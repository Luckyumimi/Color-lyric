package shop.terramoor.github.io.systemui

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Handler
import android.os.Looper
import shop.terramoor.github.io.settings.CustomLyricFontStore
import shop.terramoor.github.io.settings.isCustomFontId
import java.util.concurrent.Executors

internal class JapaneseLyricFont(
    private val context: Context,
    private val modulePackage: String = context.packageName,
    private val onFailure: (Throwable) -> Unit = {},
    private val onCustomFontReady: () -> Unit = {},
) {
    private val regularFamily: FontFamily? by lazy { loadFamily("fonts/japanese_lyric_w6.ttf") }
    private val boldFamily: FontFamily? by lazy { loadFamily("fonts/japanese_lyric_w7.ttf") }

    private fun loadFamily(path: String): FontFamily? =
        runCatching {
            val module = if (context.packageName == modulePackage) context
                else context.createPackageContext(modulePackage, 0)
            FontFamily.Builder(Font.Builder(module.assets, path).build()).build()
        }.onFailure(onFailure).getOrNull()

    private val regularTypeface: Typeface? by lazy {
        regularFamily?.let { family ->
            Typeface.CustomFallbackBuilder(family).setStyle(FontStyle(600, FontStyle.FONT_SLANT_UPRIGHT)).build()
        }
    }

    private val boldTypeface: Typeface? by lazy {
        boldFamily?.let { family ->
            val builder = Typeface.CustomFallbackBuilder(family)
            regularFamily?.let { builder.addCustomFallback(it) }
            builder.setStyle(FontStyle(700, FontStyle.FONT_SLANT_UPRIGHT)).build()
        } ?: regularTypeface
    }

    fun typeface(bold: Boolean): Typeface? = if (bold) boldTypeface else regularTypeface

    private data class CustomFaces(val regular: Typeface, val medium: Typeface, val bold: Typeface)
    private val main = Handler(Looper.getMainLooper())
    private var requestedCustomId: String? = null
    private var customFaces: CustomFaces? = null

    fun requestCustom(id: String?) {
        val selected = id?.takeIf(::isCustomFontId)
        if (requestedCustomId == selected) return
        requestedCustomId = selected
        customFaces = null
        if (selected == null) return
        loader.execute {
            val result = runCatching {
                val uri = CustomLyricFontStore.uri(modulePackage, selected)
                val application = context.applicationContext ?: context
                val descriptor = requireNotNull(application.contentResolver.openFileDescriptor(uri, "r"))
                descriptor.use {
                    val family = FontFamily.Builder(Font.Builder(it).build()).build()
                    fun face(weight: Int): Typeface = Typeface.CustomFallbackBuilder(family)
                        .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT)).build()
                    CustomFaces(face(400), face(600), face(700))
                }
            }
            main.post {
                if (requestedCustomId == selected) {
                    customFaces = result.onFailure(onFailure).getOrNull()
                    onCustomFontReady()
                }
            }
        }
    }

    fun customTypeface(bold: Boolean, translation: Boolean): Typeface? = customFaces?.let {
        if (translation) it.regular else if (bold) it.bold else it.medium
    }

    private companion object {
        val loader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ColorLyricFont").apply { isDaemon = true }
        }
    }
}
