package com.cubicl.cubinavconnect

/**
 * App-side states of the first-time setup transaction (plan section 9).
 * NOT_STARTED = the wizard is being filled in. The plan's READY_TO_SEND / DEVICE_SAVING are folded into
 * NOT_STARTED and WAITING_FOR_ACK: the app cannot observe the device while it writes NVS.
 */
enum class SetupState { NOT_STARTED, SENDING, WAITING_FOR_ACK, DEVICE_RESTARTING, RECONNECTING, COMPLETE, FAILED }

enum class SetupError { DISCONNECTED, TIMEOUT, VERSION, LANGUAGE, SETTINGS, NVS, RECONNECT_TIMEOUT, NOT_CONFIGURED }

data class SetupUi(val state: SetupState = SetupState.NOT_STARTED, val error: SetupError? = null)

/** What the wizard collects. Only device preferences + language; phone / network settings never go to the device. */
data class SetupDraft(
    val language: Int,
    val imperial: Boolean,
    val headingUp: Boolean,
    val invert: Boolean,
    val showFps: Boolean,
    val zoom: Int,
) {
    fun flags(): Int {
        var f = 0
        if (headingUp) f = f or Proto.CFG_HEADING_UP
        if (invert) f = f or Proto.CFG_INVERT
        if (showFps) f = f or Proto.CFG_SHOW_FPS
        if (imperial) f = f or Proto.CFG_IMPERIAL
        return f
    }

    companion object {
        const val ZOOM_MIN = 20     // same range as the Settings slider (firmware accepts 5..2000)
        const val ZOOM_MAX = 200

        fun from(s: Settings) = SetupDraft(
            language = if (Lang.valid(s.language)) s.language else Lang.EN,
            imperial = s.imperial, headingUp = s.headingUp, invert = s.invert, showFps = s.showFps,
            zoom = s.zoom.coerceIn(ZOOM_MIN, ZOOM_MAX),
        )
    }
}