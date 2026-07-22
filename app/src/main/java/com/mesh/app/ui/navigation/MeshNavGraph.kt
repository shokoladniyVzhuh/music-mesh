package com.mesh.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mesh.app.MeshApplication
import com.mesh.app.ui.auth.AuthScreen
import com.mesh.app.ui.createplaylist.CreatePlaylistScreen
import com.mesh.app.ui.downloaded.DownloadedScreen
import com.mesh.app.ui.home.HomeScreen
import com.mesh.app.ui.picktracks.PickTracksScreen
import com.mesh.app.ui.playlist.PlaylistDetailScreen
import com.mesh.app.ui.playlists.PlaylistsScreen
import com.mesh.app.ui.receive.ReceiveScreen
import com.mesh.app.ui.send.SendScreen
import com.mesh.app.ui.splash.SplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mesh.app.ui.createplaylist.CreatePlaylistViewModel
import com.mesh.app.ui.transfer.ReceiverPickTracksScreen
import com.mesh.app.ui.transfer.SenderWaitScreen
import com.mesh.app.ui.transfer.TransferProgressScreen

@Composable
fun MeshNavGraph(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as MeshApplication

    fun navigateHomeClearingTransfer() {
        navController.navigate(Routes.HOME) {
            popUpTo(Routes.HOME) { inclusive = false }
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier,
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onNavigateToAuth = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.AUTH) {
            AuthScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onPlaylists = { navController.navigate(Routes.PLAYLISTS) },
                onDownloaded = { navController.navigate(Routes.DOWNLOADED) },
                onSend = { navController.navigate(Routes.SEND) },
                onReceive = { navController.navigate(Routes.RECEIVE) },
            )
        }
        composable(Routes.DOWNLOADED) {
            DownloadedScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PLAYLISTS) {
            PlaylistsScreen(
                onBack = { navController.popBackStack() },
                onCreatePlaylist = { navController.navigate(Routes.CREATE_PLAYLIST) },
                onOpenPlaylist = { id ->
                    navController.navigate(Routes.playlistDetail(id))
                },
            )
        }
        composable(Routes.CREATE_PLAYLIST) {
            val createVm: CreatePlaylistViewModel = viewModel(factory = app.viewModelFactory)
            CreatePlaylistScreen(
                onBack = {
                    createVm.clearDraft()
                    navController.popBackStack()
                },
                onAddTracks = {
                    navController.navigate(Routes.pickTracks(PickTracksMode.CREATE))
                },
                onDone = { playlistId ->
                    navController.navigate(Routes.playlistDetail(playlistId)) {
                        popUpTo(Routes.PLAYLISTS)
                    }
                },
            )
        }
        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
        ) { entry ->
            val playlistId = entry.arguments?.getString("playlistId") ?: return@composable
            PlaylistDetailScreen(
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onAddTracks = { id ->
                    navController.navigate(Routes.pickTracks(PickTracksMode.ADD_TO_PLAYLIST, id))
                },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.PICK_TRACKS,
            arguments = listOf(
                navArgument("mode") { type = NavType.StringType },
                navArgument("playlistId") { type = NavType.StringType },
            ),
        ) { entry ->
            val mode = PickTracksMode.valueOf(entry.arguments?.getString("mode") ?: "CREATE")
            val playlistIdArg = entry.arguments?.getString("playlistId")
            val playlistId = playlistIdArg?.takeIf { it != "none" }
            PickTracksScreen(
                mode = mode,
                playlistId = playlistId,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(Routes.SEND) {
            SendScreen(
                onBack = { navController.popBackStack() },
                onConnected = {
                    navController.navigate(Routes.SENDER_WAIT) {
                        popUpTo(Routes.SEND) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(
                onBack = { navController.popBackStack() },
                onConnected = {
                    navController.navigate(Routes.RECEIVER_PICK) {
                        popUpTo(Routes.RECEIVE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.SENDER_WAIT) {
            SenderWaitScreen(
                onTransferring = {
                    navController.navigate(Routes.TRANSFER_PROGRESS) {
                        popUpTo(Routes.SENDER_WAIT) { inclusive = true }
                    }
                },
                onFinished = {
                    navController.navigate(Routes.TRANSFER_PROGRESS) {
                        popUpTo(Routes.SENDER_WAIT) { inclusive = true }
                    }
                },
                onFailedOrClosed = { navigateHomeClearingTransfer() },
            )
        }
        composable(Routes.RECEIVER_PICK) {
            ReceiverPickTracksScreen(
                onTransferring = {
                    navController.navigate(Routes.TRANSFER_PROGRESS) {
                        popUpTo(Routes.RECEIVER_PICK) { inclusive = true }
                    }
                },
                onFinished = {
                    navController.navigate(Routes.TRANSFER_PROGRESS) {
                        popUpTo(Routes.RECEIVER_PICK) { inclusive = true }
                    }
                },
                onFailedOrClosed = { navigateHomeClearingTransfer() },
            )
        }
        composable(Routes.TRANSFER_PROGRESS) {
            TransferProgressScreen(
                onContinueListening = { navigateHomeClearingTransfer() },
            )
        }
    }
}
