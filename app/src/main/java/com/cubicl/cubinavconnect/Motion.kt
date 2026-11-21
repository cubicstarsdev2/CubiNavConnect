package com.cubicl.cubinavconnect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One place for the app's motion language, so every screen moves the same way:
 * quick exits, slightly slower eased entrances, small distances.
 */
object Motion {
    const val EXIT = 90      // outgoing content leaves fast...
    const val SHORT = 180    // small elements (icons, chips, FAB)
    const val MEDIUM = 280   // ...and incoming content eases in (cards, pages, size changes)
}

// ---- Screen-level transitions (use inside AnimatedContent's transitionSpec) ------------------------

/** Material "fade through": the old screen fades out, then the new one fades in while easing up from 94%. */
fun fadeThrough(): ContentTransform =
    (fadeIn(tween(220, delayMillis = Motion.EXIT, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(220, delayMillis = Motion.EXIT, easing = LinearOutSlowInEasing), initialScale = 0.94f)) togetherWith
        fadeOut(tween(Motion.EXIT, easing = FastOutLinearInEasing))

/** Short horizontal slide + fade: [forward] enters from the right, going back enters from the left. */
fun slideThrough(forward: Boolean): ContentTransform {
    val dir = if (forward) 1 else -1
    return (fadeIn(tween(220, delayMillis = 70, easing = LinearOutSlowInEasing)) +
        slideInHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { dir * it / 10 }) togetherWith
        (fadeOut(tween(Motion.EXIT, easing = FastOutLinearInEasing)) +
            slideOutHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { -dir * it / 10 })
}

// ---- Element-level helpers -----------------------------------------------------------------------

/**
 * Fades + grows/shrinks its content in and out.
 *
 * [gap] is the space below the content and lives *inside* the animation. Use it instead of
 * `Arrangement.spacedBy` on the parent: a hidden AnimatedVisibility is still a (zero-size) child, so
 * spacedBy would keep reserving a gap for it.
 *
 * Pass `clip = false` for content that casts a shadow (elevated cards), otherwise the shadow is cut off.
 */
@Composable
fun Reveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    clip: Boolean = true,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.MEDIUM, delayMillis = 40, easing = LinearOutSlowInEasing)) +
            expandVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing), expandFrom = Alignment.Top, clip = clip),
        exit = fadeOut(tween(Motion.EXIT, easing = FastOutLinearInEasing)) +
            shrinkVertically(tween(Motion.SHORT, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top, clip = clip),
    ) {
        Box(Modifier.padding(bottom = gap)) { content() }
    }
}

/** Scale + fade pop for small floating controls (the recenter button). */
@Composable
fun Pop(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.SHORT)) + scaleIn(tween(Motion.SHORT, easing = LinearOutSlowInEasing), initialScale = 0.6f),
        exit = fadeOut(tween(Motion.EXIT)) + scaleOut(tween(Motion.EXIT), targetScale = 0.6f),
    ) { content() }
}

/** Fades + rises a little the first time it is composed (new scan results, list rows). Never animates out. */
@Composable
fun AppearOnEnter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        modifier = modifier,
        enter = fadeIn(tween(Motion.MEDIUM, easing = LinearOutSlowInEasing)) +
            slideInVertically(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { it / 5 },
        exit = ExitTransition.None,
    ) { content() }
}

/**
 * Cross-fades between states and animates the size change, without clipping shadows
 * (for elevated cards that float over the map).
 */
@Composable
fun <S> SoftSwap(state: S, modifier: Modifier = Modifier, label: String = "swap", content: @Composable (S) -> Unit) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(Motion.EXIT))).using(SizeTransform(clip = false))
        },
        label = label,
    ) { s -> content(s) }
}

/** Eases height/width changes of whatever is inside. Clips to bounds, so don't put shadowed children directly in it. */
fun Modifier.smoothSize(): Modifier =
    this.animateContentSize(tween(Motion.MEDIUM, easing = FastOutSlowInEasing))

/**
 * Returns [value] while [visible], and the last value seen while visible once it isn't. Exit animations
 * should keep showing what was on screen instead of morphing into the "empty" state (e.g. a card whose
 * destination just became null).
 */
@Composable
fun <T> rememberWhileVisible(value: T, visible: Boolean): T {
    val holder = remember { arrayOf<Any?>(value) }
    if (visible) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T
}
