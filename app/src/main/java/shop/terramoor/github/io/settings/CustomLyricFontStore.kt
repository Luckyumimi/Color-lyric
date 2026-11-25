package shop.terramoor.github.io.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.fonts.Font
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import shop.terramoor.github.io.systemui.NativeContract
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal fun isCustomFontId(value: String): Boolean =
    value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal object CustomLyricFontStore {
    private const val MAX_BYTES = 128L * 1024 * 1024
    private const val SUFFIX = ".CustomLyricFont"

    fun uri(modulePackage: String, id: String): Uri = Uri.Builder().scheme("content")
        .authority(modulePackage + SUFFIX).appendPath(id).build()

    private fun directory(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, "lyric-fonts")

    fun file(context: Context, id: String): File {
        require(isCustomFontId(id))
        return File(directory(context), "$id.font")
    }

    fun import(context: Context, source: Uri): String {
        val id = UUID.randomUUID().toString().replace("-", "")
        val target = file(context, id)
        check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        try {
            val input = requireNotNull(context.contentResolver.openInputStream(source))
            input.use { stream ->
                target.outputStream().buffered().use { output ->
                    val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = stream.read(bytes)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= MAX_BYTES) { "Font file is too large" }
                        output.write(bytes, 0, count)
                    }
                    require(total >= 12) { "Font file is empty or incomplete" }
                }
            }
            val signature = target.inputStream().use { stream ->
                ByteArray(4).also { check(stream.read(it) == it.size) }
            }
            require(signature.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                signature.contentEquals(byteArrayOf(0x4f, 0x54, 0x54, 0x4f)) ||
                signature.contentEquals(byteArrayOf(0x74, 0x72, 0x75, 0x65))) { "Not a TTF or OTF font" }
            Font.Builder(target).build()
            return id
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    fun delete(context: Context, id: String?) {
        if (id != null && isCustomFontId(id)) file(context, id).delete()
    }
}

/** Serves only a selected local font to the module app and the verified SystemUI UID. */
class CustomLyricFontProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = requireNotNull(context)
        val caller = Binder.getCallingUid()
        val systemUiUid = runCatching { context.packageManager.getPackageUid(NativeContract.SYSTEM_UI, 0) }.getOrNull()
        if (caller != context.applicationInfo.uid && caller != systemUiUid) {
            throw SecurityException("SystemUI caller required")
        }
        val id = uri.pathSegments.singleOrNull()?.takeIf(::isCustomFontId) ?: throw FileNotFoundException()
        if (mode != "r") throw FileNotFoundException()
        return ParcelFileDescriptor.open(CustomLyricFontStore.file(context, id), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
