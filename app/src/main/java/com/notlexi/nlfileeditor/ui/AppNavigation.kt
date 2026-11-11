package com.notlexi.nlfileeditor.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.notlexi.nlfileeditor.model.AppPhase
import com.notlexi.nlfileeditor.ui.screens.EditorScreen
import com.notlexi.nlfileeditor.ui.screens.ErrorScreen
import com.notlexi.nlfileeditor.ui.screens.HomeScreen
import com.notlexi.nlfileeditor.ui.screens.SuccessScreen
import com.notlexi.nlfileeditor.ui.screens.shareSavedFile
import com.notlexi.nlfileeditor.viewmodel.MainViewModel

private object Routes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val ERROR = "error"
    const val SUCCESS = "success"
}

/** Navigation graph. The ViewModel's [AppPhase] decides which screen is visible. */
@Composable
fun AppNavigation(vm: MainViewModel) {
    val nav = rememberNavController()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val dark by vm.darkTheme.collectAsStateWithLifecycle()

    // Keep the navigation stack in sync with the app phase.
    LaunchedEffect(phase) {
        val target = when (phase) {
            AppPhase.Home, AppPhase.Loading -> Routes.HOME
            AppPhase.Editor -> Routes.EDITOR
            is AppPhase.Saved -> Routes.SUCCESS
            is AppPhase.Error -> Routes.ERROR
        }
        if (nav.currentDestination?.route != target) {
            if (target == Routes.HOME) {
                nav.popBackStack(Routes.HOME, inclusive = false)
            } else {
                nav.navigate(target) {
                    launchSingleTop = true
                    popUpTo(Routes.HOME) { inclusive = false }
                }
            }
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(350)) + slideInHorizontally(tween(350)) { it / 8 } },
        exitTransition = { fadeOut(tween(250)) },
        popEnterTransition = { fadeIn(tween(350)) },
        popExitTransition = { fadeOut(tween(250)) + slideOutHorizontally(tween(300)) { it / 8 } }
    ) {
        composable(Routes.HOME) {
            // Android system file picker (Storage Access Framework), all file types.
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.onFilePicked(uri)
            }
            HomeScreen(
                isLoading = phase == AppPhase.Loading,
                darkTheme = dark,
                onToggleTheme = vm::toggleTheme,
                onUploadClick = { picker.launch(arrayOf("*/*")) }
            )
        }
        composable(Routes.EDITOR) {
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
}
