package com.mesh.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mesh.app.data.local.MeshDatabase
import com.mesh.app.data.prefs.UserPrefs
import com.mesh.app.data.repository.PlaylistRepository
import com.mesh.app.data.repository.TrackRepository
import com.mesh.app.library.MetadataReader
import com.mesh.app.library.TrackImporter
import com.mesh.app.player.PlayerController
import com.mesh.app.transfer.NearbyManager
import com.mesh.app.ui.auth.AuthViewModel
import com.mesh.app.ui.createplaylist.CreatePlaylistViewModel
import com.mesh.app.ui.downloaded.DownloadedViewModel
import com.mesh.app.ui.home.HomeViewModel
import com.mesh.app.ui.picktracks.PickTracksViewModel
import com.mesh.app.ui.playlist.PlaylistDetailViewModel
import com.mesh.app.ui.playlists.PlaylistsViewModel
import com.mesh.app.ui.receive.ReceiveViewModel
import com.mesh.app.ui.send.SendViewModel
import com.mesh.app.ui.splash.SplashViewModel
import com.mesh.app.ui.transfer.TransferViewModel

class MeshApplication : Application() {
    lateinit var database: MeshDatabase
        private set
    lateinit var userPrefs: UserPrefs
        private set
    lateinit var trackRepository: TrackRepository
        private set
    lateinit var playlistRepository: PlaylistRepository
        private set
    lateinit var playerController: PlayerController
        private set
    lateinit var nearbyManager: NearbyManager
        private set
    val createPlaylistDraft = CreatePlaylistDraft()

    private val metadataReader by lazy { MetadataReader(this) }
    private val trackImporter by lazy {
        TrackImporter(this, database.trackDao(), metadataReader)
    }

    override fun onCreate() {
        super.onCreate()
        database = MeshDatabase.create(this)
        userPrefs = UserPrefs(this)
        trackRepository = TrackRepository(database.trackDao(), trackImporter)
        playlistRepository = PlaylistRepository(database.playlistDao())
        playerController = PlayerController(this)
        nearbyManager = NearbyManager(this, trackRepository)
    }

    val viewModelFactory: ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                when {
                    modelClass.isAssignableFrom(SplashViewModel::class.java) ->
                        SplashViewModel(userPrefs) as T
                    modelClass.isAssignableFrom(AuthViewModel::class.java) ->
                        AuthViewModel(userPrefs) as T
                    modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                        HomeViewModel(trackRepository) as T
                    modelClass.isAssignableFrom(DownloadedViewModel::class.java) ->
                        DownloadedViewModel(
                            trackRepository,
                            playlistRepository,
                            playerController,
                        ) as T
                    modelClass.isAssignableFrom(PlaylistsViewModel::class.java) ->
                        PlaylistsViewModel(playlistRepository) as T
                    modelClass.isAssignableFrom(PlaylistDetailViewModel::class.java) ->
                        PlaylistDetailViewModel(
                            playlistRepository,
                            trackRepository,
                            playerController,
                        ) as T
                    modelClass.isAssignableFrom(CreatePlaylistViewModel::class.java) ->
                        CreatePlaylistViewModel(playlistRepository, createPlaylistDraft) as T
                    modelClass.isAssignableFrom(PickTracksViewModel::class.java) ->
                        PickTracksViewModel(
                            trackRepository,
                            playlistRepository,
                            createPlaylistDraft,
                        ) as T
                    modelClass.isAssignableFrom(SendViewModel::class.java) ->
                        SendViewModel(nearbyManager, userPrefs) as T
                    modelClass.isAssignableFrom(ReceiveViewModel::class.java) ->
                        ReceiveViewModel(nearbyManager, userPrefs) as T
                    modelClass.isAssignableFrom(TransferViewModel::class.java) ->
                        TransferViewModel(nearbyManager) as T
                    else -> error("Unknown ViewModel: ${modelClass.name}")
                }
        }
}
