package shop.terramoor.github.io.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.topappbar.TopAppBarSurface
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.DividerDefaults
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import com.itos.coui.layout.Scaffold as FloatingScaffold
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import io.github.suqi8.coui.kmp.utils.CouiHapticEffect
import io.github.suqi8.coui.kmp.utils.rememberCouiHaptic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shop.terramoor.github.io.R
import shop.terramoor.github.io.settings.TerraMoorApp.Connection
import kotlin.math.abs

class SettingsActivity : ComponentActivity() {
    private val app get() = application as TerraMoorApp
    private var backupBusy = mutableStateOf(false)
    private var diagnosticsBusy by mutableStateOf(false)
    private var restartBusy by mutableStateOf(false)
    private var updateResult by mutableStateOf<GitHubUpdates.Result>(GitHubUpdates.Result.Idle)
    private val onboarding by lazy { getSharedPreferences("onboarding", MODE_PRIVATE) }
    private var providerStatuses by mutableStateOf<Map<LyricsProvider, Boolean>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        setContent {
            val state by app.state.collectAsState()
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) exportSettings(uri)
            }
            val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importSettings(uri)
            }
            val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) saveDiagnostics(uri)
            }
            COUITheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                SettingsPage(
                    state = state,
                    app = app,
                    version = version.versionName.orEmpty(),
                    providerStatuses = providerStatuses,
                    askForProviderCheck = !onboarding.contains(PROVIDER_CHECK),
                    setProviderCheck = ::setProviderCheck,
                    openProvider = ::openProvider,
                    restartBusy = restartBusy,
                    onRestartSystemUi = ::restartSystemUi,
                    openRepository = { openWeb(GitHubUpdates.REPOSITORY_URL) },
                    updateResult = updateResult,
                    checkForUpdates = { checkForUpdates(version) },
                    openReleases = { openWeb(GitHubUpdates.RELEASES_URL) },
                    copySupportGroup = ::copySupportGroup,
                    canExportDiagnostics = !diagnosticsBusy,
                    onExportDiagnostics = { exportDiagnostics.launch(DiagnosticReport.fileName()) },
                    canUseBackup = state.connection == Connection.READY && !backupBusy.value,
                    onExport = { export.launch("TerraMoor-settings.json") },
                    onImport = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (app.state.value.connection != Connection.CHECKING) app.refresh()
        refreshProviderStatuses()
    }

    private fun setProviderCheck(allowed: Boolean) {
        onboarding.edit().putBoolean(PROVIDER_CHECK, allowed).apply()
        refreshProviderStatuses()
    }

    private fun refreshProviderStatuses() {
        if (onboarding.getBoolean(PROVIDER_CHECK, false)) {
            providerStatuses = LyricsProvider.entries.associateWith { it.isInstalled(packageManager) }
        }
    }

    private fun openProvider(provider: LyricsProvider) {
        val launch = if (providerStatuses[provider] == true) packageManager.getLaunchIntentForPackage(provider.packageId) else null
        if (launch != null) {
            try {
                startActivity(launch)
                return
            } catch (_: ActivityNotFoundException) {
                refreshProviderStatuses()
            }
        }
        openWeb(provider.downloadPage)
    }

    private fun checkForUpdates(version: PackageInfo) {
        if (updateResult == GitHubUpdates.Result.Checking) return
        updateResult = GitHubUpdates.Result.Checking
        lifecycleScope.launch {
            updateResult = GitHubUpdates.check(version.longVersionCode, version.versionName.orEmpty())
        }
    }

    private fun copySupportGroup() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.support_group), SUPPORT_GROUP))
    }

    private fun openWeb(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            notify(R.string.open_link_failed)
        }
    }

    private fun saveDiagnostics(uri: Uri) {
        if (diagnosticsBusy) return
        diagnosticsBusy = true
        val state = app.state.value
        val providers = providerStatuses
        val service = app.diagnosticService()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val report = DiagnosticReport.collect(app, state, providers, service)
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
                        it.write(report)
                    }
                }
                notify(R.string.diagnostics_export_ok)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                app.diagnostics.failure("Export diagnostic report", error)
                notify(R.string.diagnostics_export_failed)
            } finally {
                diagnosticsBusy = false
            }
        }
    }

    private fun exportSettings(uri: Uri) {
        if (app.state.value.connection != Connection.READY) {
            notify(R.string.backup_unavailable)
            return
        }
        transferBackup(R.string.backup_export_ok) {
            val settings = checkNotNull(app.snapshotForBackup())
            withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(CapsuleBackup.encode(settings).toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun importSettings(uri: Uri) {
        transferBackup(R.string.backup_import_ok) {
            val decoded = withContext(Dispatchers.IO) {
                checkNotNull(contentResolver.openInputStream(uri)).use(CapsuleBackup::decode)
            }
            check(app.restore(decoded))
        }
    }

    private fun transferBackup(@StringRes success: Int, transfer: suspend () -> Unit) {
        if (backupBusy.value) return
        backupBusy.value = true
        lifecycleScope.launch {
            try {
                transfer()
                notify(success)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notify(R.string.backup_failed)
            } finally {
                backupBusy.value = false
            }
        }
    }

    private fun restartSystemUi() {
        if (restartBusy) return
        restartBusy = true
        lifecycleScope.launch {
            try {
                app.diagnostics.note("SystemUI restart requested")
                val result = SystemUiRestart.request()
                app.diagnostics.note("SystemUI restart result: $result")
                notify(when (result) {
                    SystemUiRestart.Result.SENT -> R.string.restart_system_ui_sent
                    SystemUiRestart.Result.ROOT_REQUIRED -> R.string.restart_system_ui_root_required
                    SystemUiRestart.Result.FAILED -> R.string.restart_system_ui_failed
                })
            } finally {
                restartBusy = false
            }
        }
    }

    private fun notify(@StringRes message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private companion object {
        const val PROVIDER_CHECK = "check_provider_installation"
    }
}

private const val SUPPORT_GROUP = "1102727864"

@Composable
private fun SettingsPage(
    state: TerraMoorApp.State,
    app: TerraMoorApp,
    version: String,
    providerStatuses: Map<LyricsProvider, Boolean>,
    askForProviderCheck: Boolean,
    setProviderCheck: (Boolean) -> Unit,
    openProvider: (LyricsProvider) -> Unit,
    openRepository: () -> Unit,
    updateResult: GitHubUpdates.Result,
    checkForUpdates: () -> Unit,
    openReleases: () -> Unit,
    copySupportGroup: () -> Unit,
    canUseBackup: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    canExportDiagnostics: Boolean,
    onExportDiagnostics: () -> Unit,
    restartBusy: Boolean,
    onRestartSystemUi: () -> Unit,
) {
    val tabs = SettingsTab.entries
    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val pageState = rememberSaveableStateHolder()
    val currentTab = tabs.getOrElse(pager.currentPage) { SettingsTab.HOME }
    var showRestartConfirmation by remember { mutableStateOf(false) }
    var showProviderPrompt by rememberSaveable { mutableStateOf(askForProviderCheck) }
    if (showProviderPrompt) {
        ProviderCheckDialog { allowed ->
            showProviderPrompt = false
            setProviderCheck(allowed)
        }
    }
    if (showRestartConfirmation) {
        val dark = isSystemInDarkTheme()
        val dismiss = { showRestartConfirmation = false }
        CouiDialog(
            onDismissRequest = dismiss,
            dark = dark,
            placement = CouiDialogPlacement.Center,
            title = stringResource(R.string.restart_system_ui),
            message = stringResource(R.string.restart_system_ui_confirmation),
            dismissAction = CouiDialogAction(stringResource(android.R.string.cancel), onClick = dismiss),
            confirmAction = CouiDialogAction(
                text = stringResource(R.string.restart_system_ui_confirm),
                enabled = !restartBusy,
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
                onClick = {
                    dismiss()
                    onRestartSystemUi()
                },
            ),
        )
    }
    fun selectTab(tab: SettingsTab) {
        scope.launch { pager.animateScrollToPage(tabs.indexOf(tab)) }
    }
    BackHandler(currentTab != SettingsTab.HOME) { selectTab(SettingsTab.HOME) }
    val background = CouiPageDefaults.themeColors().background
    FloatingScaffold(
        containerColor = background,
        topBar = {
            TopAppBarSurface(gradientColor = background, dark = isSystemInDarkTheme()) {
                SettingsTopBar(
                    titleVisibility = {
                        val offset = pager.currentPage - SettingsTab.TUNING.ordinal + pager.currentPageOffsetFraction
                        (1f - abs(offset)).coerceIn(0f, 1f)
                    },
                    showTitle = currentTab == SettingsTab.TUNING,
                    restartAction = {
                        RestartSystemUiButton(enabled = !restartBusy) { showRestartConfirmation = true }
                    },
                )
            }
        },
        bottomBar = { FloatingTabBar(currentTab, ::selectTab) },
    ) { padding ->
        val topPadding = remember(padding) {
            object : PaddingValues by padding {
                override fun calculateBottomPadding() = 0.dp
            }
        }
        // Read the scaffold's padding during measurement, after it measures the shared header.
        val content = Modifier.fillMaxSize().padding(topPadding).consumeWindowInsets(padding)
        val bottomClearance = padding.calculateBottomPadding()
        ProvideCouiMenuBackdrop(isolateContent = false) { backdrop ->
            HorizontalPager(
                state = pager,
                modifier = backdrop.then(content),
                beyondViewportPageCount = 1,
                key = { tabs[it].name },
            ) { page ->
                val tab = tabs[page]
                pageState.SaveableStateProvider(tab.name) {
                    when (tab) {
                        SettingsTab.HOME -> HomePage(
                            Modifier.fillMaxSize(), bottomClearance, state, app, providerStatuses, openProvider,
                            enableProviderCheck = { showProviderPrompt = true },
                        )
                        SettingsTab.TUNING -> {
                            val visible by remember(pager, page) {
                                derivedStateOf { abs(pager.currentPage - page + pager.currentPageOffsetFraction) < 1f }
                            }
                            TuningPage(Modifier.fillMaxSize(), bottomClearance, background, state, app, visible)
                        }
                        SettingsTab.ABOUT -> AboutPage(
                            Modifier.fillMaxSize(), bottomClearance, version, canUseBackup, onExport, onImport,
                            openRepository, updateResult, checkForUpdates, openReleases, copySupportGroup,
                            canExportDiagnostics, onExportDiagnostics,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCheckDialog(onDecision: (Boolean) -> Unit) {
    val dark = isSystemInDarkTheme()
    CouiDialog(
        onDismissRequest = { onDecision(false) },
        dark = dark,
        placement = CouiDialogPlacement.Center,
        title = stringResource(R.string.provider_check_enable),
        message = stringResource(R.string.provider_check_explanation),
        dismissAction = CouiDialogAction(stringResource(R.string.provider_check_later), onClick = { onDecision(false) }),
        confirmAction = CouiDialogAction(
            text = stringResource(R.string.provider_check_allow),
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(CouiTheme.colorSchemeFor(dark).accent),
            onClick = { onDecision(true) },
        ),
    )
}

@Composable
private fun installedColor() = if (isSystemInDarkTheme()) Color(0xFF56D687) else Color(0xFF15783D)

@Composable
private fun unavailableColor() = if (isSystemInDarkTheme()) Color(0xFFFF797D) else Color(0xFFB72232)

@Composable
private fun HomePage(
    modifier: Modifier,
    bottomClearance: Dp,
    state: TerraMoorApp.State,
    app: TerraMoorApp,
    providerStatuses: Map<LyricsProvider, Boolean>,
    openProvider: (LyricsProvider) -> Unit,
    enableProviderCheck: () -> Unit,
) {
    val (title, hint) = when (state.connection) {
        Connection.CHECKING -> R.string.status_checking to null
        Connection.DISCONNECTED -> R.string.status_disconnected to R.string.status_disconnected_hint
        Connection.NO_SCOPE -> R.string.status_no_scope to R.string.status_no_scope_hint
        Connection.READY -> R.string.status_ready to null
        Connection.FAILED -> R.string.status_failed to R.string.status_failed_hint
    }
    SettingsList(modifier, bottomClearance) {
        SettingsPageTitle(R.string.app_name)
        SettingsGroup(stringResource(R.string.section_connection)) {
            BasicComponent(
                title = stringResource(title),
                summary = hint?.let { stringResource(it) },
                endActions = if (state.connection != Connection.CHECKING) {
                    {
                        val ready = state.connection == Connection.READY
                        Icon(painterResource(if (ready) R.drawable.ic_status_check else R.drawable.ic_status_cross),
                            contentDescription = null, tint = if (ready) installedColor() else unavailableColor())
                    }
                } else null,
            )
        }
        SettingsGroup(stringResource(R.string.section_capsule)) {
            TactileSwitchPreference(
                checked = state.settings.enabled,
                onCheckedChange = { app.set(CapsuleSettings.ENABLED, it) },
                title = stringResource(R.string.enable_lyrics),
                enabled = state.connection == Connection.READY,
            )
        }
        SettingsGroup(stringResource(R.string.section_provider), notice = stringResource(R.string.provider_both_required)) {
            LyricsProvider.entries.forEachIndexed { index, provider ->
                if (index != 0) HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
                val installed = providerStatuses[provider]
                BasicComponent(
                    title = stringResource(provider.title),
                    onClick = { openProvider(provider) },
                    startAction = if (provider == LyricsProvider.BRIDGE) {
                        { Image(painterResource(R.drawable.provider_bridge), null, Modifier.size(32.dp)) }
                    } else null,
                    endActions = {
                        Text(
                            text = stringResource(when (installed) {
                                true -> R.string.provider_installed
                                false -> R.string.provider_download
                                null -> R.string.provider_not_checked
                            }),
                            color = if (installed == true) installedColor() else COUITheme.colorScheme.onSurfaceVariantSummary,
                            style = COUITheme.textStyles.body2,
                        )
                    },
                )
            }
            if (providerStatuses.isEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
                BasicComponent(title = stringResource(R.string.provider_check_enable), onClick = enableProviderCheck)
            }
        }
    }
}

@Composable
private fun AboutPage(
    modifier: Modifier,
    bottomClearance: Dp,
    version: String,
    canUseBackup: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    openRepository: () -> Unit,
    updateResult: GitHubUpdates.Result,
    checkForUpdates: () -> Unit,
    openReleases: () -> Unit,
    copySupportGroup: () -> Unit,
    canExportDiagnostics: Boolean,
    onExportDiagnostics: () -> Unit,
) {
    SettingsList(modifier, bottomClearance) {
        SettingsPageTitle(R.string.tab_about)
        SettingsGroup(stringResource(R.string.section_app_info)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), color = COUITheme.colorScheme.onSurface)
                SettingSummary(stringResource(R.string.app_version, version))
                SettingSummary(stringResource(R.string.app_compatibility))
            }
        }
        SettingsGroup(stringResource(R.string.section_support)) {
            BasicComponent(title = stringResource(R.string.open_repository), summary = GitHubUpdates.REPOSITORY, onClick = openRepository)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            UpdatePreference(updateResult, checkForUpdates, openReleases)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            SupportGroupPreference(copySupportGroup)
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            BasicComponent(
                title = stringResource(R.string.diagnostics_export),
                summary = stringResource(R.string.diagnostics_export_hint),
                enabled = canExportDiagnostics,
                onClick = onExportDiagnostics,
            )
        }
        SettingsGroup(stringResource(R.string.section_backup)) {
            BasicComponent(
                title = stringResource(R.string.backup_export),
                summary = stringResource(R.string.backup_export_hint),
                enabled = canUseBackup,
                onClick = onExport,
            )
            HorizontalDivider(Modifier.padding(horizontal = DividerDefaults.CardInset))
            BasicComponent(
                title = stringResource(R.string.backup_import),
                summary = stringResource(R.string.backup_import_hint),
                enabled = canUseBackup,
                onClick = onImport,
            )
        }
    }
}

@Composable
private fun SettingsTopBar(
    titleVisibility: () -> Float,
    showTitle: Boolean,
    restartAction: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
            .height(52.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.tab_tuning),
            modifier = Modifier.graphicsLayer { alpha = titleVisibility() }
                .then(if (showTitle) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {}),
            color = COUITheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(Modifier.align(Alignment.CenterEnd)) { restartAction() }
    }
}

@Composable
private fun SettingsPageTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        modifier = Modifier.semantics { heading() },
        color = COUITheme.colorScheme.onSurface,
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun UpdatePreference(result: GitHubUpdates.Result, onCheck: () -> Unit, onOpenReleases: () -> Unit) {
    val checking = result == GitHubUpdates.Result.Checking
    val hasNewVersion = result is GitHubUpdates.Result.Latest && result.comparison == GitHubUpdates.Comparison.NEWER
    val summary = when (result) {
        GitHubUpdates.Result.Idle -> null
        GitHubUpdates.Result.Checking -> stringResource(R.string.update_checking)
        GitHubUpdates.Result.NoRelease -> stringResource(R.string.update_no_release)
        GitHubUpdates.Result.Failed -> stringResource(R.string.update_check_failed)
        is GitHubUpdates.Result.Latest -> {
            val message = when (result.comparison) {
                GitHubUpdates.Comparison.NEWER -> R.string.update_available
                GitHubUpdates.Comparison.UP_TO_DATE -> R.string.update_up_to_date
                GitHubUpdates.Comparison.UNKNOWN -> R.string.update_latest_tag
            }
            stringResource(message, result.tag)
        }
    }
    BasicComponent(
        title = stringResource(R.string.update_title),
        summary = summary,
        endActions = {
            AboutActionButton(stringResource(R.string.check_updates), onCheck, enabled = !checking)
        },
    )
    if (hasNewVersion) {
        BasicComponent(title = stringResource(R.string.update_view_release), onClick = onOpenReleases)
    }
}

@Composable
private fun SupportGroupPreference(onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    BasicComponent(
        title = stringResource(R.string.support_group),
        summary = SUPPORT_GROUP,
        endActions = {
            AboutActionButton(stringResource(if (copied) R.string.support_group_copied else R.string.copy_support_group), {
                onCopy()
                copied = true
            })
        },
    )
}

@Composable
private fun AboutActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        minWidth = ButtonDefaults.MinWidthSmall,
        minHeight = ButtonDefaults.MinHeightSmall,
        cornerRadius = ButtonDefaults.CornerRadiusSmall,
        insideMargin = ButtonDefaults.InsideMarginSmall,
        textStyle = COUITheme.textStyles.button.copy(fontSize = 14.sp),
    )
}

@Composable
private fun RestartSystemUiButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = COUITheme.colorScheme
    val haptic = rememberCouiHaptic()
    TopAppBarButton(
        onClick = {
            haptic(CouiHapticEffect.Strength)
            onClick()
        },
        enabled = enabled,
        style = CouiTopAppBarButtonDefaults.style(dark = isSystemInDarkTheme()),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_restart_rounded),
            contentDescription = stringResource(R.string.restart_system_ui),
            tint = if (enabled) colors.onSurface else colors.disabledOnSecondaryVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
internal fun SettingsList(
    modifier: Modifier,
    bottomClearance: Dp,
    horizontalPadding: Dp = 16.dp,
    topPadding: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier.verticalScroll(rememberScrollState())
            .padding(start = horizontalPadding, end = horizontalPadding, top = topPadding, bottom = 12.dp + bottomClearance),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
internal fun SettingsGroup(title: String, notice: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingSummary(title)
            if (notice != null) Text(notice, color = unavailableColor(), style = COUITheme.textStyles.body2)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(color = CouiPageDefaults.themeColors().card),
        ) { content() }
    }
}

@Composable
internal fun SettingSummary(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = COUITheme.colorScheme.onSurfaceVariantSummary, style = COUITheme.textStyles.body2)
}
