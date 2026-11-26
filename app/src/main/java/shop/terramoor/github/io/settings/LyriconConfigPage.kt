package shop.terramoor.github.io.settings

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.suqi8.coui.kmp.basic.BasicComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.terramoor.github.io.R

private data class InstalledProvider(val id: String, val label: String, val version: String)

@Composable
internal fun LyriconConfigPage(showCatalog: Boolean = false, onCatalog: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as TerraMoorApp
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    var serviceError by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var disabled by remember { mutableStateOf(BuiltinLyricsConfig.disabledLyriconProviders(context)) }
    val failure = stringResource(R.string.lyricon_download_failed)
    val restart = stringResource(R.string.hle_pack_restart)
    val serviceMissing = stringResource(R.string.hle_pack_lsposed_required)
    fun notify(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val installed by produceState(emptyList<HleProviderStore.Installed>(), revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.diagnosticService()?.let(HleProviderStore::installed) }.getOrNull().orEmpty()
        }
    }
    if (showCatalog) {
        var refresh by remember { mutableStateOf(0) }
        val catalog by produceState<Result<List<HleProviderStore.Listing>>?>(null, refresh) {
            value = null
            value = try { Result.success(HleProviderStore.catalog()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
        }
        val entries = catalog?.getOrNull()
        when {
            entries == null && catalog == null -> SettingSummary(stringResource(R.string.hle_pack_loading))
            entries == null -> SettingsGroup(null) {
                HubNavigation(stringResource(R.string.hle_pack_loading_failed)) { refresh++ }
            }
            entries.isEmpty() -> SettingSummary(stringResource(R.string.lyricon_catalog_empty))
            else -> entries.forEach { item ->
                val existing = installed.firstOrNull { it.definition.id == item.definition.id }
                SettingsGroup(null) {
                    BasicComponent(
                        title = item.definition.displayName,
                        summary = listOfNotNull(item.release.versionName, item.definition.targetPackages.joinToString(" · ")).joinToString("\n"),
                        endActions = {
                            HubPrimaryButton(
                                label = stringResource(when {
                                    downloading == item.definition.id -> R.string.lyricon_downloading
                                    existing?.version == item.release.versionName -> R.string.hle_pack_added
                                    else -> R.string.lyricon_download
                                }),
                                enabled = downloading == null && existing?.version != item.release.versionName,
                            ) {
                                val service = app.diagnosticService()
                                if (service == null) {
                                    notify(serviceMissing)
                                    return@HubPrimaryButton
                                }
                                downloading = item.definition.id
                                scope.launch {
                                    try {
                                        HleProviderStore.install(service, item)
                                        revision++
                                        runCatching { HleProviderStore.requestScope(context, service, item.definition) }
                                        notify(restart)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { notify(failure) }
                                    finally { downloading = null }
                                }
                            }
                        },
                    )
                }
            }
        }
        return
    }
    SettingsGroup(null) {
        HubNavigation(stringResource(R.string.hle_pack_catalog), onClick = onCatalog)
    }
    SettingsGroup(stringResource(R.string.hle_pack_installed)) {
        if (installed.isEmpty()) BasicComponent(title = stringResource(R.string.hle_pack_empty))
        installed.forEachIndexed { index, item ->
            if (index > 0) HubDivider()
            TactileSwitchPreference(item.enabled, { next ->
                val service = app.diagnosticService()
                if (service == null) notify(serviceMissing) else scope.launch {
                    try {
                        withContext(Dispatchers.IO) { HleProviderStore.setEnabled(service, item.definition.id, next) }
                        revision++
                        if (next) runCatching { HleProviderStore.requestScope(context, service, item.definition) }
                        notify(restart)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { serviceError = true }
                }
            }, item.definition.displayName, true, summary = item.version)
        }
    }
    if (serviceError) SettingSummary(failure)
    val independent by produceState(emptyList<InstalledProvider>(), revision) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                .mapNotNull { pkg ->
                    val info = pkg.applicationInfo ?: return@mapNotNull null
                    if (pkg.packageName == context.packageName || info.metaData?.getBoolean("lyricon_module") != true) return@mapNotNull null
                    InstalledProvider(pkg.packageName, pm.getApplicationLabel(info).toString(), pkg.versionName.orEmpty())
                }.sortedBy { it.label.lowercase() }
        }
    }
    if (independent.isNotEmpty()) SettingsGroup(stringResource(R.string.lyricon_installed)) {
        independent.forEachIndexed { index, provider ->
            if (index > 0) HubDivider()
            TactileSwitchPreference(
                checked = provider.id !in disabled,
                onCheckedChange = { enabled ->
                    disabled = if (enabled) disabled - provider.id else disabled + provider.id
                    BuiltinLyricsConfig.setDisabledLyriconProviders(context, disabled)
                },
                title = provider.label,
                enabled = true,
                summary = listOf(provider.version, provider.id).filter(String::isNotBlank).joinToString(" · "),
            )
        }
    }
}
