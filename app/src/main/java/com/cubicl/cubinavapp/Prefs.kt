package com.cubicl.cubinavapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

object Roads {
    /**
     * Classes exposed as UI toggles. A ticked box = this road type is allowed.
     * OSRM can only avoid classes that the server's profile defines (see osrm-setup.md);
     * the Settings screen hides the ones the server does not report in capabilities.json.
     */
    val ALL = setOf("motorway", "trunk", "avenue", "residential", "toll", "ferry", "unpaved")
    /** What a stock OSRM car profile can avoid; used when the server publishes no capabilities.json. */
    val STOCK = setOf("motorway", "toll", "ferry")
    val LABELS = mapOf(
        "motorway" to "Motorways / autopistas",
        "trunk" to "Highways / routes (trunk)",
        "avenue" to "Avenues (primary / secondary)",
        "residential" to "Residential streets",
        "toll" to "Toll roads",
        "ferry" to "Ferries",
        "unpaved" to "Unpaved / tracks",
    )
}

data class Dest(val lat: Double, val lon: Double, val label: String, val confirmed: Boolean = true)

data class Settings(
    val osrmUrl: String = "https://router.project-osrm.org",
    val profile: String = "driving",
    val presetId: String = "",
    val nominatimUrl: String = "https://nominatim.openstreetmap.org",
    val userAgent: String = "CubiNav/1.0 (set-your-contact@example.com)",
    val contactEmail: String = "",
    // Read-only, repo-scoped token for the firmware download. Meant to ship in the app.
    val githubToken: String = "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",
    val styleUrl: String = "osm",
    val imperial: Boolean = false,
    val headingUp: Boolean = true,
    val invert: Boolean = false,
    val showFps: Boolean = false,
    val zoom: Int = 60,
    val allowed: Set<String> = Roads.ALL,
    val maxAccuracy: Int = 50,
    val deviceAddress: String = "",
    val deviceName: String = "",
    val debug: Boolean = false,
    val advanced: Boolean = false,     // reveals every server link, firmware source and the developer tools
    val capsUrl: String = "",          // empty = <osrmUrl>/capabilities.json
    val allRoads: Boolean = false,     // show every road toggle even without capabilities.json
    // README whose first "## x.y.z" line is the latest firmware version.
    val readmeUrl: String = "https://raw.githubusercontent.com/cubicstarsdev2/CubiNav/main/README.md",
    // Direct link to the firmware image.
    val binUrl: String = "https://raw.githubusercontent.com/cubicstarsdev2/CubiNav/main/.pio/build/nodemcu-32s/firmware.bin",
)

fun sanitizeFirmwareUrl(url: String): String {
    val trimmed = url.trim()
    if (trimmed.contains("raw.githubusercontent.com") && trimmed.contains("/refs/heads/")) {
        return trimmed.replace("/refs/heads/", "/")
    }
    return trimmed
}

/** Contact address that goes into the User-Agent (Nominatim policy requires a real one). */
object Contact {
    private val EMAIL = Regex("^[^@\\s()]+@[^@\\s()]+\\.[^@\\s()]+$")
    const val PLACEHOLDER = "set-your-contact@example.com"
    fun isValid(email: String) = EMAIL.matches(email.trim()) && email.trim() != PLACEHOLDER
    /** Same as Prefs.setContactEmail, for call sites written as Contact.setContactEmail(...). */
    fun setContactEmail(email: String) = NavController.setContactEmail(email)
    fun userAgent(email: String) = "CubiNav/1.0 (${email.trim().ifEmpty { PLACEHOLDER }})"
    /** The address inside "CubiNav/1.0 (addr)", or "" if it is still the placeholder. */
    fun emailOf(userAgent: String): String =
        Regex("\\(([^)]*)\\)").find(userAgent)?.groupValues?.get(1)?.takeIf { it != PLACEHOLDER }.orEmpty()
}

/** Copy of these settings with the contact e-mail stored and the User-Agent rebuilt from it. */
fun Settings.setContactEmail(email: String): Settings =
    copy(contactEmail = email.trim(), userAgent = Contact.userAgent(email))

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cubinav", Context.MODE_PRIVATE)

    val settings = MutableStateFlow(load())
    val recents = MutableStateFlow(loadDests("recents"))
    val favorites = MutableStateFlow(loadDests("favorites"))

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            osrmUrl = sp.getString("osrmUrl", d.osrmUrl)!!,
            profile = sp.getString("profile", d.profile)!!,
            presetId = sp.getString("presetId", d.presetId)!!,
            nominatimUrl = sp.getString("nominatimUrl", d.nominatimUrl)!!,
            userAgent = sp.getString("userAgent", d.userAgent)!!,
            contactEmail = sp.getString("contactEmail", "")!!,
            // an empty saved value falls back to the built-in default
            githubToken = sp.getString("githubToken", "")!!.ifEmpty { d.githubToken },
            styleUrl = sp.getString("styleUrl", d.styleUrl)!!,
            imperial = sp.getBoolean("imperial", d.imperial),
            headingUp = sp.getBoolean("headingUp", d.headingUp),
            invert = sp.getBoolean("invert", d.invert),
            showFps = sp.getBoolean("showFps", d.showFps),
            zoom = sp.getInt("zoom", d.zoom),
            // roadsV < 2: the class list changed, so older saved sets would silently forbid the new classes
            allowed = if (sp.getInt("roadsV", 0) < 2) d.allowed
            else sp.getString("allowed", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: d.allowed,
            maxAccuracy = sp.getInt("maxAccuracy", d.maxAccuracy),
            deviceAddress = sp.getString("deviceAddress", "")!!,
            deviceName = sp.getString("deviceName", "")!!,
            debug = sp.getBoolean("debug", d.debug),
            advanced = sp.getBoolean("advanced", d.advanced),
            capsUrl = sp.getString("capsUrl", "")!!,
            allRoads = sp.getBoolean("allRoads", d.allRoads),
            readmeUrl = sp.getString("readmeUrl", d.readmeUrl)!!.let {
                val s = sanitizeFirmwareUrl(it)
                if ("OWNER/REPO" in s || "/refs/heads/" in s) d.readmeUrl else s
            },
            // an earlier build saved the OWNER/REPO placeholder or bad refs/heads URL: replace with real default
            binUrl = sp.getString("binUrl", d.binUrl)!!.let {
                val s = sanitizeFirmwareUrl(it)
                if ("OWNER/REPO" in s || "/refs/heads/" in s) d.binUrl else s
            },
        )
    }

    fun update(f: (Settings) -> Settings) {
        val n = f(settings.value)
        settings.value = n
        sp.edit()
            .putString("osrmUrl", n.osrmUrl).putString("profile", n.profile).putString("presetId", n.presetId)
            .putString("nominatimUrl", n.nominatimUrl).putString("userAgent", n.userAgent)
            .putString("contactEmail", n.contactEmail).putString("githubToken", n.githubToken)
            .putString("styleUrl", n.styleUrl).putBoolean("imperial", n.imperial)
            .putBoolean("headingUp", n.headingUp).putBoolean("invert", n.invert)
            .putBoolean("showFps", n.showFps).putInt("zoom", n.zoom)
            .putString("allowed", n.allowed.joinToString(",")).putInt("maxAccuracy", n.maxAccuracy)
            .putString("deviceAddress", n.deviceAddress).putString("deviceName", n.deviceName)
            .putBoolean("debug", n.debug).putBoolean("advanced", n.advanced).putInt("roadsV", 2)
            .putString("capsUrl", n.capsUrl).putBoolean("allRoads", n.allRoads)
            .putString("binUrl", n.binUrl).putString("readmeUrl", n.readmeUrl)
            .apply()
    }

    /** Stores the contact address and puts it into the User-Agent that Nominatim/OSRM requests send. */
    fun setContactEmail(email: String) = update { it.setContactEmail(email) }

    private fun loadDests(key: String): List<Dest> = try {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Dest(o.getDouble("lat"), o.getDouble("lon"), o.optString("label"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveDests(key: String, list: List<Dest>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("lat", it.lat).put("lon", it.lon).put("label", it.label)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    private fun same(a: Dest, b: Dest) = Math.abs(a.lat - b.lat) < 1e-5 && Math.abs(a.lon - b.lon) < 1e-5

    fun addRecent(d: Dest) {
        val l = (listOf(d.copy(confirmed = true)) + recents.value.filterNot { same(it, d) }).take(10)
        recents.value = l
        saveDests("recents", l)
    }

    fun toggleFavorite(d: Dest) {
        val cur = favorites.value
        val l = if (cur.any { same(it, d) }) cur.filterNot { same(it, d) } else cur + d.copy(confirmed = true)
        favorites.value = l
        saveDests("favorites", l)
    }

    fun isFavorite(d: Dest) = favorites.value.any { same(it, d) }
}