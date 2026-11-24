package dev.nebula.glass

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Variable fonts for the resized lock clock (user requests 2026-11-24): the official variable Rubik
 * and Oswald stand in for Nubia's static copies, so boldness moves a real weight axis. Nubia's other
 * clock fonts have no variable version and are drawn as they are. SIL Open Font License, shipped in
 * the module's assets (`assets/fonts`, licences beside them). The hooks read the files straight from
 * the module's APK ([apkPath], set by [hook.ModulePath]).
 */
object ClockFonts {
    class Family(val id: String, val label: String, internal val file: String)

    /** A variation axis's range, in the font's units. */
    data class Axis(val min: Float, val default: Float, val max: Float)

    class Loaded(val family: Family, val typeface: Typeface, val axes: Map<String, Axis>)

    /**
     * Roboto Flex replaces Nubia's first clock font (its thin digits; user request 2026-11-24): with
     * width and figure-height axes it fills the box. Rubik and Oswald are the official variable
     * versions of Nubia's copies: the same designs, with a weight axis.
     */
    val all = listOf(
        Family("robotoflex", "Roboto Flex", "RobotoFlex.ttf"),
        Family("rubik", "Rubik", "Rubik.ttf"),
        Family("oswald", "Oswald", "Oswald.ttf"),
    )

    /** Nubia's clock font names (`FontResUtil`) that have a variable version here; the rest are drawn in Nubia's own font. */
    private val NUBIA_FONTS = mapOf("zdigit" to "robotoflex", "redmagic_digit" to "robotoflex", "zrubik" to "rubik", "zoswald" to "oswald")

    /** The variable family for Nubia's font [name], or null: draw Nubia's own font. */
    fun forNubia(name: String?): String? = NUBIA_FONTS[name]

    fun family(id: String?) = all.firstOrNull { it.id == id } ?: all[0]

    /** The module APK; the hooks get it from Xposed, the app from its own ApplicationInfo. */
    @Volatile var apkPath: String? = null

    private val loaded = HashMap<String, Loaded?>()

    /** [id]'s font, or null when it can't be read. Cached per process. */
    fun load(id: String?): Loaded? {
        val f = family(id)
        synchronized(loaded) {
            if (loaded.containsKey(f.id)) return loaded[f.id]
            val out = try {
                read(f)
            } catch (t: Throwable) {
                Log.w(TAG, "clock font ${f.id}", t); null
            }
            loaded[f.id] = out
            return out
        }
    }

    private fun read(f: Family): Loaded? {
        val apk = apkPath ?: ownApk() ?: return null
        val bytes = ZipFile(apk).use { zip ->
            val e = zip.getEntry("assets/fonts/${f.file}") ?: return null
            zip.getInputStream(e).use { it.readBytes() }
        }
        val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
        val font = Font.Builder(buffer).build()
        val face = Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
        val axes = axes(bytes)
        Log.i(TAG, "clock font ${f.id} ${bytes.size / 1024} KB axes=${axes.keys}")
        return Loaded(f, face, axes)
    }

    /** The APK of this package, found through whichever process we're in. */
    private fun ownApk(): String? = try {
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context
        app?.packageManager?.getApplicationInfo(GlassSettings.PKG, 0)?.sourceDir?.also { apkPath = it }
    } catch (t: Throwable) {
        Log.w(TAG, "module apk", t); null
    }

    /** The `fvar` table's axes: tag -> range. Empty for a static font. */
    private fun axes(d: ByteArray): Map<String, Axis> {
        val b = ByteBuffer.wrap(d)   // big-endian, as sfnt is
        val tables = b.getShort(4).toInt() and 0xFFFF
        var fvar = -1
        for (i in 0 until tables) {
            val rec = 12 + 16 * i
            if (String(d, rec, 4, Charsets.ISO_8859_1) == "fvar") fvar = b.getInt(rec + 8)
        }
        if (fvar < 0) return emptyMap()
        val first = fvar + (b.getShort(fvar + 4).toInt() and 0xFFFF)
        val count = b.getShort(fvar + 8).toInt() and 0xFFFF
        val size = b.getShort(fvar + 10).toInt() and 0xFFFF
        val out = LinkedHashMap<String, Axis>()
        for (i in 0 until count) {
            val a = first + i * size
            out[String(d, a, 4, Charsets.ISO_8859_1)] =
                Axis(b.getInt(a + 4) / 65536f, b.getInt(a + 8) / 65536f, b.getInt(a + 12) / 65536f)
        }
        return out
    }

    private const val TAG = "NebulaGlass"
}
