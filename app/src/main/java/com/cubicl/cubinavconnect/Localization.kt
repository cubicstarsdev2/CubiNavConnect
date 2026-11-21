package com.cubicl.cubinavconnect

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.util.Locale

/**
 * Language ids are shared with the firmware (SETUP_CONFIG byte 2, NVS key "language").
 * Never renumber them.
 */
object Lang {
    const val EN = 0
    const val ES = 1
    const val PT = 2
    const val FR = 3
    const val DE = 4

    class Info(val id: Int, val tag: String, val nativeName: String)

    val ALL = listOf(
        Info(EN, "en", "English"),
        Info(ES, "es", "Español"),
        Info(PT, "pt", "Português"),
        Info(FR, "fr", "Français"),
        Info(DE, "de", "Deutsch"),
    )

    /**
     * Offered in the pickers. PT / FR / DE have reserved ids but are added here only once the app strings
     * (res/values-xx) AND the device glyphs / strings exist, otherwise the app and the display would disagree.
     */
    val AVAILABLE = listOf(EN, ES)

    fun valid(id: Int) = AVAILABLE.contains(id)
    fun nativeName(id: Int) = ALL.firstOrNull { it.id == id }?.nativeName ?: "English"
    fun tag(id: Int) = ALL.firstOrNull { it.id == id }?.tag ?: "en"

    /** Wraps [base] so every resource lookup uses the language chosen in the app (not the phone's). */
    fun wrap(base: Context): Context {
        val id = base.getSharedPreferences("cubinav", Context.MODE_PRIVATE).getInt("language", EN)
        val locale = Locale.forLanguageTag(tag(if (valid(id)) id else EN))
        Locale.setDefault(locale)
        val cfg = android.content.res.Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }
}

/** Localized strings for code with no Compose context (banners, notifications, OTA messages). Follows the app language. */
object L {
    private var cachedId = -1
    private var cached: Context? = null

    fun s(id: Int, vararg args: Any): String {
        val app = NavController.app
        val lang = app.getSharedPreferences("cubinav", Context.MODE_PRIVATE).getInt("language", Lang.EN)
        val c = synchronized(this) {
            if (cached == null || cachedId != lang) { cached = Lang.wrap(app); cachedId = lang }
            cached!!
        }
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}