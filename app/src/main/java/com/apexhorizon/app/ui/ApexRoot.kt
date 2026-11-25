package com.apexhorizon.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apexhorizon.app.ui.screens.AboutScreen
import com.apexhorizon.app.ui.screens.DriveScreen
import com.apexhorizon.app.ui.screens.HistoryScreen
import com.apexhorizon.app.ui.screens.HomeScreen
import com.apexhorizon.app.ui.screens.MapsScreen
import com.apexhorizon.app.ui.screens.PlanScreen
import com.apexhorizon.app.ui.screens.SettingsScreen
import com.apexhorizon.app.ui.screens.SummaryScreen
import com.apexhorizon.app.ui.theme.Apex

@Composable
fun ApexRoot(vm: AppViewModel) {
    val current = vm.stack.last()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Android back button / gesture: pop the stack. On Home the handler is disabled, so the system
    // closes the app. While driving it opens the "End drive?" dialog instead of closing the screen.
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Home -> HomeScreen(vm)
                Screen.Drive -> DriveScreen(vm)
                Screen.Summary -> SummaryScreen(vm)
                Screen.History -> HistoryScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
                Screen.About -> AboutScreen(vm)
                Screen.Plan -> PlanScreen(vm)
                Screen.Maps -> MapsScreen(vm)
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding(),
        ) { data -> Snackbar(data, containerColor = Apex.SurfaceHigh, contentColor = Apex.TextPrimary) }
    }
}
