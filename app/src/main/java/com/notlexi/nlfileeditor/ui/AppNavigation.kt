package com.notlexi.nlfileeditor.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notlexi.nlfileeditor.model.AppPhase
import com.notlexi.nlfileeditor.ui.screens.AppDataBlockedScreen
import com.notlexi.nlfileeditor.ui.screens.AppFilesScreen
import com.notlexi.nlfileeditor.ui.screens.EditorScreen
import com.notlexi.nlfileeditor.ui.screens.ErrorScreen
import com.notlexi.nlfileeditor.ui.screens.HomeScreen
import com.notlexi.nlfileeditor.ui.screens.HtmlViewerScreen
import com.notlexi.nlfileeditor.ui.screens.InstalledAppsScreen
import com.notlexi.nlfileeditor.ui.screens.SuccessScreen
import com.notlexi.nlfileeditor.ui.screens.TmxFullScreen
import com.notlexi.nlfileeditor.ui.screens.TmxHomeScreen
import com.notlexi.nlfileeditor.ui.screens.TmxLightScreen
import com.notlexi.nlfileeditor.ui.screens.WorkspaceScreen
import com.notlexi.nlfileeditor.game.GameDetector
import com.notlexi.nlfileeditor.util.PickGameFolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.notlexi.nlfileeditor.ui.screens.shareSavedFile
import com.notlexi.nlfileeditor.util.PickAppDataFolder
import com.notlexi.nlfileeditor.viewmodel.MainViewModel

private object Routes {
    const val HOME = "home"
    const val APP_LIST = "app_list"
    const val APP_FILES = "app_files"
    const val APP_BLOCKED = "app_blocked"
    const val HTML = "html_viewer"
    const val WORKSPACE = "workspace"
    const val TMX_HOME = "tmx_home"
    const val TMX_LIGHT = "tmx_light"
    const val TMX_FULL = "tmx_full"
    const val EDITOR = "editor"
    const val ERROR = "error"
    const val SUCCESS = "success"
}

private fun routeFor(phase: AppPhase): String = when (phase) {
    AppPhase.Home -> Routes.HOME
    AppPhase.AppList -> Routes.APP_LIST
    AppPhase.AppFiles -> Routes.APP_FILES
    is AppPhase.AppDataBlocked -> Routes.APP_BLOCKED
    AppPhase.HtmlViewer -> Routes.HTML
    AppPhase.Workspace -> Routes.WORKSPACE
    AppPhase.TmxHome -> Routes.TMX_HOME
    AppPhase.TmxLight -> Routes.TMX_LIGHT
    AppPhase.TmxFull -> Routes.TMX_FULL
    AppPhase.Editor -> Routes.EDITOR
    is AppPhase.Saved -> Routes.SUCCESS
    is AppPhase.Error -> Routes.ERROR
}

/** True if [route] is somewhere on the navigation back stack. */
private fun NavHostController.isOnStack(route: String): Boolean =
    try {
        getBackStackEntry(route)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

/**
 * Navigation graph. The ViewModel's [AppPhase] (and its back stack) decides which screen is shown;
 * this keeps the NavController in step with it:
 *  - a screen that is already on the stack is returned to by popping everything above it,
 *    so going Back never rebuilds the screen below and never skips one;
 *  - any other screen is pushed.
 * Home -> Browse Apps -> files -> editor is therefore a genuine stack.
 */
@Composable
fun AppNavigation(vm: MainViewModel) {
    val nav = rememberNavController()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val gameState by vm.game.state.collectAsStateWithLifecycle()
    val dark by vm.darkTheme.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Game folder picker: lives above the NavHost so any screen (or the automatic prompt) can use it.
    val folderPicker = rememberLauncherForActivityResult(PickGameFolder()) { uri -> vm.game.onPicked(uri) }
    LaunchedEffect(Unit) {
        vm.game.pickRequests.collect { relative ->
            try {
                folderPicker.launch(relative)
            } catch (e: ActivityNotFoundException) {
                vm.game.onPicked(null)
            }
        }
    }
    // Re-check for a running game whenever the user comes back to the app (e.g. after playing).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.game.refresh() }

    LaunchedEffect(phase) {
        val target = routeFor(phase)
        if (nav.currentDestination?.route == target) return@LaunchedEffect
        if (nav.isOnStack(target)) {
            nav.popBackStack(target, inclusive = false)
        } else {
            nav.navigate(target) { launchSingleTop = true }
        }
    }

    // Keeps the overlay text during its fade-out, after `busy` has already become null.
    var lastBusyText by remember { mutableStateOf("") }
    LaunchedEffect(busy) { busy?.let { lastBusyText = it } }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it / 10 } },
            exitTransition = { fadeOut(tween(160)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 10 } }
        ) {
            composable(Routes.HOME) {
                // Android system file picker (Storage Access Framework), all file types.
                // `pickerOpen` stops a second tap from stacking a second picker on top of the first.
                var pickerOpen by remember { mutableStateOf(false) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    pickerOpen = false
                    if (uri != null) vm.onFilePicked(uri)
                }
                HomeScreen(
                    darkTheme = dark,
                    onToggleTheme = vm::toggleTheme,
                    onUploadClick = {
                        if (!pickerOpen && busy == null) {
                            pickerOpen = true
                            try {
                                picker.launch(arrayOf("*/*"))
                            } catch (e: ActivityNotFoundException) {
                                pickerOpen = false
                                vm.onBrowseUnavailable()
                            }
                        }
                    },
                    onBrowseAppsClick = vm::openBrowseApps,
                    onHtmlViewerClick = vm::openHtmlViewer,
                    onSearchClick = { tab ->
                        if (vm.game.state.value.folder != null) vm.openWorkspace(tab)
                        else vm.game.requestPick("Android/data", null)
                    },
                    onTmxClick = vm::openTmx,
                    gameState = gameState,
                    onGrantUsage = {
                        try { context.startActivity(GameDetector.usageAccessIntent()) } catch (e: ActivityNotFoundException) { }
                    },
                    onChooseFolder = {
                        val pkg = gameState.detected?.packageName
                        vm.game.requestPick(if (pkg != null) "Android/data/$pkg" else "Android/data", pkg)
                    },
                    onUseApp = vm.game::useApp,
                    onForgetFolder = vm.game::forgetFolder,
                    onDismissNotice = vm.game::dismissNotice
                )
            }
            composable(Routes.APP_LIST) {
                val browse by vm.browse.collectAsStateWithLifecycle()
                // Android system folder picker, opened at Android/data/<package> when the device allows it.
                val folderPicker = rememberLauncherForActivityResult(PickAppDataFolder()) { uri ->
                    if (uri != null) vm.onAppDataFolderPicked(uri)
                }
                InstalledAppsScreen(
                    apps = browse.apps,
                    onPick = { app ->
                        vm.selectApp(app)
                        try {
                            folderPicker.launch(app.packageName)
                        } catch (e: ActivityNotFoundException) {
                            vm.onBrowseUnavailable()
                        }
                    },
                    onBack = vm::back // -> Home
                )
            }
            composable(Routes.APP_FILES) {
                val browse by vm.browse.collectAsStateWithLifecycle()
                AppFilesScreen(
                    appLabel = browse.selectedApp?.label ?: "this app",
                    candidates = browse.candidates,
                    onPick = vm::onCandidateChosen,
                    onBack = vm::back // -> installed apps list
                )
            }
            composable(Routes.APP_BLOCKED) {
                val blocked = phase as? AppPhase.AppDataBlocked
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.onFilePicked(uri)
                }
                AppDataBlockedScreen(
                    title = blocked?.title ?: "Can't open this folder",
                    message = blocked?.message ?: "",
                    onUploadInstead = { picker.launch(arrayOf("*/*")) },
                    onBack = vm::back
                )
            }
            composable(Routes.WORKSPACE) { WorkspaceScreen(vm) }
            composable(Routes.TMX_HOME) { TmxHomeScreen(vm) }
            composable(Routes.TMX_LIGHT) { TmxLightScreen(vm) }
            composable(Routes.TMX_FULL) { TmxFullScreen(vm) }
            composable(Routes.HTML) {
                HtmlViewerScreen(vm)
            }
            composable(Routes.EDITOR) {
                // Its own Back handling: returns to the file list the file was opened from (or Home).
                EditorScreen(vm)
            }
            composable(Routes.ERROR) {
                val error = phase as? AppPhase.Error
                BackHandler { vm.dismissError() }
                ErrorScreen(
                    title = error?.title ?: "Something went wrong",
                    message = error?.message ?: "",
                    onTryAgain = vm::dismissError
                )
            }
            composable(Routes.SUCCESS) {
                val saved = phase as? AppPhase.Saved
                BackHandler { vm.keepEditing() }
                SuccessScreen(
                    savedName = saved?.savedName ?: "",
                    onShare = { context -> shareSavedFile(context, vm.savedUri, vm.mimeType) },
                    onKeepEditing = vm::keepEditing,
                    onDone = vm::closeFile
                )
            }
        }

        // Working overlay above every screen (reading a file, scanning an app's folder...).
        AnimatedVisibility(visible = busy != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            BackHandler { } // no leaving a screen while it is still working
            BusyOverlay(text = busy ?: lastBusyText)
        }
    }
}
