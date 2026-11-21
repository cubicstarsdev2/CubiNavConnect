package com.cubicl.cubinavconnect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val STEPS = 3

// Page keys for the wizard's AnimatedContent: 0..STEPS-1 are the steps, the rest are the transfer states.
private const val PAGE_APPLYING = 10
private const val PAGE_DONE = 11
private const val PAGE_FAILED = 12

/**
 * Full-screen first-time setup, shown when the device reports "not configured" in HELLO_ACK (and while a
 * setup transaction is running). All state lives in NavController, so it survives the activity recreate
 * that applying a new app language causes.
 */
@Composable
fun SetupWizard(frozen: Boolean = false) {
    val liveUi by NavController.setup.collectAsState()
    val liveStep by NavController.setupStep.collectAsState()
    val liveDraft by NavController.setupDraft.collectAsState()
    // When the wizard finishes, that state resets while the screen is still fading out; keep what was on screen.
    val ui = rememberWhileVisible(liveUi, !frozen)
    val step = rememberWhileVisible(liveStep, !frozen).coerceIn(0, STEPS - 1)
    val draft = rememberWhileVisible(liveDraft, !frozen)

    val page = when (ui.state) {
        SetupState.NOT_STARTED -> step
        SetupState.SENDING, SetupState.WAITING_FOR_ACK, SetupState.DEVICE_RESTARTING, SetupState.RECONNECTING -> PAGE_APPLYING
        SetupState.COMPLETE -> PAGE_DONE
        SetupState.FAILED -> PAGE_FAILED
    }
    val applyingMsg = when (ui.state) {
        SetupState.DEVICE_RESTARTING -> R.string.setup_restarting
        SetupState.RECONNECTING -> R.string.setup_reconnecting
        else -> R.string.setup_sending
    }
    // Lives outside the pages so it glides between steps instead of being rebuilt on every page.
    val progress by animateFloatAsState((step + 1) / STEPS.toFloat(), tween(Motion.MEDIUM + 120), label = "setupProgress")

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Reveal(visible = ui.state == SetupState.NOT_STARTED, gap = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Crossfade(targetState = step, animationSpec = tween(Motion.SHORT), label = "stepLabel") { st ->
                        Text(stringResource(R.string.setup_step_of, st + 1, STEPS), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    // steps slide forward/back; moving into or out of the transfer states fades through
                    if (initialState < PAGE_APPLYING && targetState < PAGE_APPLYING) slideThrough(forward = targetState > initialState)
                    else fadeThrough()
                },
                label = "setupPage",
            ) { p ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (p) {
                        0 -> SetupLanguageScreen(draft)
                        1 -> SetupDisplayScreen(draft)
                        2 -> SetupReviewScreen(draft)
                        PAGE_APPLYING -> SetupApplyingScreen(applyingMsg)
                        PAGE_DONE -> SetupDoneScreen()
                        else -> SetupFailedScreen(ui.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepHeader(title: Int, subtitle: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NavRow(step: Int, nextLabel: Int, onNext: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(nextLabel)) }
    if (step > 0) TextButton(onClick = { NavController.setSetupStep(step - 1) }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_back))
    } else TextButton(onClick = { NavController.exitSetup() }, Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_exit))
    }
}

// ---- Step 1: language ----------------------------------------------------------------------------
@Composable
private fun SetupLanguageScreen(d: SetupDraft) {
    val ctx = LocalContext.current
    StepHeader(R.string.setup_language_title, R.string.setup_language_sub)
    LanguagePicker(d.language) { id ->
        if (id != d.language) {
            NavController.setSetupLanguage(id)       // saves it, so the recreated activity starts in that language
            ctx.findActivity()?.recreate()
        }
    }
    Text(stringResource(R.string.setup_language_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    NavRow(0, R.string.setup_next) { NavController.setSetupStep(1) }
}

/** Shared by the wizard and Settings. */
@Composable
fun LanguagePicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Lang.AVAILABLE.forEach { id ->
            val rowColor by animateColorAsState(
                if (id == selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                tween(Motion.SHORT), label = "langRow",
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = rowColor,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(id) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = id == selected, onClick = { onSelect(id) })
                    Text(Lang.nativeName(id), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// ---- Step 2: display preferences -----------------------------------------------------------------
@Composable
private fun ChoiceRow(label: Int, options: List<Pair<Int, Boolean>>, onPick: (Int) -> Unit) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, (text, sel) ->
            FilterChip(selected = sel, onClick = { onPick(i) }, label = { Text(stringResource(text)) })
        }
    }
}

@Composable
private fun SetupDisplayScreen(d: SetupDraft) {
    StepHeader(R.string.setup_display_title, R.string.setup_display_sub)
    ChoiceRow(R.string.setup_units, listOf(R.string.setup_units_metric to !d.imperial, R.string.setup_units_imperial to d.imperial)) {
        NavController.updateDraft { s -> s.copy(imperial = it == 1) }
    }
    ChoiceRow(R.string.setup_orientation, listOf(R.string.setup_heading_up to d.headingUp, R.string.setup_north_up to !d.headingUp)) {
        NavController.updateDraft { s -> s.copy(headingUp = it == 0) }
    }
    ChoiceRow(R.string.setup_colors, listOf(R.string.setup_colors_normal to !d.invert, R.string.setup_colors_inverted to d.invert)) {
        NavController.updateDraft { s -> s.copy(invert = it == 1) }
    }
    ChoiceRow(R.string.setup_fps, listOf(R.string.setup_off to !d.showFps, R.string.setup_on to d.showFps)) {
        NavController.updateDraft { s -> s.copy(showFps = it == 1) }
    }
    HorizontalDivider()
    Text(stringResource(R.string.setup_zoom, d.zoom), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Slider(
        value = d.zoom.toFloat(),
        onValueChange = { v -> NavController.updateDraft { s -> s.copy(zoom = v.roundToInt()) } },
        valueRange = SetupDraft.ZOOM_MIN.toFloat()..SetupDraft.ZOOM_MAX.toFloat(),
    )
    NavRow(1, R.string.setup_next) { NavController.setSetupStep(2) }
}

// ---- Step 3: review ------------------------------------------------------------------------------
@Composable
private fun ReviewRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupReviewScreen(d: SetupDraft) {
    StepHeader(R.string.setup_review_title, R.string.setup_review_sub)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ReviewRow(R.string.setup_language_title, Lang.nativeName(d.language))
            ReviewRow(R.string.setup_units, stringResource(if (d.imperial) R.string.setup_units_imperial else R.string.setup_units_metric))
            ReviewRow(R.string.setup_orientation, stringResource(if (d.headingUp) R.string.setup_heading_up else R.string.setup_north_up))
            ReviewRow(R.string.setup_colors, stringResource(if (d.invert) R.string.setup_colors_inverted else R.string.setup_colors_normal))
            ReviewRow(R.string.setup_fps, stringResource(if (d.showFps) R.string.setup_on else R.string.setup_off))
            ReviewRow(R.string.setup_zoom_short, d.zoom.toString())
        }
    }
    Text(stringResource(R.string.setup_restart_note), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.tertiary)
    NavRow(2, R.string.setup_finish) { NavController.startSetupTransfer() }
}

// ---- Transfer ------------------------------------------------------------------------------------
@Composable
private fun ColumnScope.SetupApplyingScreen(msg: Int) {
    Spacer(Modifier.height(48.dp))
    CircularProgressIndicator(Modifier.size(48.dp).align(Alignment.CenterHorizontally))
    Crossfade(targetState = msg, animationSpec = tween(Motion.MEDIUM), modifier = Modifier.align(Alignment.CenterHorizontally), label = "applyMsg") { m ->
        Text(stringResource(m), style = MaterialTheme.typography.titleMedium)
    }
    Text(stringResource(R.string.setup_keep_close), color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun SetupDoneScreen() {
    Spacer(Modifier.height(32.dp))
    Icon(TIcons.Check, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.setup_done_body), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { NavController.finishSetupFlow() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.setup_done_button))
    }
}

@Composable
private fun SetupFailedScreen(e: SetupError?) {
    val msg = when (e) {
        SetupError.DISCONNECTED -> R.string.setup_err_disconnected
        SetupError.TIMEOUT -> R.string.setup_err_timeout
        SetupError.VERSION -> R.string.setup_err_version
        SetupError.LANGUAGE -> R.string.setup_err_language
        SetupError.SETTINGS -> R.string.setup_err_settings
        SetupError.NVS -> R.string.setup_err_nvs
        SetupError.RECONNECT_TIMEOUT -> R.string.setup_err_reconnect
        SetupError.NOT_CONFIGURED -> R.string.setup_err_not_configured
        null -> R.string.setup_err_timeout
    }
    Spacer(Modifier.height(32.dp))
    Text(stringResource(R.string.setup_failed_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(msg), color = MaterialTheme.colorScheme.error)
    Button(onClick = { NavController.retrySetup() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.setup_retry))
    }
    OutlinedButton(onClick = { NavController.backToSetupSettings() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(stringResource(R.string.setup_edit))
    }
}

/** First-run screen: scan, pick the CubiNav, pair. Once it connects, HELLO_ACK decides what comes next. */
@Composable
fun FindDeviceScreen(onSkip: () -> Unit) {
    val state by NavController.bleState.collectAsState()
    val found by NavController.bleFound.collectAsState()
    val scanning by NavController.bleScanning.collectAsState()
    val busy = state == BleState.CONNECTING || state == BleState.BONDING || state == BleState.CONNECTED || state == BleState.RECONNECTING

    androidx.compose.runtime.LaunchedEffect(Unit) { NavController.scan() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(TIcons.Bluetooth, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.find_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.find_sub), color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state == BleState.BONDING) Text(stringResource(R.string.find_passkey), color = MaterialTheme.colorScheme.tertiary)
            if (state == BleState.OFF) Text(stringResource(R.string.find_bt_off), color = MaterialTheme.colorScheme.error)

            // scanning <-> connecting fades through; devices fade in as the scan discovers them
            AnimatedContent(
                targetState = busy,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = { fadeThrough() },
                label = "findBusy",
            ) { isBusy ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (isBusy) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Text(stringResource(R.string.find_connecting))
                    } else {
                        if (found.isEmpty()) Text(stringResource(if (scanning) R.string.find_scanning else R.string.find_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        found.forEach { d ->
                            androidx.compose.runtime.key(d.address) {
                                AppearOnEnter(Modifier.fillMaxWidth()) {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        modifier = Modifier.fillMaxWidth().clickable { NavController.connect(d.address, d.name) },
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
                                            Text(d.name ?: "CubiNav", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                            Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        Button(onClick = { NavController.scan() }, enabled = !scanning, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(stringResource(if (scanning) R.string.find_scanning else R.string.find_scan))
                        }
                        TextButton(onClick = onSkip, Modifier.fillMaxWidth()) { Text(stringResource(R.string.find_skip)) }
                    }
                }
            }
        }
    }
}