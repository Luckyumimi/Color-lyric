package shop.terramoor.github.io.diagnostics

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import shop.terramoor.github.io.systemui.NativeContract

/** Receives a bounded batch only from the device's SystemUI process. */
class DiagnosticProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != RECORD) return null
        val context = requireNotNull(context)
        if (callingPackage != NativeContract.SYSTEM_UI ||
            Binder.getCallingUid() != context.packageManager.getPackageUid(NativeContract.SYSTEM_UI, 0)
        ) {
            throw SecurityException("SystemUI caller required")
        }
        val lines = requireNotNull(extras?.getStringArrayList(LINES))
        require(lines.size in 1..DiagnosticStore.BATCH_SIZE)
        require(lines.all { it.length <= DiagnosticStore.MAX_LINE_LENGTH && it == diagnosticLine(it) })
        return Bundle().apply { putBoolean(STORED, DiagnosticStore.append(context, lines)) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        const val RECORD = "record"
        const val LINES = "lines"
        const val STORED = "stored"
        private const val SUFFIX = ".TerraMoorDiagnostics"

        fun systemUiLog(modulePackage: () -> String): DiagnosticLog =
            DiagnosticLog("SystemUI") { batch ->
                // A module can receive its package callback before SystemUI's Application exists.
                val application = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication").invoke(null) as? Application
                if (application == null) return@DiagnosticLog false
                val uri = Uri.parse("content://${modulePackage()}$SUFFIX")
                application.contentResolver.call(uri, RECORD, null, Bundle().apply {
                    putStringArrayList(LINES, ArrayList(batch))
                })?.getBoolean(STORED) == true
            }
    }
}
