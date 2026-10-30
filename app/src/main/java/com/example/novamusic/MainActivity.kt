package com.example.novamusic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.app.Dialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import org.json.JSONArray
import org.json.JSONObject
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

data class SongInfo(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val albumId: Long
)

class MainActivity : AppCompatActivity() {

    private val songs = mutableListOf<SongInfo>()
    private lateinit var player: MediaController
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // Album artwork loads away from the UI thread; cache helps scrolling stay smooth.
    private val artworkExecutor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val artworkCache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(1024)
    ) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    // References used by the full-screen Now Playing panel.
    private var nowPlayingDialog: Dialog? = null
    private var nowPlayingTitle: TextView? = null
    private var nowPlayingArtist: TextView? = null
    private var nowPlayingCover: ImageView? = null
    private var nowPlayingPlaceholder: TextView? = null
    private var nowPlayingSeekBar: SeekBar? = null
    private var nowPlayingPosition: TextView? = null
    private var nowPlayingDuration: TextView? = null
    private var nowPlayingPlayButton: TextView? = null
    private var nowPlayingShuffleButton: TextView? = null
    private var nowPlayingRepeatButton: TextView? = null
    private var nowPlayingSkinView: NovaPlayerSkinView? = null
    private var userIsSeeking = false
    private var pendingSearch: Runnable? = null
    private var favoritesOnly = false
    private var selectedPlaylist: String? = null
    private var activeQueue: List<SongInfo> = emptyList()

    // Premium sleep-timer sheet state.
    private var nowPlayingSleepTimerButton: TextView? = null
    private var equalizerDialog: Dialog? = null
    private var settingsDialog: Dialog? = null
    private var playlistsDialog: Dialog? = null
    private var selectedPlayerSkin = 0
    private var accentColor = Color.rgb(169, 137, 255)
    private var pendingDeleteSong: SongInfo? = null
    private var pendingDeleteRequiresRetry = false
    private var selectedNavigation = "home"
    private var selectedTopCategory = "all"
    private var sleepTimerDialog: Dialog? = null
    private var sleepTimerStatusTitle: TextView? = null
    private var sleepTimerStatusDetail: TextView? = null
    private var sleepTimerSelectedCaption: TextView? = null
    private var sleepTimerCancelButton: TextView? = null
    private val sleepTimerPresetButtons = linkedMapOf<Int, TextView>()

    private val sleepTimerPreferences by lazy {
        getSharedPreferences(SleepTimerCommands.PREFERENCES_NAME, MODE_PRIVATE)
    }

    private val playlistPreferences by lazy {
        getSharedPreferences("nova_music_playlists", MODE_PRIVATE)
    }

    private val favoritesPreferences by lazy {
        getSharedPreferences("nova_music_preferences", MODE_PRIVATE)
    }

    private val appearancePreferences by lazy {
        getSharedPreferences("nova_appearance_preferences", MODE_PRIVATE)
    }

    private val deleteSongLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = pendingDeleteSong
        pendingDeleteSong = null
        if (result.resultCode == Activity.RESULT_OK && song != null) {
            if (pendingDeleteRequiresRetry) {
                pendingDeleteRequiresRetry = false
                deleteSongFromStorage(song)
            } else {
                finishSongDeletion(song)
            }
        } else if (song != null) {
            pendingDeleteRequiresRetry = false
            Toast.makeText(this, "Song was not deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private val legacyWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val song = pendingDeleteSong
        if (granted && song != null) {
            deleteSongFromStorage(song)
        } else {
            pendingDeleteSong = null
            Toast.makeText(this, "Storage permission was not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSongs()
        } else {
            Toast.makeText(
                this,
                "Music permission is required to scan your songs.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val seekUpdateRunnable = object : Runnable {
        override fun run() {
            val dialog = nowPlayingDialog
            val seekBar = nowPlayingSeekBar
            if (dialog?.isShowing != true || seekBar == null) return

            val duration = getCurrentDuration()
            val position = player.currentPosition.coerceAtLeast(0L)

            if (!userIsSeeking) {
                seekBar.progress = if (duration > 0L) {
                    ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                } else {
                    0
                }
            }

            nowPlayingPosition?.text = formatDuration(position)
            nowPlayingDuration?.text = formatDuration(duration)
            updateSleepTimerHeaderButton()
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        accentColor = appearancePreferences.getInt("accent_color", Color.rgb(169, 137, 255))
        selectedPlayerSkin = appearancePreferences.getInt("player_skin_id", 0).coerceIn(0, 4)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        connectToPlaybackService()

        findViewById<TextView>(R.id.miniPlayerPlayButton).setOnClickListener {
            togglePlayback()
        }

        // Tap the mini-player body to open the full-screen player.
        findViewById<LinearLayout>(R.id.miniPlayer).setOnClickListener {
            showNowPlaying()
        }

        // Search songs by title or artist. Debouncing avoids rebuilding the list on every keystroke.
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                pendingSearch?.let { mainHandler.removeCallbacks(it) }
                val query = s?.toString().orEmpty()
                val task = Runnable { renderSongs(query) }
                pendingSearch = task
                mainHandler.postDelayed(task, 180L)
            }
        })

        setupPremiumNavigation()
        checkMusicPermission()
    }

    private fun connectToPlaybackService() {
        val token = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future

        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                player = future.get()
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updatePlayButton(isPlaying)
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        songForMediaItem(mediaItem, player.currentMediaItemIndex)?.let { song ->
                            updateMiniPlayer(song)
                            updateNowPlayingSong(song)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateNowPlayingControls()
                    }
                })

                if (player.mediaItemCount > 0) {
                    songs.getOrNull(player.currentMediaItemIndex)?.let { updateMiniPlayer(it) }
                }
                updatePlayButton(player.isPlaying)
            } catch (e: Exception) {
                Log.e("NovaMusic", "Unable to connect to playback service", e)
                Toast.makeText(
                    this,
                    "Music player is not ready. Please reopen the app.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkMusicPermission() {
        if (ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            permissionLauncher.launch(audioPermission)
        }
    }

    private fun loadSongs() {
        songs.clear()
        val musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            contentResolver.query(musicUri, projection, selection, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                        ?.takeIf { it.isNotBlank() } ?: "Unknown title"
                    val artist = cursor.getString(artistColumn)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown artist"
                    val duration = cursor.getLong(durationColumn)
                    val albumId = cursor.getLong(albumIdColumn)
                    songs.add(SongInfo(id, title, artist, duration, albumId))
                }
            }

            val currentQuery = findViewById<EditText>(R.id.etSearch).text?.toString().orEmpty()
            renderSongs(currentQuery)
            if (::player.isInitialized && player.mediaItemCount > 0) {
                songForMediaItem(player.currentMediaItem, player.currentMediaItemIndex)?.let {
                    updateMiniPlayer(it)
                }
            }
            Toast.makeText(this, "${songs.size} songs loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("NovaMusic", "Unable to load songs", e)
            Toast.makeText(this, "Unable to load music", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderSongs(query: String = "") {
        val container = findViewById<LinearLayout>(R.id.songContainer)
        container.removeAllViews()

        val favoriteIds = getFavoriteIds()
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
        }

        filterRow.addView(
            createLibraryFilterChip(
                label = "Songs",
                selected = !favoritesOnly && selectedPlaylist == null
            ) {
                favoritesOnly = false
                selectedPlaylist = null
                selectedTopCategory = "all"
                updateTopCategoryStyle()
                renderSongs(currentSearchQuery())
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) }
        )

        filterRow.addView(
            createLibraryFilterChip(
                label = "Favorites",
                selected = favoritesOnly
            ) {
                favoritesOnly = true
                selectedPlaylist = null
                selectedTopCategory = "favorites"
                updateTopCategoryStyle()
                renderSongs(currentSearchQuery())
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )

        filterRow.addView(
            createLibraryFilterChip(
                label = if (selectedPlaylist == null) "Playlists" else "Playlist ✓",
                selected = selectedPlaylist != null
            ) {
                showPlaylistsDialog()
            },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) }
        )
        container.addView(filterRow)

        val normalizedQuery = query.trim()
        val playlistSongIds = selectedPlaylist?.let { getPlaylistSongIds(it) }.orEmpty()
        val visibleSongs = songs.filter { song ->
            val matchesQuery = normalizedQuery.isEmpty() ||
                song.title.contains(normalizedQuery, ignoreCase = true) ||
                song.artist.contains(normalizedQuery, ignoreCase = true)
            val matchesCollection = when {
                selectedPlaylist != null -> song.id.toString() in playlistSongIds
                favoritesOnly -> song.id.toString() in favoriteIds
                else -> true
            }
            matchesQuery && matchesCollection
        }

        if (visibleSongs.isEmpty()) {
            val isSearchMiss = normalizedQuery.isNotBlank() && songs.isNotEmpty()
            val emptyState = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(34), dp(20), dp(34))
                background = roundedBackground(Color.rgb(19, 22, 38), 22, Color.rgb(43, 45, 70))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            emptyState.addView(TextView(this).apply {
                text = if (isSearchMiss) "⌕" else "♫"
                textSize = 42f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
            })
            emptyState.addView(TextView(this).apply {
                text = when {
                    isSearchMiss -> "NOT FOUND"
                    songs.isEmpty() -> "NO MUSIC FOUND"
                    selectedPlaylist != null && playlistSongIds.isEmpty() -> "EMPTY PLAYLIST"
                    selectedPlaylist != null -> "NO MATCHES IN THIS PLAYLIST"
                    favoritesOnly && favoriteIds.isEmpty() -> "NO FAVORITES YET"
                    favoritesOnly -> "NO MATCHING FAVORITES"
                    else -> "NOT FOUND"
                }
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = 0.08f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(5))
            })
            emptyState.addView(TextView(this).apply {
                text = when {
                    isSearchMiss -> "No song or artist matches “$normalizedQuery”. Try another search."
                    songs.isEmpty() -> "Add music to your device, then refresh your library."
                    selectedPlaylist != null && playlistSongIds.isEmpty() -> "Add songs to this playlist to see them here."
                    selectedPlaylist != null -> "Try a different song or artist name."
                    favoritesOnly -> "Tap the heart beside a song to save it here."
                    else -> "Try another search or refresh your library."
                }
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(153, 160, 183))
                setPadding(0, 0, 0, dp(14))
            })
            if (isSearchMiss) {
                val clearButton = TextView(this).apply {
                    text = "CLEAR SEARCH"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = accentButtonBackground()
                    setPadding(dp(18), dp(11), dp(18), dp(11))
                    setOnClickListener { findViewById<EditText>(R.id.etSearch).setText("") }
                }
                emptyState.addView(clearButton)
            }
            container.addView(emptyState)
            return
        }

        val artColors = intArrayOf(
            Color.rgb(68, 55, 118),
            Color.rgb(39, 86, 92),
            Color.rgb(101, 64, 47),
            Color.rgb(52, 70, 110)
        )
        val musicSymbols = arrayOf("♫", "♪", "♬", "♫")

        visibleSongs.forEachIndexed { index, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBackground(
                    Color.rgb(23, 28, 43), 18, Color.rgb(37, 43, 61)
                )
                setPadding(dp(10), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = "${song.title}, ${song.artist}. Tap to play."
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(76)
                ).apply { bottomMargin = dp(10) }
            }

            val coverHolder = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
                background = roundedBackground(artColors[index % artColors.size], 12)
                clipToOutline = true
            }

            val placeholder = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                text = musicSymbols[index % musicSymbols.size]
                textSize = 28f
                setTextColor(Color.rgb(221, 213, 255))
            }
            coverHolder.addView(placeholder)

            val artworkView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
                tag = song.id
                clipToOutline = true
            }
            coverHolder.addView(artworkView)
            loadAlbumArtwork(song, artworkView, placeholder)
            row.addView(coverHolder)

            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = dp(12) }
            }

            val titleView = TextView(this).apply {
                text = song.title
                textSize = 14f
                isSingleLine = true
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

            val artistView = TextView(this).apply {
                text = song.artist
                textSize = 12f
                isSingleLine = true
                setTextColor(Color.rgb(146, 152, 170))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            }

            details.addView(titleView)
            details.addView(artistView)
            row.addView(details)

            val durationView = TextView(this).apply {
                text = formatDuration(song.duration)
                textSize = 11f
                setTextColor(Color.rgb(146, 152, 170))
            }
            row.addView(durationView)

            val playlistButton = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(44)).apply {
                    marginStart = dp(2)
                }
                gravity = Gravity.CENTER
                text = if (selectedPlaylist != null) "−" else "+"
                textSize = 23f
                setTextColor(Color.rgb(178, 160, 255))
                isClickable = true
                isFocusable = true
                contentDescription = if (selectedPlaylist != null) {
                    "Remove from this playlist"
                } else {
                    "Add to playlist"
                }
                setOnClickListener {
                    val currentPlaylist = selectedPlaylist
                    if (currentPlaylist != null) {
                        removeSongFromPlaylist(song, currentPlaylist)
                    } else {
                        showAddToPlaylistDialog(song)
                    }
                }
            }
            row.addView(playlistButton)

            val isFavorite = song.id.toString() in favoriteIds
            val favoriteButton = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(46)).apply {
                    marginStart = dp(3)
                }
                gravity = Gravity.CENTER
                text = if (isFavorite) "♥" else "♡"
                textSize = 23f
                setTextColor(if (isFavorite) Color.rgb(255, 105, 145) else Color.rgb(151, 157, 178))
                isClickable = true
                isFocusable = true
                contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites"
                setOnClickListener { toggleFavorite(song) }
            }
            row.addView(favoriteButton)

            val moreButton = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(46)).apply {
                    marginStart = dp(1)
                }
                gravity = Gravity.CENTER
                text = "⋮"
                textSize = 22f
                setTextColor(Color.rgb(170, 174, 195))
                isClickable = true
                isFocusable = true
                contentDescription = "More options for ${song.title}"
                setOnClickListener { showSongOptionsDialog(song) }
            }
            row.addView(moreButton)

            row.setOnClickListener { playSong(song, openPlayer = true) }
            container.addView(row)
        }
    }

    private fun createLibraryFilterChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (selected) Color.WHITE else Color.rgb(194, 198, 215))
            background = if (selected) {
                roundedBackground(Color.rgb(103, 79, 204), 20, Color.rgb(144, 124, 240))
            } else {
                roundedBackground(Color.rgb(23, 28, 43), 20, Color.rgb(43, 48, 67))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun getFavoriteIds(): MutableSet<String> {
        return favoritesPreferences.getStringSet("favorite_song_ids", mutableSetOf<String>())
            ?.toMutableSet() ?: mutableSetOf()
    }

    private fun toggleFavorite(song: SongInfo) {
        val favoriteIds = getFavoriteIds()
        val songId = song.id.toString()
        val added: Boolean

        if (songId in favoriteIds) {
            favoriteIds.remove(songId)
            added = false
        } else {
            favoriteIds.add(songId)
            added = true
        }

        favoritesPreferences.edit()
            .putStringSet("favorite_song_ids", favoriteIds)
            .apply()

        Toast.makeText(
            this,
            if (added) "Added to favorites" else "Removed from favorites",
            Toast.LENGTH_SHORT
        ).show()

        val query = findViewById<EditText>(R.id.etSearch).text?.toString().orEmpty()
        renderSongs(query)
    }

    private fun currentSearchQuery(): String =
        findViewById<EditText>(R.id.etSearch).text?.toString().orEmpty()

    private fun readPlaylists(): LinkedHashMap<String, MutableSet<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        val saved = playlistPreferences.getString("custom_playlists_json", "{}") ?: "{}"
        try {
            val root = JSONObject(saved)
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val array = root.optJSONArray(name) ?: JSONArray()
                val ids = linkedSetOf<String>()
                for (i in 0 until array.length()) {
                    val id = array.optString(i)
                    if (id.isNotBlank()) ids.add(id)
                }
                result[name] = ids
            }
        } catch (e: Exception) {
            Log.e("NovaMusic", "Could not read saved playlists", e)
        }
        return result
    }

    private fun savePlaylists(playlists: Map<String, Set<String>>) {
        val root = JSONObject()
        try {
            playlists.forEach { (name, ids) ->
                val array = JSONArray()
                ids.forEach { array.put(it) }
                root.put(name, array)
            }
            playlistPreferences.edit()
                .putString("custom_playlists_json", root.toString())
                .apply()
        } catch (e: Exception) {
            Log.e("NovaMusic", "Could not save playlists", e)
            Toast.makeText(this, "Could not save playlist", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPlaylistSongIds(name: String): Set<String> =
        readPlaylists()[name]?.toSet() ?: emptySet()

    // ---------------------------------------------------------
    // PREMIUM NAVIGATION AND SETTINGS
    // ---------------------------------------------------------

    private fun setupPremiumNavigation() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            navigateToHome()
        }
        findViewById<LinearLayout>(R.id.navLibrary).setOnClickListener {
            navigateToLibrary()
        }
        findViewById<LinearLayout>(R.id.navPlaylists).setOnClickListener {
            navigateToPlaylists()
        }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener {
            selectedNavigation = "settings"
            updateBottomNavigationStyle()
            showSettingsDialog()
        }

        findViewById<TextView>(R.id.btnHeaderSettings).setOnClickListener {
            selectedNavigation = "settings"
            updateBottomNavigationStyle()
            showSettingsDialog()
        }

        findViewById<TextView>(R.id.btnPlayMix).setOnClickListener {
            playRandomMix()
        }

        findViewById<TextView>(R.id.chipAllSongs).setOnClickListener {
            favoritesOnly = false
            selectedPlaylist = null
            selectedTopCategory = "all"
            updateTopCategoryStyle()
            renderSongs(currentSearchQuery())
        }

        findViewById<TextView>(R.id.chipFavorites).setOnClickListener {
            favoritesOnly = true
            selectedPlaylist = null
            selectedTopCategory = "favorites"
            selectedNavigation = "library"
            showHomeSections(false)
            updateTopCategoryStyle()
            updateBottomNavigationStyle()
            renderSongs(currentSearchQuery())
            findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
        }

        findViewById<TextView>(R.id.chipPlaylists).setOnClickListener {
            navigateToPlaylists()
        }

        applyAccentColor()
    }

    private fun navigateToHome() {
        selectedNavigation = "home"
        favoritesOnly = false
        selectedPlaylist = null
        selectedTopCategory = "all"
        showHomeSections(true)
        updateBottomNavigationStyle()
        updateTopCategoryStyle()
        renderSongs(currentSearchQuery())
        findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
    }

    private fun navigateToLibrary() {
        selectedNavigation = "library"
        favoritesOnly = false
        selectedPlaylist = null
        selectedTopCategory = "all"
        showHomeSections(false)
        updateBottomNavigationStyle()
        updateTopCategoryStyle()
        renderSongs(currentSearchQuery())
        findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
    }

    private fun navigateToPlaylists() {
        selectedNavigation = "playlists"
        selectedTopCategory = "playlists"
        showHomeSections(false)
        updateTopCategoryStyle()
        updateBottomNavigationStyle()
        findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0, 0)
        showPlaylistsDialog()
    }

    private fun showHomeSections(show: Boolean) {
        findViewById<TextView>(R.id.heroTitle).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.heroSubtitle).visibility = if (show) View.VISIBLE else View.GONE
        findViewById<LinearLayout>(R.id.featuredCard).visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun updateBottomNavigationStyle() {
        val tabs = listOf(
            Triple("home", R.id.navHome, Pair(R.id.navHomeIcon, R.id.navHomeLabel)),
            Triple("library", R.id.navLibrary, Pair(R.id.navLibraryIcon, R.id.navLibraryLabel)),
            Triple("playlists", R.id.navPlaylists, Pair(R.id.navPlaylistsIcon, R.id.navPlaylistsLabel)),
            Triple("settings", R.id.navSettings, Pair(R.id.navSettingsIcon, R.id.navSettingsLabel))
        )

        tabs.forEach { tab ->
            val selected = selectedNavigation == tab.first
            val item = findViewById<LinearLayout>(tab.second)
            val icon = findViewById<TextView>(tab.third.first)
            val label = findViewById<TextView>(tab.third.second)

            item.background = if (selected) {
                GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(accentDarkColor(), Color.rgb(25, 24, 43))
                ).apply {
                    cornerRadius = dp(17).toFloat()
                    setStroke(dp(1), accentColor)
                }
            } else {
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(17).toFloat()
                }
            }

            icon.setTextColor(
                if (selected) accentLightColor() else Color.rgb(123, 130, 153)
            )
            label.setTextColor(
                if (selected) Color.WHITE else Color.rgb(123, 130, 153)
            )
            label.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    private fun updateTopCategoryStyle() {
        val chips = listOf(
            "all" to R.id.chipAllSongs,
            "favorites" to R.id.chipFavorites,
            "playlists" to R.id.chipPlaylists
        )

        chips.forEach { (key, id) ->
            val chip = findViewById<TextView>(id)
            val selected = selectedTopCategory == key
            chip.background = if (selected) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(accentColor, accentDarkColor())
                ).apply {
                    cornerRadius = dp(19).toFloat()
                    setStroke(dp(1), accentLightColor())
                }
            } else {
                roundedBackground(Color.rgb(24, 27, 43), 19, Color.rgb(48, 51, 73))
            }
            chip.setTextColor(
                if (selected) Color.WHITE else Color.rgb(202, 204, 220)
            )
            chip.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
        }
    }

    private fun playRandomMix() {
        if (songs.isEmpty()) {
            Toast.makeText(this, "No songs available yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is connecting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        favoritesOnly = false
        selectedPlaylist = null
        selectedTopCategory = "all"
        selectedNavigation = "home"
        showHomeSections(true)
        updateTopCategoryStyle()
        updateBottomNavigationStyle()
        player.shuffleModeEnabled = true
        playSong(songs.random(), openPlayer = true)
    }

    // ---------------------------------------------------------
    // PLAYER SKINS, THEME COLOURS AND SONG STORAGE ACTIONS
    // ---------------------------------------------------------

    private fun showPlayerSkinPicker() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(34, 26, 66), Color.rgb(10, 12, 25))
            ).apply {
                cornerRadius = dp(27).toFloat()
                setStroke(dp(1), Color.rgb(76, 61, 119))
            }
        }
        panel.addView(TextView(this).apply {
            text = "PLAYER SKINS"
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = "Choose a look for your listening screen."
            textSize = 12f
            setTextColor(Color.rgb(160, 160, 187))
            setPadding(0, dp(5), 0, dp(13))
        })

        val scroll = android.widget.ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val skinNames = listOf(
            "Classic Gramophone" to "Warm brass horn and polished wood",
            "Vintage Cassette" to "Retro cassette deck with reel details",
            "Vinyl Turntable" to "A classic record player in 3D style",
            "Retro Radio" to "Old-school radio with tuning dial",
            "Nova Midnight" to "Modern dark player with neon accents"
        )

        skinNames.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(9), dp(9), dp(11), dp(9))
                background = roundedBackground(
                    if (selectedPlayerSkin == index) Color.rgb(46, 35, 83) else Color.rgb(21, 23, 41),
                    18,
                    if (selectedPlayerSkin == index) accentColor else Color.rgb(51, 49, 76)
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(108)
                ).apply { bottomMargin = dp(9) }
                isClickable = true
                isFocusable = true
            }
            val preview = NovaPlayerSkinView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
                setSkin(index)
            }
            card.addView(preview)
            val labels = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            }
            labels.addView(TextView(this).apply {
                text = item.first
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            labels.addView(TextView(this).apply {
                text = item.second
                textSize = 11f
                setTextColor(Color.rgb(156, 160, 183))
                setPadding(0, dp(4), 0, 0)
            })
            labels.addView(TextView(this).apply {
                text = if (selectedPlayerSkin == index) "✓  SELECTED" else "TAP TO SELECT"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setTextColor(if (selectedPlayerSkin == index) accentLightColor() else Color.rgb(126, 131, 155))
                setPadding(0, dp(8), 0, 0)
            })
            card.addView(labels)
            card.setOnClickListener {
                selectedPlayerSkin = index
                appearancePreferences.edit().putInt("player_skin_id", index).apply()
                nowPlayingSkinView?.setSkin(index)
                dialog.dismiss()
                Toast.makeText(this, "${item.first} selected", Toast.LENGTH_SHORT).show()
            }
            list.addView(card)
        }
        scroll.addView(list)
        panel.addView(scroll)

        val close = TextView(this).apply {
            text = "DONE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = accentButtonBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(12) }
            setOnClickListener { dialog.dismiss() }
        }
        panel.addView(close)
        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.78f
            attributes = params
        }
    }

    private fun loadAlbumArtworkForSkin(song: SongInfo, skinView: NovaPlayerSkinView) {
        skinView.setArtwork(null)
        if (song.albumId <= 0L) return
        val cached = artworkCache.get(song.albumId)
        if (cached != null) {
            skinView.setArtwork(cached)
            return
        }
        artworkExecutor.execute {
            val artworkUri = Uri.withAppendedPath(
                Uri.parse("content://media/external/audio/albumart"),
                song.albumId.toString()
            )
            val bitmap = try {
                contentResolver.openInputStream(artworkUri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.d("NovaMusic", "No artwork for player skin album ${song.albumId}", e)
                null
            }
            if (bitmap != null) artworkCache.put(song.albumId, bitmap)
            mainHandler.post {
                if (!isFinishing && nowPlayingSkinView === skinView && bitmap != null) {
                    skinView.setArtwork(bitmap)
                }
            }
        }
    }

    private fun showThemeColorDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(31, 25, 61), Color.rgb(12, 14, 27))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(71, 61, 111))
            }
        }
        panel.addView(TextView(this).apply {
            text = "Choose your colour"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = "Personalise Nova Music with your favourite accent."
            textSize = 12f
            setTextColor(Color.rgb(158, 160, 186))
            setPadding(0, dp(5), 0, dp(16))
        })

        val options = listOf(
            "Nova Violet" to Color.rgb(169, 137, 255),
            "Ocean Blue" to Color.rgb(74, 164, 255),
            "Emerald" to Color.rgb(65, 207, 158),
            "Rose" to Color.rgb(255, 111, 166),
            "Sunset" to Color.rgb(255, 157, 79),
            "Ice" to Color.rgb(126, 220, 238)
        )
        options.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(91)
                )
            }
            chunk.forEach { option ->
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        accentColor = option.second
                        appearancePreferences.edit().putInt("accent_color", accentColor).apply()
                        applyAccentColor()
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "${option.first} theme applied", Toast.LENGTH_SHORT).show()
                    }
                }
                column.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(option.second)
                        setStroke(dp(2), if (accentColor == option.second) Color.WHITE else Color.argb(100, 255, 255, 255))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    elevation = dp(4).toFloat()
                })
                column.addView(TextView(this).apply {
                    text = option.first
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(206, 206, 224))
                    setPadding(0, dp(6), 0, 0)
                })
                row.addView(column)
            }
            panel.addView(row)
        }
        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.76f
            attributes = params
        }
    }

    private fun applyAccentColor() {
        updateBottomNavigationStyle()
        updateTopCategoryStyle()
        findViewById<TextView>(R.id.btnHeaderSettings)?.background =
            roundedBackground(accentDarkColor(), 16, accentColor)
        findViewById<TextView>(R.id.btnPlayMix)?.background = accentButtonBackground()
        nowPlayingSkinView?.invalidate()
    }

    private fun accentDarkColor(): Int = blendColor(accentColor, Color.BLACK, 0.58f)

    private fun accentLightColor(): Int = blendColor(accentColor, Color.WHITE, 0.28f)

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(from) * (1f - t) + Color.red(to) * t).toInt()
        val g = (Color.green(from) * (1f - t) + Color.green(to) * t).toInt()
        val b = (Color.blue(from) * (1f - t) + Color.blue(to) * t).toInt()
        return Color.rgb(r, g, b)
    }

    private fun accentButtonBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(accentColor, accentDarkColor())
    ).apply {
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), accentLightColor())
    }

    private fun showSongOptionsDialog(song: SongInfo) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(32, 26, 58), Color.rgb(12, 14, 27))
            ).apply {
                cornerRadius = dp(23).toFloat()
                setStroke(dp(1), Color.rgb(69, 58, 105))
            }
        }
        panel.addView(TextView(this).apply {
            text = song.title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
        })
        panel.addView(TextView(this).apply {
            text = song.artist
            textSize = 12f
            setTextColor(Color.rgb(155, 159, 184))
            setPadding(0, dp(4), 0, dp(13))
        })
        fun addOption(label: String, symbol: String, destructive: Boolean = false, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = roundedBackground(Color.rgb(23, 25, 43), 14, Color.rgb(49, 48, 73))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                ).apply { topMargin = dp(7) }
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = symbol
                textSize = 18f
                setTextColor(if (destructive) Color.rgb(255, 117, 135) else accentLightColor())
            })
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (destructive) Color.rgb(255, 132, 145) else Color.WHITE)
                setPadding(dp(12), 0, 0, 0)
            })
            row.setOnClickListener { dialog.dismiss(); action() }
            panel.addView(row)
        }
        addOption("Add to playlist", "+") { showAddToPlaylistDialog(song) }
        addOption(
            if (song.id.toString() in getFavoriteIds()) "Remove from favorites" else "Add to favorites",
            "♡"
        ) { toggleFavorite(song) }
        addOption("Delete from device", "⌫", true) { confirmDeleteSongFromDevice(song) }
        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(resources.displayMetrics.widthPixels - dp(34), WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.78f
            attributes = params
        }
    }

    private fun confirmDeleteSongFromDevice(song: SongInfo) {
        AlertDialog.Builder(this)
            .setTitle("Delete song from device?")
            .setMessage("\"${song.title}\" will be removed from your device storage. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteSongFromStorage(song) }
            .show()
    }

    private fun deleteSongFromStorage(song: SongInfo) {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteSong = song
                pendingDeleteRequiresRetry = false
                val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                deleteSongLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                try {
                    val deleted = contentResolver.delete(uri, null, null)
                    if (deleted > 0) finishSongDeletion(song)
                    else Toast.makeText(this, "Song could not be deleted", Toast.LENGTH_SHORT).show()
                } catch (security: SecurityException) {
                    if (security is RecoverableSecurityException) {
                        pendingDeleteSong = song
                        pendingDeleteRequiresRetry = true
                        deleteSongLauncher.launch(
                            IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build()
                        )
                    } else {
                        throw security
                    }
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingDeleteSong = song
                    pendingDeleteRequiresRetry = false
                    legacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return
                }
                val deleted = contentResolver.delete(uri, null, null)
                if (deleted > 0) finishSongDeletion(song)
                else Toast.makeText(this, "Song could not be deleted", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            pendingDeleteSong = null
            pendingDeleteRequiresRetry = false
            Log.e("NovaMusic", "Unable to delete song from storage", e)
            Toast.makeText(this, "Unable to delete this song", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishSongDeletion(song: SongInfo) {
        pendingDeleteSong = null
        pendingDeleteRequiresRetry = false
        val songId = song.id.toString()
        try {
            if (::player.isInitialized) {
                val index = (0 until player.mediaItemCount).firstOrNull {
                    player.getMediaItemAt(it).mediaId == songId
                }
                if (index != null) {
                    if (player.currentMediaItemIndex == index) player.pause()
                    player.removeMediaItem(index)
                }
            }
        } catch (e: Exception) {
            Log.w("NovaMusic", "Deleted track could not be removed from active queue", e)
        }
        activeQueue = activeQueue.filterNot { it.id == song.id }
        favoritesPreferences.edit().apply {
            val ids = getFavoriteIds()
            ids.remove(songId)
            putStringSet("favorite_song_ids", ids)
        }.apply()
        val playlists = readPlaylists()
        playlists.values.forEach { it.remove(songId) }
        savePlaylists(playlists)
        Toast.makeText(this, "Song deleted from device", Toast.LENGTH_LONG).show()
        loadSongs()
    }

    private fun showSettingsDialog() {
        if (settingsDialog?.isShowing == true) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(31, 25, 61), Color.rgb(12, 14, 27), Color.rgb(17, 18, 34))
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(71, 61, 111))
            }
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(17)
            }
            background = roundedBackground(Color.rgb(112, 98, 161), 4)
        }
        panel.addView(handle)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heading.addView(TextView(this).apply {
            text = "Nova Settings"
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heading.addView(TextView(this).apply {
            text = "Make your music experience yours."
            textSize = 12f
            setTextColor(Color.rgb(162, 160, 190))
            setPadding(0, dp(5), 0, 0)
        })
        header.addView(heading)

        val close = TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 213, 242))
            background = roundedBackground(Color.rgb(42, 37, 66), 14, Color.rgb(71, 63, 99))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close)
        panel.addView(header)

        fun addSettingsRow(
            symbol: String,
            titleText: String,
            detailText: String,
            action: () -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(13), dp(12))
                background = roundedBackground(
                    Color.rgb(25, 26, 45), 17, Color.rgb(51, 48, 76)
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }

            row.addView(TextView(this).apply {
                text = symbol
                textSize = 23f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(201, 185, 255))
                background = roundedBackground(Color.rgb(56, 44, 94), 13)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            })

            val textGroup = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(12) }
            }
            textGroup.addView(TextView(this).apply {
                text = titleText
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            textGroup.addView(TextView(this).apply {
                text = detailText
                textSize = 11f
                setTextColor(Color.rgb(154, 157, 181))
                setPadding(0, dp(4), 0, 0)
            })
            row.addView(textGroup)
            row.addView(TextView(this).apply {
                text = "›"
                textSize = 25f
                setTextColor(Color.rgb(165, 145, 225))
            })
            row.setOnClickListener {
                dialog.dismiss()
                action()
            }
            panel.addView(row)
        }

        addSettingsRow("♫", "Audio Equalizer", "Presets, bands, bass and spatial sound") {
            showEqualizerDialog()
        }
        addSettingsRow("☾", "Sleep Timer", "Pause music after your chosen time") {
            showSleepTimerDialog()
        }
        addSettingsRow("◉", "Theme Colour", "Choose the accent colour for Nova Music") {
            showThemeColorDialog()
        }
        addSettingsRow("▤", "Playlists", "Create and manage your collections") {
            selectedNavigation = "playlists"
            showHomeSections(false)
            updateBottomNavigationStyle()
            showPlaylistsDialog()
        }
        addSettingsRow("↻", "Refresh Music Library", "Scan the music stored on this device") {
            loadSongs()
        }
        addSettingsRow("N", "About Nova Music", "Your personal soundscape") {
            AlertDialog.Builder(this)
                .setTitle("Nova Music")
                .setMessage("A lightweight music player with local songs, playlists, favorites, Equalizer and Sleep Timer.")
                .setPositiveButton("Close", null)
                .show()
        }

        dialog.setContentView(panel)
        dialog.setOnDismissListener { settingsDialog = null }
        dialog.setCancelable(true)
        settingsDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.76f
            attributes = params
            navigationBarColor = Color.rgb(12, 14, 27)
        }
    }

    private fun showPlaylistsDialog() {
        selectedNavigation = "playlists"
        selectedTopCategory = "playlists"
        showHomeSections(false)
        updateTopCategoryStyle()
        updateBottomNavigationStyle()
        if (playlistsDialog?.isShowing == true) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(31, 25, 61), Color.rgb(12, 14, 27), Color.rgb(17, 18, 34))
            ).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.rgb(71, 61, 111))
            }
        }

        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = roundedBackground(Color.rgb(112, 98, 161), 4)
        }
        panel.addView(handle)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heading.addView(TextView(this).apply {
            text = "Your Playlists"
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heading.addView(TextView(this).apply {
            text = "Collections made for every mood."
            textSize = 12f
            setTextColor(Color.rgb(160, 160, 187))
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(heading)

        val close = TextView(this).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(42, 37, 66), 14, Color.rgb(71, 63, 99))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close)
        panel.addView(header)

        val createButton = TextView(this).apply {
            text = "＋   CREATE NEW PLAYLIST"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = accentButtonBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { topMargin = dp(18); bottomMargin = dp(14) }
            setOnClickListener {
                dialog.dismiss()
                showCreatePlaylistDialog()
            }
        }
        panel.addView(createButton)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val playlists = readPlaylists().toSortedMap(String.CASE_INSENSITIVE_ORDER)
        if (playlists.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(28), dp(14), dp(28))
                background = roundedBackground(Color.rgb(24, 25, 44), 20, Color.rgb(52, 48, 78))
            }
            empty.addView(TextView(this).apply {
                text = "♫"
                textSize = 38f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
            })
            empty.addView(TextView(this).apply {
                text = "No playlists yet"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(7), 0, dp(4))
            })
            empty.addView(TextView(this).apply {
                text = "Create your first playlist and keep your favourite tracks together."
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(157, 161, 184))
            })
            list.addView(empty)
        } else {
            playlists.forEach { (name, ids) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(11), dp(8), dp(11))
                    background = roundedBackground(Color.rgb(24, 25, 44), 17, Color.rgb(52, 48, 78))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(70)
                    ).apply { bottomMargin = dp(9) }
                }
                row.addView(TextView(this).apply {
                    text = "♫"
                    textSize = 25f
                    gravity = Gravity.CENTER
                    setTextColor(accentColor)
                    background = roundedBackground(Color.rgb(52, 42, 85), 13)
                    layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                })
                val textGroup = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginStart = dp(11) }
                }
                textGroup.addView(TextView(this).apply {
                    text = name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    maxLines = 1
                })
                textGroup.addView(TextView(this).apply {
                    text = "${ids.size} ${if (ids.size == 1) "song" else "songs"}"
                    textSize = 11f
                    setTextColor(Color.rgb(151, 157, 181))
                    setPadding(0, dp(4), 0, 0)
                })
                row.addView(textGroup)
                val open = TextView(this).apply {
                    text = "OPEN"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = roundedBackground(accentDarkColor(), 12, accentColor)
                    setPadding(dp(11), dp(9), dp(11), dp(9))
                    setOnClickListener {
                        selectedPlaylist = name
                        favoritesOnly = false
                        dialog.dismiss()
                        renderSongs(currentSearchQuery())
                    }
                }
                row.addView(open)
                val more = TextView(this).apply {
                    text = "⋮"
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(174, 177, 198))
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(46))
                    setOnClickListener { showPlaylistOptions(name, dialog) }
                }
                row.addView(more)
                row.setOnClickListener {
                    selectedPlaylist = name
                    favoritesOnly = false
                    dialog.dismiss()
                    renderSongs(currentSearchQuery())
                }
                list.addView(row)
            }
        }
        scroll.addView(list)
        panel.addView(scroll)

        val allSongs = TextView(this).apply {
            text = "VIEW ALL SONGS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(205, 197, 235))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                selectedPlaylist = null
                favoritesOnly = false
                selectedTopCategory = "all"
                selectedNavigation = "library"
                updateTopCategoryStyle()
                updateBottomNavigationStyle()
                dialog.dismiss()
                renderSongs(currentSearchQuery())
            }
        }
        panel.addView(allSongs)

        dialog.setContentView(panel)
        dialog.setOnDismissListener { playlistsDialog = null }
        dialog.setCancelable(true)
        playlistsDialog = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.dimAmount = 0.76f
            attributes = params
            navigationBarColor = Color.rgb(12, 14, 27)
        }
    }

    private fun showPlaylistOptions(name: String, parentDialog: Dialog) {
        val options = arrayOf("Open playlist", "Delete playlist")
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedPlaylist = name
                        favoritesOnly = false
                        parentDialog.dismiss()
                        renderSongs(currentSearchQuery())
                    }
                    1 -> {
                        parentDialog.dismiss()
                        confirmDeletePlaylist(name)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatePlaylistDialog(songToAdd: SongInfo? = null) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(34, 27, 66), Color.rgb(13, 15, 30))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(77, 64, 119))
            }
        }
        panel.addView(TextView(this).apply {
            text = "✦  NEW PLAYLIST"
            textSize = 10f
            letterSpacing = 0.16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        })
        panel.addView(TextView(this).apply {
            text = "Give it a name."
            textSize = 23f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(5))
        })
        panel.addView(TextView(this).apply {
            text = "Create a collection for the music you love."
            textSize = 12f
            setTextColor(Color.rgb(165, 164, 190))
        })

        val input = EditText(this).apply {
            hint = "e.g. Night Drive, Study Time..."
            setSingleLine(true)
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(132, 137, 161))
            background = roundedBackground(Color.rgb(17, 19, 36), 15, accentColor)
            setPadding(dp(15), 0, dp(15), 0)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        panel.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(20) })

        val hint = TextView(this).apply {
            text = if (songToAdd != null) "The selected song will be added automatically." else "You can add songs to this playlist after creating it."
            textSize = 11f
            setTextColor(Color.rgb(144, 150, 174))
            setPadding(dp(2), dp(8), dp(2), dp(2))
        }
        panel.addView(hint)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(18) }
        }
        val cancel = TextView(this).apply {
            text = "CANCEL"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(194, 192, 214))
            background = roundedBackground(Color.rgb(36, 35, 55), 14)
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { dialog.dismiss() }
        }
        val create = TextView(this).apply {
            text = "CREATE PLAYLIST"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = accentButtonBackground()
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.35f).apply { marginStart = dp(6) }
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = "Enter a playlist name"
                    input.requestFocus()
                    return@setOnClickListener
                }
                val playlists = readPlaylists()
                if (playlists.keys.any { it.equals(name, ignoreCase = true) }) {
                    input.error = "A playlist with this name already exists"
                    input.requestFocus()
                    return@setOnClickListener
                }
                val ids = linkedSetOf<String>()
                songToAdd?.let { ids.add(it.id.toString()) }
                playlists[name] = ids
                savePlaylists(playlists)
                selectedPlaylist = name
                favoritesOnly = false
                selectedNavigation = "playlists"
                selectedTopCategory = "playlists"
                updateTopCategoryStyle()
                updateBottomNavigationStyle()
                dialog.dismiss()
                renderSongs(currentSearchQuery())
                Toast.makeText(this@MainActivity, "Playlist created", Toast.LENGTH_SHORT).show()
            }
        }
        actions.addView(cancel)
        actions.addView(create)
        panel.addView(actions)

        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                (resources.displayMetrics.widthPixels - dp(32)),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.CENTER)
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        input.requestFocus()
    }

    private fun showAddToPlaylistDialog(song: SongInfo) {
        val names = readPlaylists().keys.sortedBy { it.lowercase() }.toTypedArray()
        val builder = AlertDialog.Builder(this).setTitle("Add to playlist")

        if (names.isEmpty()) {
            builder.setMessage("Create your first playlist to save this song in it.")
        } else {
            builder.setItems(names) { _, which ->
                addSongToPlaylist(song, names[which])
            }
        }

        builder.setPositiveButton("Create new") { _, _ ->
            showCreatePlaylistDialog(song)
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun addSongToPlaylist(song: SongInfo, playlistName: String) {
        val playlists = readPlaylists()
        val ids = playlists[playlistName] ?: linkedSetOf<String>()
        val added = ids.add(song.id.toString())
        playlists[playlistName] = ids
        savePlaylists(playlists)
        Toast.makeText(
            this,
            if (added) "Added to $playlistName" else "Already in $playlistName",
            Toast.LENGTH_SHORT
        ).show()
        if (selectedPlaylist == playlistName) renderSongs(currentSearchQuery())
    }

    private fun removeSongFromPlaylist(song: SongInfo, playlistName: String) {
        val playlists = readPlaylists()
        val ids = playlists[playlistName] ?: return
        ids.remove(song.id.toString())
        playlists[playlistName] = ids
        savePlaylists(playlists)
        Toast.makeText(this, "Removed from $playlistName", Toast.LENGTH_SHORT).show()
        renderSongs(currentSearchQuery())
    }

    private fun confirmDeletePlaylist(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete playlist?")
            .setMessage("Delete \"$name\"? Your music files will not be deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val playlists = readPlaylists()
                playlists.remove(name)
                savePlaylists(playlists)
                if (selectedPlaylist == name) selectedPlaylist = null
                renderSongs(currentSearchQuery())
                Toast.makeText(this, "Playlist deleted", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun currentQueueForSelection(): List<SongInfo> {
        return when {
            selectedPlaylist != null -> {
                val ids = getPlaylistSongIds(selectedPlaylist!!)
                songs.filter { it.id.toString() in ids }
            }
            favoritesOnly -> {
                val ids = getFavoriteIds()
                songs.filter { it.id.toString() in ids }
            }
            else -> songs.toList()
        }
    }

    private fun songForMediaItem(mediaItem: MediaItem?, index: Int): SongInfo? {
        val id = mediaItem?.mediaId?.toLongOrNull()
        return id?.let { songId -> songs.firstOrNull { it.id == songId } }
            ?: activeQueue.getOrNull(index)
            ?: songs.getOrNull(index)
    }

    private fun loadAlbumArtwork(song: SongInfo, imageView: ImageView, placeholder: TextView) {
        imageView.tag = song.id
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        placeholder.visibility = View.VISIBLE

        if (song.albumId <= 0L) return

        val cached = artworkCache.get(song.albumId)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
            return
        }

        artworkExecutor.execute {
            val artworkUri = Uri.withAppendedPath(
                Uri.parse("content://media/external/audio/albumart"),
                song.albumId.toString()
            )
            val bitmap = try {
                contentResolver.openInputStream(artworkUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                Log.d("NovaMusic", "No artwork for album ${song.albumId}", e)
                null
            }

            if (bitmap != null) artworkCache.put(song.albumId, bitmap)

            mainHandler.post {
                if (!isFinishing && imageView.tag == song.id && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    imageView.visibility = View.VISIBLE
                    placeholder.visibility = View.GONE
                }
            }
        }
    }

    private fun playSong(song: SongInfo, openPlayer: Boolean = false) {
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is connecting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        val queueSongs = currentQueueForSelection().ifEmpty { songs.toList() }
        val selectedIndex = queueSongs.indexOfFirst { it.id == song.id }
        if (selectedIndex < 0) return
        activeQueue = queueSongs

        val mediaItems = queueSongs.map { item ->
            val songUri = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                item.id
            )
            val metadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .build()

            MediaItem.Builder()
                .setMediaId(item.id.toString())
                .setUri(songUri)
                .setMediaMetadata(metadata)
                .build()
        }

        try {
            player.setMediaItems(mediaItems, selectedIndex, 0L)
            player.prepare()
            player.play()
            updateMiniPlayer(song)
            if (openPlayer) showNowPlaying()
        } catch (e: Exception) {
            Log.e("NovaMusic", "Playback failed", e)
            Toast.makeText(this, "Unable to play this song", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayback() {
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is connecting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        if (player.mediaItemCount == 0) {
            if (songs.isNotEmpty()) {
                playSong(songs.first())
            } else {
                Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun showNowPlaying() {
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is connecting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        if (songs.isEmpty()) {
            Toast.makeText(this, "No songs available", Toast.LENGTH_SHORT).show()
            return
        }

        if (player.mediaItemCount == 0) {
            playSong(songs.first())
        }

        val currentSong = songForMediaItem(player.currentMediaItem, player.currentMediaItemIndex) ?: songs.first()
        if (nowPlayingDialog?.isShowing == true) {
            updateNowPlayingSong(currentSong)
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(20, 18, 43), Color.rgb(8, 10, 18), Color.rgb(13, 15, 29))
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }

        val closeButton = TextView(this).apply {
            text = "⌄"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(35, 34, 58), 16)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            contentDescription = "Close player"
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(closeButton)

        val topTitle = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 11f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(202, 194, 255))
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        topBar.addView(topTitle)

        val skinButton = TextView(this).apply {
            text = "◈"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(accentDarkColor(), 15, accentColor)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(44)).apply {
                marginEnd = dp(5)
            }
            contentDescription = "Choose player skin"
            isClickable = true
            isFocusable = true
            setOnClickListener { showPlayerSkinPicker() }
        }
        topBar.addView(skinButton)

        val equalizerButton = TextView(this).apply {
            text = "EQ"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(
                Color.rgb(47, 39, 86),
                15,
                Color.rgb(128, 107, 207)
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(6)
            }
            contentDescription = "Open Equalizer"
            isClickable = true
            isFocusable = true
            setOnClickListener { showEqualizerDialog() }
        }
        topBar.addView(equalizerButton)

        val sleepTimerButton = TextView(this).apply {
            text = "☾"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(91, 70, 178), 16)
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(44))
            isClickable = true
            isFocusable = true
            contentDescription = "Sleep timer"
            setOnClickListener { showSleepTimerDialog() }
        }
        topBar.addView(sleepTimerButton)
        nowPlayingSleepTimerButton = sleepTimerButton
        updateSleepTimerHeaderButton()
        root.addView(topBar)

        val scrollView = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }

        val artworkFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(276), dp(276)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
                bottomMargin = dp(26)
            }
            background = roundedBackground(Color.rgb(37, 30, 76), 30, Color.rgb(102, 88, 175))
            elevation = dp(12).toFloat()
            clipToOutline = true
        }

        val skinView = NovaPlayerSkinView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setSkin(selectedPlayerSkin)
        }
        artworkFrame.addView(skinView)
        content.addView(artworkFrame)

        val eyebrow = TextView(this).apply {
            text = "✦  YOUR MUSIC UNIVERSE  ✦"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(174, 158, 255))
        }
        content.addView(eyebrow)

        val titleView = TextView(this).apply {
            text = currentSong.title
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 2
            setPadding(dp(8), dp(10), dp(8), 0)
        }
        content.addView(titleView)

        val artistView = TextView(this).apply {
            text = currentSong.artist
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(156, 160, 181))
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        content.addView(artistView)

        val seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(178, 160, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(57, 53, 79))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
            ).apply {
                topMargin = dp(20)
                leftMargin = dp(2)
                rightMargin = dp(2)
            }
        }
        content.addView(seekBar)

        val timesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22)
            )
        }

        val positionView = TextView(this).apply {
            text = "0:00"
            textSize = 11f
            setTextColor(Color.rgb(160, 164, 184))
        }
        timesRow.addView(positionView)

        val timeSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        timesRow.addView(timeSpacer)

        val durationView = TextView(this).apply {
            text = formatDuration(currentSong.duration)
            textSize = 11f
            setTextColor(Color.rgb(160, 164, 184))
        }
        timesRow.addView(durationView)
        content.addView(timesRow)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88)
            ).apply { topMargin = dp(14) }
        }

        val shuffleButton = createPlayerControl("⤨", 46, false)
        val previousButton = createPlayerControl("⏮", 52, false)
        val playButton = createPlayerControl("Ⅱ", 70, true)
        val nextButton = createPlayerControl("⏭", 52, false)
        val repeatButton = createPlayerControl("↻", 46, false)

        controls.addView(shuffleButton, controlParams(46))
        controls.addView(previousButton, controlParams(52))
        controls.addView(playButton, controlParams(70))
        controls.addView(nextButton, controlParams(52))
        controls.addView(repeatButton, controlParams(46))
        content.addView(controls)

        val footer = TextView(this).apply {
            text = "N O V A   •   L O C A L   L I B R A R Y"
            textSize = 9f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(105, 111, 137))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
            ).apply { topMargin = dp(6) }
        }
        content.addView(footer)

        scrollView.addView(content)
        root.addView(scrollView)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(seekUpdateRunnable)
            nowPlayingDialog = null
            nowPlayingTitle = null
            nowPlayingArtist = null
            nowPlayingCover = null
            nowPlayingPlaceholder = null
            nowPlayingSeekBar = null
            nowPlayingPosition = null
            nowPlayingDuration = null
            nowPlayingPlayButton = null
            nowPlayingShuffleButton = null
            nowPlayingRepeatButton = null
            nowPlayingSleepTimerButton = null
            nowPlayingSkinView = null
            nowPlayingCover = null
            nowPlayingPlaceholder = null
            userIsSeeking = false
        }

        nowPlayingDialog = dialog
        nowPlayingTitle = titleView
        nowPlayingArtist = artistView
        nowPlayingSkinView = skinView
        nowPlayingCover = null
        nowPlayingPlaceholder = null
        nowPlayingSeekBar = seekBar
        nowPlayingPosition = positionView
        nowPlayingDuration = durationView
        nowPlayingPlayButton = playButton
        nowPlayingShuffleButton = shuffleButton
        nowPlayingRepeatButton = repeatButton

        loadAlbumArtworkForSkin(currentSong, skinView)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = getCurrentDuration()
                    positionView.text = formatDuration(duration * progress / 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = getCurrentDuration()
                if (duration > 0L) {
                    player.seekTo(duration * seekBar.progress / 1000L)
                }
                userIsSeeking = false
            }
        })

        shuffleButton.setOnClickListener {
            player.shuffleModeEnabled = !player.shuffleModeEnabled
            updateNowPlayingControls()
        }
        previousButton.setOnClickListener {
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
            } else {
                player.seekTo(0L)
            }
        }
        playButton.setOnClickListener { togglePlayback() }
        nextButton.setOnClickListener {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
        repeatButton.setOnClickListener {
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            updateNowPlayingControls()
        }

        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.rgb(20, 18, 43)
            navigationBarColor = Color.rgb(8, 10, 18)
        }
        updateNowPlayingSong(currentSong)
        updateNowPlayingControls()
        mainHandler.removeCallbacks(seekUpdateRunnable)
        mainHandler.post(seekUpdateRunnable)
    }

    // ---------------------------------------------------------
    // PREMIUM EQUALIZER UI
    // The audio effects are controlled by PlaybackService through Media3 commands.
    // ---------------------------------------------------------

    private fun showEqualizerDialog() {
        if (equalizerDialog?.isShowing == true) return
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is connecting. Try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 20, 53), Color.rgb(10, 12, 24), Color.rgb(15, 17, 32))
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleBox.addView(TextView(this).apply {
            text = "NOVA EQUALIZER"
            textSize = 21f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titleBox.addView(TextView(this).apply {
            text = "Shape your sound"
            textSize = 12f
            setTextColor(Color.rgb(165, 161, 192))
            setPadding(0, dp(4), 0, 0)
        })
        header.addView(titleBox)

        val close = TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 216, 239))
            background = roundedBackground(Color.rgb(38, 35, 62), 14, Color.rgb(67, 61, 97))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(8) }
            contentDescription = "Close Equalizer"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close)

        val eqSwitch = Switch(this).apply {
            text = "ON"
            textSize = 12f
            setTextColor(Color.rgb(221, 214, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(195, 177, 255))
            trackTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 75, 143))
            isEnabled = false
        }
        header.addView(eqSwitch)
        root.addView(header)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(18) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(TextView(this).apply {
            text = "Reading audio capabilities…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(35), dp(12), dp(35))
        })
        scroll.addView(body)
        root.addView(scroll)

        root.addView(TextView(this).apply {
            text = "N O V A   •   SOUND STUDIO"
            textSize = 10f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(116, 117, 148))
            setPadding(0, dp(12), 0, dp(4))
        })

        dialog.setContentView(root)
        dialog.setOnDismissListener { equalizerDialog = null }
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            statusBarColor = Color.rgb(25, 20, 53)
            navigationBarColor = Color.rgb(10, 12, 24)
        }

        equalizerDialog = dialog
        refreshEqualizerUi(dialog, body, eqSwitch)
    }

    private fun sendEqualizerCommand(
        action: String,
        args: Bundle = Bundle(),
        onResult: (Boolean) -> Unit = {}
    ) {
        if (!::player.isInitialized) {
            onResult(false)
            return
        }
        try {
            val future = player.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
            future.addListener({
                val success = try {
                    future.get().resultCode == SessionResult.RESULT_SUCCESS
                } catch (e: Exception) {
                    Log.e("NovaMusicEQ", "Equalizer command failed", e)
                    false
                }
                if (!isFinishing && !isDestroyed) onResult(success)
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("NovaMusicEQ", "Could not send Equalizer command", e)
            onResult(false)
        }
    }

    private fun requestEqualizerInfo(onLoaded: (Bundle?) -> Unit) {
        if (!::player.isInitialized) {
            onLoaded(null)
            return
        }
        try {
            val future = player.sendCustomCommand(
                SessionCommand(EqualizerCommands.GET_INFO, Bundle.EMPTY), Bundle.EMPTY
            )
            future.addListener({
                val data = try {
                    val result = future.get()
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) result.extras else null
                } catch (e: Exception) {
                    Log.e("NovaMusicEQ", "Unable to read Equalizer info", e)
                    null
                }
                if (!isFinishing && !isDestroyed) onLoaded(data)
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("NovaMusicEQ", "Unable to request Equalizer info", e)
            onLoaded(null)
        }
    }

    private fun refreshEqualizerUi(dialog: Dialog, body: LinearLayout, eqSwitch: Switch) {
        requestEqualizerInfo { info ->
            if (!dialog.isShowing || equalizerDialog !== dialog) return@requestEqualizerInfo
            body.removeAllViews()

            if (info == null) {
                body.addView(eqInfoCard(
                    "Audio engine unavailable",
                    "Could not read Equalizer settings from PlaybackService. Check that the updated PlaybackService.kt is installed."
                ))
                eqSwitch.isEnabled = false
                return@requestEqualizerInfo
            }

            val available = info.getBoolean("eq_available", false)
            eqSwitch.setOnCheckedChangeListener(null)
            eqSwitch.isChecked = info.getBoolean("eq_enabled", false)
            eqSwitch.isEnabled = available
            eqSwitch.setOnCheckedChangeListener { _, enabled ->
                val args = Bundle().apply { putBoolean(EqualizerCommands.ENABLED_KEY, enabled) }
                sendEqualizerCommand(EqualizerCommands.SET_ENABLED, args) { success ->
                    if (!success) {
                        Toast.makeText(this, "Equalizer is unavailable on this device", Toast.LENGTH_SHORT).show()
                        refreshEqualizerUi(dialog, body, eqSwitch)
                    }
                }
            }
            renderEqualizerBody(body, info, dialog, eqSwitch)
        }
    }

    private fun renderEqualizerBody(body: LinearLayout, info: Bundle, dialog: Dialog, eqSwitch: Switch) {
        body.removeAllViews()
        val bandCount = info.getInt("band_count", 0)
        val minLevel = info.getInt("min_level_mb", -1500)
        val maxLevel = info.getInt("max_level_mb", 1500)
        val frequencies = info.getIntArray("band_frequencies_hz") ?: intArrayOf()
        val levels = info.getIntArray("band_levels_mb") ?: intArrayOf()
        val presets = info.getStringArray("preset_names") ?: emptyArray()
        val selectedPreset = info.getInt("selected_preset", -1)

        body.addView(eqSectionTitle("FREQUENCY BANDS"))
        body.addView(TextView(this).apply {
            text = if (bandCount > 0) "Fine-tune the sound to your taste." else "No compatible Equalizer bands were reported by this device."
            textSize = 12f
            setTextColor(Color.rgb(156, 158, 183))
            setPadding(0, dp(3), 0, dp(12))
        })

        val actualCount = minOf(bandCount, frequencies.size, levels.size)
        if (actualCount > 0) {
            val horizontalScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val bandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (index in 0 until actualCount) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), dp(12), dp(4), dp(10))
                    background = roundedBackground(Color.rgb(25, 25, 45), 17, Color.rgb(53, 48, 81))
                    layoutParams = LinearLayout.LayoutParams(dp(68), dp(228)).apply { marginEnd = dp(7) }
                }
                card.addView(TextView(this).apply {
                    text = formatEqFrequency(frequencies[index])
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(208, 197, 255))
                })
                val valueLabel = TextView(this).apply {
                    text = formatEqLevel(levels[index])
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(7), 0, dp(4))
                }
                card.addView(valueLabel)

                val slider = VerticalEqualizerSlider(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(148))
                    setRange(minLevel, maxLevel, levels[index])
                    isEnabled = availableEq(info)
                    alpha = if (isEnabled) 1f else 0.5f
                    onLevelChanged = { value -> valueLabel.text = formatEqLevel(value) }
                    onLevelCommitted = { value ->
                        val args = Bundle().apply {
                            putInt(EqualizerCommands.BAND_INDEX_KEY, index)
                            putInt(EqualizerCommands.BAND_LEVEL_KEY, value)
                        }
                        sendEqualizerCommand(EqualizerCommands.SET_BAND, args) { success ->
                            if (!success) Toast.makeText(this@MainActivity, "Could not apply this band", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                card.addView(slider)
                bandRow.addView(card)
            }
            horizontalScroll.addView(bandRow)
            body.addView(horizontalScroll)
        }

        body.addView(eqSectionTitle("SOUND PRESETS"))
        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presets.forEachIndexed { index, presetName ->
            val chip = TextView(this).apply {
                text = presetName.trim()
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (selectedPreset == index) Color.WHITE else Color.rgb(203, 199, 222))
                background = roundedBackground(
                    if (selectedPreset == index) Color.rgb(105, 79, 205) else Color.rgb(29, 29, 50),
                    18,
                    if (selectedPreset == index) Color.rgb(167, 144, 255) else Color.rgb(58, 53, 81)
                )
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(8) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val args = Bundle().apply { putInt(EqualizerCommands.PRESET_INDEX_KEY, index) }
                    sendEqualizerCommand(EqualizerCommands.SET_PRESET, args) { success ->
                        if (success) refreshEqualizerUi(dialog, body, eqSwitch)
                        else Toast.makeText(this@MainActivity, "Unable to apply preset", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            presetRow.addView(chip)
        }
        if (presets.isEmpty()) {
            presetRow.addView(TextView(this).apply {
                text = "No device presets available"
                textSize = 12f
                setTextColor(Color.rgb(156, 158, 183))
            })
        }
        presetScroll.addView(presetRow)
        body.addView(presetScroll)

        addAudioEffectCard(body, info, "Bass Boost", "Add more weight to low frequencies.", "bass_available", "bass_enabled", "bass_strength", EqualizerCommands.SET_BASS)
        addAudioEffectCard(body, info, "Virtualizer", "Create a wider, more spacious sound.", "virtualizer_available", "virtualizer_enabled", "virtualizer_strength", EqualizerCommands.SET_VIRTUALIZER)

        val resetButton = TextView(this).apply {
            text = "RESET EQUALIZER"
            textSize = 12f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(58, 43, 103), 15, Color.rgb(111, 91, 173))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(18)
                bottomMargin = dp(12)
            }
            setOnClickListener { resetEqualizer(actualCount, dialog, body, eqSwitch) }
        }
        body.addView(resetButton)
    }

    private fun availableEq(info: Bundle): Boolean = info.getBoolean("eq_available", false)

    private fun addAudioEffectCard(
        body: LinearLayout,
        info: Bundle,
        titleText: String,
        description: String,
        availableKey: String,
        enabledKey: String,
        strengthKey: String,
        action: String
    ) {
        val available = info.getBoolean(availableKey, false)
        val initiallyEnabled = info.getBoolean(enabledKey, false)
        val initialStrength = info.getInt(strengthKey, 500).coerceIn(0, 1000)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = roundedBackground(Color.rgb(24, 25, 44), 19, Color.rgb(53, 48, 81))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(15) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = titleText
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        labels.addView(TextView(this).apply {
            text = if (available) description else "Not supported on this device"
            textSize = 11f
            setTextColor(Color.rgb(154, 156, 181))
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(labels)

        val effectSwitch = Switch(this).apply {
            isChecked = initiallyEnabled
            isEnabled = available
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.rgb(195, 177, 255))
            trackTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 75, 143))
        }
        top.addView(effectSwitch)
        card.addView(top)

        val strengthLabel = TextView(this).apply {
            text = "Intensity  ${initialStrength / 10}%"
            textSize = 12f
            setTextColor(Color.rgb(199, 190, 235))
            setPadding(0, dp(12), 0, 0)
        }
        card.addView(strengthLabel)

        val strengthSeek = SeekBar(this).apply {
            max = 1000
            progress = initialStrength
            isEnabled = available
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(169, 141, 255))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        card.addView(strengthSeek)
        body.addView(card)

        effectSwitch.setOnCheckedChangeListener { _, enabled ->
            val args = Bundle().apply {
                putBoolean(EqualizerCommands.ENABLED_KEY, enabled)
                putInt(EqualizerCommands.STRENGTH_KEY, strengthSeek.progress)
            }
            sendEqualizerCommand(action, args) { success ->
                if (!success) Toast.makeText(this, "$titleText could not be applied", Toast.LENGTH_SHORT).show()
            }
        }

        strengthSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                strengthLabel.text = "Intensity  ${progress / 10}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val args = Bundle().apply {
                    putBoolean(EqualizerCommands.ENABLED_KEY, effectSwitch.isChecked)
                    putInt(EqualizerCommands.STRENGTH_KEY, seekBar.progress)
                }
                sendEqualizerCommand(action, args) { success ->
                    if (!success) Toast.makeText(this@MainActivity, "$titleText could not be applied", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun resetEqualizer(bandCount: Int, dialog: Dialog, body: LinearLayout, eqSwitch: Switch) {
        val commands = mutableListOf<Pair<String, Bundle>>()
        for (index in 0 until bandCount) {
            commands.add(EqualizerCommands.SET_BAND to Bundle().apply {
                putInt(EqualizerCommands.BAND_INDEX_KEY, index)
                putInt(EqualizerCommands.BAND_LEVEL_KEY, 0)
            })
        }
        commands.add(EqualizerCommands.SET_ENABLED to Bundle().apply {
            putBoolean(EqualizerCommands.ENABLED_KEY, false)
        })
        commands.add(EqualizerCommands.SET_BASS to Bundle().apply {
            putBoolean(EqualizerCommands.ENABLED_KEY, false)
            putInt(EqualizerCommands.STRENGTH_KEY, 0)
        })
        commands.add(EqualizerCommands.SET_VIRTUALIZER to Bundle().apply {
            putBoolean(EqualizerCommands.ENABLED_KEY, false)
            putInt(EqualizerCommands.STRENGTH_KEY, 0)
        })

        // Send in order so the service receives a predictable reset sequence.
        fun sendNext(position: Int, allOk: Boolean) {
            if (position >= commands.size) {
                if (dialog.isShowing) {
                    Toast.makeText(this, if (allOk) "Equalizer reset" else "Some settings could not be reset", Toast.LENGTH_SHORT).show()
                    refreshEqualizerUi(dialog, body, eqSwitch)
                }
                return
            }
            val (action, args) = commands[position]
            sendEqualizerCommand(action, args) { success -> sendNext(position + 1, allOk && success) }
        }
        sendNext(0, true)
    }

    private fun eqSectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 10f
        letterSpacing = 0.15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(156, 143, 205))
        setPadding(0, dp(22), 0, dp(5))
    }

    private fun eqInfoCard(titleText: String, detail: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = roundedBackground(Color.rgb(25, 25, 45), 18, Color.rgb(53, 48, 81))
        addView(TextView(this@MainActivity).apply {
            text = titleText
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@MainActivity).apply {
            text = detail
            textSize = 12f
            setTextColor(Color.rgb(169, 166, 193))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun formatEqFrequency(hz: Int): String = if (hz >= 1000) {
        String.format(java.util.Locale.US, "%.1f", hz / 1000f).replace(".0", "") + " kHz"
    } else "$hz Hz"

    private fun formatEqLevel(levelMb: Int): String = String.format(
        java.util.Locale.US, "%+.1f dB", levelMb / 100f
    )

    private fun showSleepTimerDialog() {
        if (sleepTimerDialog?.isShowing == true) return

        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(28, 24, 57),
                    Color.rgb(15, 16, 32),
                    Color.rgb(9, 11, 22)
                )
            ).apply {
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(66, 59, 105))
            }
            elevation = dp(18).toFloat()
        }

        // Small drag-handle accent
        val handle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
            background = roundedBackground(Color.rgb(102, 94, 143), 4)
        }
        sheet.addView(handle)

        // Header: premium icon, title, subtitle, close button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val moonBadge = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            gravity = Gravity.CENTER
            text = "☾"
            textSize = 28f
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(148, 111, 255), Color.rgb(75, 48, 165))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Color.rgb(193, 174, 255))
            }
            elevation = dp(7).toFloat()
        }
        header.addView(moonBadge)

        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(13) }
        }
        val title = TextView(this).apply {
            text = "Sleep Timer"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Let the music fade when you do."
            textSize = 12f
            setTextColor(Color.rgb(163, 164, 190))
            setPadding(0, dp(4), 0, 0)
        }
        headerText.addView(title)
        headerText.addView(subtitle)
        header.addView(headerText)

        val closeButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            gravity = Gravity.CENTER
            text = "✕"
            textSize = 15f
            setTextColor(Color.rgb(218, 216, 239))
            background = roundedBackground(Color.rgb(38, 35, 62), 14, Color.rgb(67, 61, 97))
            isClickable = true
            isFocusable = true
            contentDescription = "Close sleep timer"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeButton)
        sheet.addView(header)

        // Active timer status card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(22)
                bottomMargin = dp(24)
            }
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(42, 35, 83), Color.rgb(26, 27, 49))
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(82, 69, 139))
            }
        }

        val statusIcon = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
            gravity = Gravity.CENTER
            text = "◷"
            textSize = 27f
            setTextColor(Color.rgb(215, 203, 255))
            background = roundedBackground(Color.rgb(75, 60, 134), 15)
        }
        statusCard.addView(statusIcon)

        val statusTextContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        val statusTitle = TextView(this).apply {
            text = "NO TIMER SET"
            textSize = 10f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.rgb(190, 174, 255))
        }
        val statusDetail = TextView(this).apply {
            text = "Choose when Nova should pause your music."
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        }
        val selectedCaption = TextView(this).apply {
            text = "Works while Nova plays in the background."
            textSize = 11f
            setTextColor(Color.rgb(164, 166, 190))
            setPadding(0, dp(5), 0, 0)
        }
        statusTextContainer.addView(statusTitle)
        statusTextContainer.addView(statusDetail)
        statusTextContainer.addView(selectedCaption)
        statusCard.addView(statusTextContainer)
        sheet.addView(statusCard)

        val sectionLabel = TextView(this).apply {
            text = "AUTO-PAUSE AFTER"
            textSize = 10f
            letterSpacing = 0.16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.rgb(151, 145, 190))
        }
        sheet.addView(sectionLabel)

        val sectionTitle = TextView(this).apply {
            text = "Choose a duration"
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, dp(13))
        }
        sheet.addView(sectionTitle)

        val durations = intArrayOf(5, 10, 15, 20, 30, 45, 60, 90)
        sleepTimerPresetButtons.clear()
        durations.toList().chunked(4).forEachIndexed { rowIndex, rowMinutes ->
            val optionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = if (rowIndex == 1) dp(8) else dp(8) }
            }

            rowMinutes.forEachIndexed { columnIndex, minutes ->
                val preset = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                        marginStart = if (columnIndex == 0) 0 else dp(4)
                        marginEnd = if (columnIndex == rowMinutes.lastIndex) 0 else dp(4)
                    }
                    gravity = Gravity.CENTER
                    text = "$minutes min"
                    textSize = 12f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Color.rgb(211, 211, 230))
                    background = sleepTimerPresetBackground(false)
                    isClickable = true
                    isFocusable = true
                    contentDescription = "Set sleep timer for $minutes minutes"
                    setOnClickListener {
                        sendSleepTimerCommand(
                            action = SleepTimerCommands.SET_TIMER,
                            minutes = minutes,
                            successMessage = "Sleep timer set for $minutes minutes"
                        )
                    }
                }
                sleepTimerPresetButtons[minutes] = preset
                optionRow.addView(preset)
            }
            sheet.addView(optionRow)
        }

        val cancelButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(12) }
            gravity = Gravity.CENTER
            text = "CANCEL ACTIVE TIMER"
            textSize = 11f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.rgb(238, 202, 215))
            background = roundedBackground(Color.rgb(47, 29, 47), 16, Color.rgb(104, 58, 83))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isEnabled) {
                    sendSleepTimerCommand(
                        action = SleepTimerCommands.CANCEL_TIMER,
                        minutes = null,
                        successMessage = "Sleep timer cancelled"
                    )
                }
            }
        }
        sheet.addView(cancelButton)

        val footer = TextView(this).apply {
            text = "NOVA  •  Your timer stays active while music plays in the background."
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(119, 123, 151))
            setPadding(dp(6), dp(14), dp(6), 0)
        }
        sheet.addView(footer)

        sleepTimerDialog = dialog
        sleepTimerStatusTitle = statusTitle
        sleepTimerStatusDetail = statusDetail
        sleepTimerSelectedCaption = selectedCaption
        sleepTimerCancelButton = cancelButton

        dialog.setContentView(sheet)
        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(sleepTimerUiRunnable)
            sleepTimerDialog = null
            sleepTimerStatusTitle = null
            sleepTimerStatusDetail = null
            sleepTimerSelectedCaption = null
            sleepTimerCancelButton = null
            sleepTimerPresetButtons.clear()
        }
        dialog.setCancelable(true)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = window.attributes
            params.dimAmount = 0.72f
            window.attributes = params
            window.navigationBarColor = Color.rgb(9, 11, 22)
        }

        refreshSleepTimerUi()
        mainHandler.removeCallbacks(sleepTimerUiRunnable)
        mainHandler.post(sleepTimerUiRunnable)
    }

    private val sleepTimerUiRunnable = object : Runnable {
        override fun run() {
            if (sleepTimerDialog?.isShowing != true) return
            refreshSleepTimerUi()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    private fun sleepTimerPresetBackground(selected: Boolean): GradientDrawable {
        return if (selected) {
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(139, 108, 255), Color.rgb(91, 62, 190))
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(207, 193, 255))
            }
        } else {
            roundedBackground(Color.rgb(28, 29, 49), 16, Color.rgb(56, 55, 83))
        }
    }

    private fun refreshSleepTimerUi() {
        val endTime = sleepTimerPreferences.getLong(SleepTimerCommands.END_TIME_KEY, 0L)
        val remainingMs = endTime - System.currentTimeMillis()
        val storedMinutes = sleepTimerPreferences.getInt(SleepTimerCommands.DURATION_KEY, 0)
        val active = endTime > 0L && remainingMs > 0L

        if (endTime > 0L && remainingMs <= 0L) {
            sleepTimerPreferences.edit()
                .remove(SleepTimerCommands.END_TIME_KEY)
                .remove(SleepTimerCommands.DURATION_KEY)
                .apply()
        }

        sleepTimerStatusTitle?.text = if (active) "TIMER IS ACTIVE" else "NO TIMER SET"
        sleepTimerStatusDetail?.text = if (active) {
            "Music pauses in ${formatTimerCountdown(remainingMs)}"
        } else {
            "Choose when Nova should pause your music."
        }
        sleepTimerSelectedCaption?.text = if (active) {
            "$storedMinutes-minute timer selected · works in the background"
        } else {
            "Works while Nova plays in the background."
        }

        sleepTimerPresetButtons.forEach { (minutes, button) ->
            val selected = active && minutes == storedMinutes
            button.background = sleepTimerPresetBackground(selected)
            button.setTextColor(
                if (selected) Color.WHITE else Color.rgb(211, 211, 230)
            )
            button.text = if (selected) "✓  $minutes min" else "$minutes min"
        }

        sleepTimerCancelButton?.apply {
            isEnabled = active
            alpha = if (active) 1f else 0.48f
            text = if (active) "CANCEL ACTIVE TIMER" else "NO ACTIVE TIMER TO CANCEL"
        }
        updateSleepTimerHeaderButton()
    }

    private fun updateSleepTimerHeaderButton() {
        val button = nowPlayingSleepTimerButton ?: return
        val endTime = sleepTimerPreferences.getLong(SleepTimerCommands.END_TIME_KEY, 0L)
        val remainingMs = endTime - System.currentTimeMillis()
        val duration = sleepTimerPreferences.getInt(SleepTimerCommands.DURATION_KEY, 0)
        if (endTime > 0L && remainingMs <= 0L) {
            sleepTimerPreferences.edit()
                .remove(SleepTimerCommands.END_TIME_KEY)
                .remove(SleepTimerCommands.DURATION_KEY)
                .apply()
        }
        if (endTime > 0L && remainingMs > 0L) {
            val minutesLeft = ((remainingMs + 59_999L) / 60_000L).toInt()
            button.text = "${minutesLeft}m"
            button.textSize = 12f
            button.background = roundedBackground(
                Color.rgb(111, 82, 205), 16, Color.rgb(178, 157, 255)
            )
            button.contentDescription =
                "Sleep timer active. $duration minute timer, ${formatTimerCountdown(remainingMs)} remaining. Tap to change."
        } else {
            button.text = "☾"
            button.textSize = 24f
            button.background = roundedBackground(Color.rgb(91, 70, 178), 16)
            button.contentDescription = "Set sleep timer"
        }
    }

    private fun formatTimerCountdown(remainingMs: Long): String {
        val totalSeconds = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L)
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun sendSleepTimerCommand(
        action: String,
        minutes: Int?,
        successMessage: String
    ) {
        if (!::player.isInitialized) {
            Toast.makeText(this, "Player is still connecting. Try again in a moment.", Toast.LENGTH_LONG).show()
            Log.w("NovaMusicSleepTimer", "Command not sent: MediaController is not initialized")
            return
        }

        val pendingMessage = if (action == SleepTimerCommands.SET_TIMER) {
            "Setting sleep timer..."
        } else {
            "Cancelling sleep timer..."
        }
        Toast.makeText(this, pendingMessage, Toast.LENGTH_SHORT).show()

        val extras = Bundle().apply {
            if (minutes != null) putInt(SleepTimerCommands.MINUTES_KEY, minutes)
        }
        val command = SessionCommand(action, Bundle.EMPTY)

        try {
            Log.d("NovaMusicSleepTimer", "Sending command=$action, minutes=$minutes")
            val future = player.sendCustomCommand(command, extras)
            future.addListener({
                if (isFinishing || isDestroyed) return@addListener
                try {
                    val result = future.get()
                    Log.d(
                        "NovaMusicSleepTimer",
                        "Command completed: action=$action, resultCode=${result.resultCode}"
                    )
                    if (result.resultCode == SessionResult.RESULT_SUCCESS) {
                        if (action == SleepTimerCommands.SET_TIMER && minutes != null) {
                            sleepTimerPreferences.edit()
                                .putLong(
                                    SleepTimerCommands.END_TIME_KEY,
                                    System.currentTimeMillis() + minutes * 60_000L
                                )
                                .putInt(SleepTimerCommands.DURATION_KEY, minutes)
                                .apply()
                        } else if (action == SleepTimerCommands.CANCEL_TIMER) {
                            sleepTimerPreferences.edit()
                                .remove(SleepTimerCommands.END_TIME_KEY)
                                .remove(SleepTimerCommands.DURATION_KEY)
                                .apply()
                        }
                        refreshSleepTimerUi()
                        Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this,
                            "Sleep timer command rejected (${result.resultCode}). Check PlaybackService.kt.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("NovaMusicSleepTimer", "Sleep timer command failed", e)
                    Toast.makeText(
                        this,
                        "Sleep timer request failed. Check Logcat.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("NovaMusicSleepTimer", "Could not send sleep timer command", e)
            Toast.makeText(
                this,
                "Could not send sleep timer command. Check PlaybackService.kt.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateNowPlayingSong(song: SongInfo) {
        nowPlayingTitle?.text = song.title
        nowPlayingArtist?.text = song.artist
        nowPlayingSkinView?.let { skinView ->
            loadAlbumArtworkForSkin(song, skinView)
        }
        nowPlayingDuration?.text = formatDuration(getCurrentDuration())
    }

    private fun updateNowPlayingControls() {
        nowPlayingPlayButton?.text = if (player.isPlaying) "Ⅱ" else "▶"
        nowPlayingShuffleButton?.setTextColor(
            if (player.shuffleModeEnabled) Color.rgb(190, 174, 255) else Color.rgb(147, 151, 173)
        )
        nowPlayingRepeatButton?.setTextColor(
            if (player.repeatMode == Player.REPEAT_MODE_OFF) Color.rgb(147, 151, 173)
            else Color.rgb(190, 174, 255)
        )
    }

    private fun createPlayerControl(symbol: String, sizeDp: Int, primary: Boolean): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = if (primary) 28f else 21f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.rgb(30, 22, 58) else Color.rgb(190, 183, 222))
            background = roundedBackground(
                if (primary) accentLightColor() else Color.rgb(31, 30, 50),
                if (primary) 35 else 18,
                if (primary) Color.WHITE else Color.rgb(50, 47, 75)
            )
            elevation = dp(if (primary) 9 else 2).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = when (symbol) {
                "Ⅱ", "▶" -> "Play or pause"
                "⏮" -> "Previous song"
                "⏭" -> "Next song"
                "⤨" -> "Shuffle"
                else -> "Repeat mode"
            }
        }
    }

    private fun controlParams(widthDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(widthDp), dp(widthDp)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    private fun updateMiniPlayer(song: SongInfo) {
        findViewById<TextView>(R.id.miniPlayerTitle).text = song.title
        findViewById<TextView>(R.id.miniPlayerArtist).text = song.artist
        updatePlayButton(player.isPlaying)
    }

    private fun updatePlayButton(isPlaying: Boolean) {
        findViewById<TextView>(R.id.miniPlayerPlayButton).text = if (isPlaying) "Ⅱ" else "▶"
        updateNowPlayingControls()
    }

    private fun getCurrentDuration(): Long {
        val playerDuration = player.duration
        if (playerDuration > 0L) return playerDuration
        return songForMediaItem(player.currentMediaItem, player.currentMediaItemIndex)?.duration?.coerceAtLeast(0L) ?: 0L
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        settingsDialog?.dismiss()
        equalizerDialog?.dismiss()
        playlistsDialog?.dismiss()
        sleepTimerDialog?.dismiss()
        mainHandler.removeCallbacks(seekUpdateRunnable)
        mainHandler.removeCallbacks(sleepTimerUiRunnable)
        pendingSearch?.let { mainHandler.removeCallbacks(it) }
        pendingSearch = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        artworkExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** Lightweight vertical frequency slider; redraws only in response to touch/state changes. */
private class VerticalEqualizerSlider(context: android.content.Context) : View(context) {
    private var minValue = -1500
    private var maxValue = 1500
    private var currentValue = 0

    var onLevelChanged: ((Int) -> Unit)? = null
    var onLevelCommitted: ((Int) -> Unit)? = null

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(66, 61, 92)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(178, 151, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val thumbOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(166, 138, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(119, 104, 166)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    fun setRange(minimum: Int, maximum: Int, initialValue: Int) {
        minValue = minimum
        maxValue = maximum.coerceAtLeast(minimum + 1)
        currentValue = initialValue.coerceIn(minValue, maxValue)
        invalidate()
    }

    private fun updateValueFromTouch(y: Float) {
        val top = dp(10f)
        val bottom = height.toFloat() - dp(10f)
        if (bottom <= top) return
        val ratio = ((bottom - y) / (bottom - top)).coerceIn(0f, 1f)
        currentValue = (minValue + ratio * (maxValue - minValue) + 0.5f)
            .toInt().coerceIn(minValue, maxValue)
        invalidate()
        onLevelChanged?.invoke(currentValue)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val top = dp(10f)
        val bottom = height.toFloat() - dp(10f)
        if (bottom <= top) return

        val range = (maxValue - minValue).toFloat()
        val ratio = (currentValue - minValue) / range
        val thumbY = bottom - ratio * (bottom - top)
        val zeroRatio = (0f - minValue) / range
        val zeroY = bottom - zeroRatio * (bottom - top)

        canvas.drawLine(centerX, top, centerX, bottom, trackPaint)
        canvas.drawLine(centerX - dp(8f), zeroY, centerX + dp(8f), zeroY, zeroPaint)
        canvas.drawLine(centerX, zeroY, centerX, thumbY, activePaint)
        canvas.drawCircle(centerX, thumbY, dp(7f), thumbPaint)
        canvas.drawCircle(centerX, thumbY, dp(9f), thumbOutlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateValueFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateValueFromTouch(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateValueFromTouch(event.y)
                onLevelCommitted?.invoke(currentValue)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onLevelCommitted?.invoke(currentValue)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

