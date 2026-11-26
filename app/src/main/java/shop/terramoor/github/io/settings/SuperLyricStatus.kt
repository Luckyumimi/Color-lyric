package shop.terramoor.github.io.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import shop.terramoor.github.io.R
import shop.terramoor.github.io.settings.coui.TagView
import shop.terramoor.github.io.settings.coui.TagViewDefaults

@Composable
internal fun SuperLyricStatus(connection: String?, onError: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(SuperLyricDownload.isInstalled(context)) }
    var downloading by remember { mutableStateOf(false) }
    var downloaded by rememberSaveable { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installed = SuperLyricDownload.isInstalled(context)
        if (installed) downloaded = false
    }
    fun install() {
        runCatching { installer.launch(SuperLyricDownload.installIntent(context)) }.onFailure {
            downloaded = false
            onError()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (downloaded && context.packageManager.canRequestPackageInstalls()) install()
    }
    DisposableEffect(context) {
        installed = SuperLyricDownload.isInstalled(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == SuperLyricDownload.PACKAGE) {
                    installed = SuperLyricDownload.isInstalled(context)
                    if (installed) downloaded = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val active = installed && connection == "registered"
    val status = when {
        !installed -> R.string.superlyric_not_installed
        active -> R.string.superlyric_active
        else -> R.string.superlyric_waiting
    }
    BasicComponent(title = "SuperLyric", endActions = {
        val label = stringResource(status)
        if (active) {
            TagView(label, colors = TagViewDefaults.tagViewColors(
                contentColor = Color(0xFF007D47), containerColor = Color(0xFFD9F8E8),
            ))
        } else TagView(label)
    })
    if (!installed) {
        HubDivider()
        val label = when {
            downloading -> R.string.superlyric_downloading
            downloaded -> R.string.superlyric_install
            else -> R.string.superlyric_download
        }
        HubRow(stringResource(label), enabled = !downloading) {
            scope.launch {
                try {
                    if (!downloaded) {
                        downloading = true
                        SuperLyricDownload.download(context)
                        downloaded = true
                    }
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else permission.launch(SuperLyricDownload.permissionIntent(context))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onError()
                } finally {
                    downloading = false
                }
            }
        }
    }
}
