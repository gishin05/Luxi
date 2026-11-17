package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource.IllegalMergeException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.source.VideoSource
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val CONTROLS_TIMEOUT_MS = 3_500L
private const val POSITION_UPDATE_MS = 500L
private const val NO_AUDIO_TRACK = -1
private val SEEK_THUMB_SIZE = 14.dp

/** The panels the player can open from the control bar. */
private enum class PlayerPanel { QUALITY, AUDIO, SUBTITLES }

@Composable
fun PlayerScreen(
    mangaId: Long = -1L,
    chapterId: Long,
    mangaTitle: String,
    chapterTitle: String,
    onBackClick: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    // Share app cookies, User-Agent, Conscrypt TLS, DoH, and Cloudflare clearance with ExoPlayer
    // without letting large media range reads pollute the small API disk cache.
    val playerHttpClient = remember(context) {
        context.appGraph.networkHelper.client.newBuilder()
            .cache(null)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val player = remember {
        ExoPlayer.Builder(context)
            // Buffer ahead so that seeking to an already buffered position resumes right away.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 120_000, 1_500, 3_000)
                    .build(),
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    var source by remember { mutableStateOf<VideoSource?>(null) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var selectedVideoIndex by remember { mutableIntStateOf(0) }
    var currentVideo by remember { mutableStateOf<Video?>(null) }
    var externalAudioTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var selectedExternalAudioIndex by remember { mutableIntStateOf(NO_AUDIO_TRACK) }
    var mergeAudioFailed by remember { mutableStateOf(false) }
    var forcedHls by remember { mutableStateOf<Boolean?>(null) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var isFullscreen by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(1f) }
    var subtitleScale by remember { mutableFloatStateOf(1f) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var subtitleView by remember { mutableStateOf<SubtitleView?>(null) }
    var audioSelection by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var subtitleSelection by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
                audioSelection = newTracks.audioOptions()
                    .firstOrNull { it.selected }
                    ?.let { it.groupIndex to it.trackIndex }
                subtitleSelection = newTracks.subtitleOptions()
                    .firstOrNull { it.selected }
                    ?.let { it.groupIndex to it.trackIndex }
                logcat(priority = LogPriority.INFO) { "Player tracks: ${newTracks.describe()}" }
            }

            override fun onPlayerError(error: PlaybackException) {
                // The separate audio rendition can be incompatible with the video one; retry without it.
                if (error.hasCause<IllegalMergeException>()) {
                    mergeAudioFailed = true
                    return
                }
                // A playlist can hide behind a URL without an extension, so let the other parser
                // try once before giving up.
                if (error.hasCause<UnrecognizedInputFormatException>() || error.hasCause<ParserException>()) {
                    val guessedHls = currentVideo?.isHlsStream(forcedHls = null) ?: false
                    if (forcedHls == null) {
                        forcedHls = !guessedHls
                        errorMessage = null
                        return
                    }
                }
                // If there are other candidate video streams, try the next one before giving up.
                if (selectedVideoIndex + 1 < videos.size) {
                    selectedVideoIndex += 1
                    errorMessage = null
                    return
                }
                errorMessage = error.localizedMessage ?: "Playback error"
                isBuffering = false
                logcat(LogPriority.ERROR, error) { "Playback failed for ${currentVideo?.videoUrl}" }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(source) {
        val activeSource = source
        onDispose {
            activeSource?.release()
        }
    }

    LaunchedEffect(subtitleScale, subtitleView) {
        subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subtitleScale)
    }

    // Load the streams of the chapter.
    LaunchedEffect(chapterId, mangaId) {
        val videoSource = findVideoSource(context, chapterId, mangaId)
        source = videoSource
        if (videoSource == null) {
            errorMessage = "Source not available for this chapter"
            isBuffering = false
            return@LaunchedEffect
        }

        val chapter = chapterToSChapter(context, chapterId, mangaId)
        val streamsResult = withContext(Dispatchers.IO) {
            runCatching { videoSource.getVideos(chapter) }
                .onFailure { e -> logcat(LogPriority.ERROR, e) { "Failed to get videos for chapter $chapterId" } }
        }
        val streams = streamsResult.getOrNull().orEmpty()
        videos = streams
        if (streams.isEmpty()) {
            errorMessage = streamsResult.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "No streams available for this chapter"
            isBuffering = false
        }
    }

    // Resolve the selected stream.
    LaunchedEffect(selectedVideoIndex, videos, source) {
        val videoSource = source ?: return@LaunchedEffect
        val video = videos.getOrNull(selectedVideoIndex) ?: return@LaunchedEffect
        val resolveResult = withContext(Dispatchers.IO) {
            runCatching { videoSource.resolveVideo(video) }
                .onFailure { e -> logcat(LogPriority.ERROR, e) { "Failed to resolve video ${video.videoTitle}" } }
        }
        val resolved = if (resolveResult.isSuccess) {
            (resolveResult.getOrNull() ?: video.takeIf { it.initialized })
                ?.takeIf { it.videoUrl.isNotBlank() }
        } else {
            null
        }
        if (resolved == null) {
            if (selectedVideoIndex + 1 < videos.size) {
                selectedVideoIndex += 1
                errorMessage = null
            } else {
                errorMessage = resolveResult.exceptionOrNull()?.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "No playable stream URL available"
                isBuffering = false
            }
            return@LaunchedEffect
        }

        errorMessage = null
        position = 0
        isBuffering = true
        currentVideo = resolved
        externalAudioTracks = resolved.audioTracks
        selectedExternalAudioIndex = if (resolved.audioTracks.isEmpty()) NO_AUDIO_TRACK else 0
        mergeAudioFailed = false
        forcedHls = null
    }

    // Play the current stream, merging the separate audio rendition when the extension provides one.
    LaunchedEffect(currentVideo, selectedExternalAudioIndex, mergeAudioFailed, forcedHls, playerHttpClient) {
        val video = currentVideo ?: return@LaunchedEffect
        val mediaSource = withContext(Dispatchers.IO) {
            buildMediaSource(video, selectedExternalAudioIndex, mergeAudioFailed, forcedHls, playerHttpClient)
        }
        val resumePosition = player.currentPosition
        player.setMediaSource(mediaSource)
        player.prepare()
        if (resumePosition > 0) {
            player.seekTo(resumePosition)
        }
        player.playWhenReady = true
    }

    // Keep the position label in sync with the playback.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            delay(POSITION_UPDATE_MS)
        }
    }

    // Hide the controls again once playback is running.
    LaunchedEffect(controlsVisible, isPlaying, panel) {
        if (controlsVisible && isPlaying && panel == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { controlsVisible = !controlsVisible }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    subtitleView = this.subtitleView
                }
            },
            update = { view ->
                view.player = player
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isBuffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = Color.Red,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (panel != null) {
            // Scrim below the control bar so the bar stays usable while a panel is open.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 72.dp)
                    .background(Color.Black.copy(alpha = 0.2f))
                    .clickable { panel = null },
            )
        }

        if (controlsVisible) {
            PlayerTopBar(
                title = listOf(mangaTitle, chapterTitle).filter { it.isNotBlank() }.joinToString(" • "),
                onBackClick = onBackClick,
                modifier = Modifier.align(Alignment.TopStart),
            )

            PlayerBottomBar(
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                qualityLabel = videos.getOrNull(selectedVideoIndex)?.displayLabel(),
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onSeek = {
                    isBuffering = true
                    player.seekTo(it)
                },
                onQualityClick = { panel = PlayerPanel.QUALITY },
                onAudioClick = { panel = PlayerPanel.AUDIO },
                onSubtitlesClick = { panel = PlayerPanel.SUBTITLES },
                onFullscreenClick = {
                    isFullscreen = !isFullscreen
                    onFullscreenChange(isFullscreen)
                },
                isFullscreen = isFullscreen,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        PlayerOverlayPanel(
            panel = panel,
            videos = videos,
            selectedVideoIndex = selectedVideoIndex,
            tracks = tracks,
            volume = volume,
            subtitleScale = subtitleScale,
            audioSelection = audioSelection,
            subtitleSelection = subtitleSelection,
            externalAudioTracks = externalAudioTracks,
            selectedExternalAudioIndex = selectedExternalAudioIndex,
            onVideoSelected = { index ->
                selectedVideoIndex = index
                panel = null
            },
            onVolumeChange = {
                volume = it
                player.volume = it
            },
            onSubtitleScaleChange = { subtitleScale = it },
            onAudioTrackSelected = { groupIndex, trackIndex ->
                audioSelection = trackIndex?.let { groupIndex to it }
                player.selectAudioTrack(tracks, groupIndex, trackIndex)
            },
            onExternalAudioSelected = { index ->
                selectedExternalAudioIndex = index
            },
            onSubtitleTrackSelected = { groupIndex, trackIndex ->
                subtitleSelection = trackIndex?.let { groupIndex to it }
                player.selectSubtitleTrack(tracks, groupIndex, trackIndex)
            },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                    contentDescription = stringResource(MR.strings.action_bar_up_description),
                    tint = Color.White,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlayerBottomBar(
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    qualityLabel: String?,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onQualityClick: () -> Unit,
    onAudioClick: () -> Unit,
    onSubtitlesClick: () -> Unit,
    onFullscreenClick: () -> Unit,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) {
                            androidx.media3.ui.R.drawable.exo_icon_pause
                        } else {
                            androidx.media3.ui.R.drawable.exo_icon_play
                        },
                    ),
                    contentDescription = null,
                    tint = Color.White,
                )
            }

            Text(
                text = position.toTimeString(),
                color = Color.White,
                fontSize = 12.sp,
            )

            PlayerSeekSlider(
                position = position,
                duration = duration,
                onSeek = onSeek,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )

            Text(
                text = duration.toTimeString(),
                color = Color.White,
                fontSize = 12.sp,
            )

            qualityLabel?.let { label ->
                TextButton(onClick = onQualityClick) {
                    Text(
                        text = label,
                        color = Color.White,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }

            IconButton(onClick = onAudioClick) {
                Icon(
                    painter = painterResource(androidx.media3.ui.R.drawable.exo_ic_audiotrack),
                    contentDescription = stringResource(MR.strings.player_audio),
                    tint = Color.White,
                )
            }

            IconButton(onClick = onSubtitlesClick) {
                Icon(
                    painter = painterResource(androidx.media3.ui.R.drawable.exo_ic_subtitle_on),
                    contentDescription = stringResource(MR.strings.player_subtitles),
                    tint = Color.White,
                )
            }

            IconButton(onClick = onFullscreenClick) {
                Icon(
                    painter = painterResource(
                        if (isFullscreen) {
                            androidx.media3.ui.R.drawable.exo_ic_fullscreen_exit
                        } else {
                            androidx.media3.ui.R.drawable.exo_ic_fullscreen_enter
                        },
                    ),
                    contentDescription = stringResource(MR.strings.player_fullscreen),
                    tint = Color.White,
                )
            }
        }
    }
}

/** Thin progress bar with a dot marking the current position. */
@Composable
private fun PlayerSeekSlider(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draggedPosition by remember { mutableStateOf<Long?>(null) }
    val sliderState = key(duration) {
        rememberSliderState(0f, 0, 0f..duration.coerceAtLeast(1L).toFloat())
    }
    LaunchedEffect(position, draggedPosition, duration) {
        if (draggedPosition == null) {
            sliderState.value = position.toFloat()
        }
    }

    Slider(
        state = sliderState,
        onValueChange = { draggedPosition = it.toLong() },
        onValueChangeFinished = {
            draggedPosition?.let(onSeek)
            draggedPosition = null
        },
        track = { state -> SeekTrack(state) },
        thumb = { SeekThumb() },
        modifier = modifier,
    )
}

@Composable
private fun SeekTrack(state: SliderState) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
    ) {
        val playedWidth = (maxWidth - SEEK_THUMB_SIZE) * state.coercedValueAsFraction
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
        )
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(playedWidth.coerceAtLeast(0.dp))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun SeekThumb() {
    Box(
        modifier = Modifier
            .size(SEEK_THUMB_SIZE)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

/** Small translucent panel shown above the control bar. */
@Composable
private fun PlayerOverlayPanel(
    panel: PlayerPanel?,
    videos: List<Video>,
    selectedVideoIndex: Int,
    tracks: Tracks,
    volume: Float,
    subtitleScale: Float,
    audioSelection: Pair<Int, Int>?,
    subtitleSelection: Pair<Int, Int>?,
    externalAudioTracks: List<Track>,
    selectedExternalAudioIndex: Int,
    onVideoSelected: (Int) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSubtitleScaleChange: (Float) -> Unit,
    onAudioTrackSelected: (Int, Int?) -> Unit,
    onExternalAudioSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel == null) return

    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .padding(end = 12.dp, bottom = 72.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        when (panel) {
            PlayerPanel.QUALITY -> {
                PanelTitle(stringResource(MR.strings.player_quality))
                if (videos.isEmpty()) {
                    PanelMessage(stringResource(MR.strings.player_no_streams))
                }
                videos.forEachIndexed { index, video ->
                    PlayerOptionRow(
                        title = video.displayLabel(),
                        selected = index == selectedVideoIndex,
                        onClick = { onVideoSelected(index) },
                    )
                }
            }

            PlayerPanel.AUDIO -> {
                PanelTitle(stringResource(MR.strings.player_audio))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.player_volume),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val volumeState = rememberSliderState(volume, 0, 0f..1f)
                    LaunchedEffect(volume) { volumeState.value = volume }
                    Slider(
                        state = volumeState,
                        onValueChange = onVolumeChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "${(volume * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                val audioTracks = tracks.audioOptions()
                PlayerOptionRow(
                    title = stringResource(MR.strings.player_default),
                    selected = audioSelection == null,
                    onClick = { onAudioTrackSelected(-1, null) },
                )
                audioTracks.forEach { track ->
                    PlayerOptionRow(
                        title = track.label,
                        subtitle = track.detail,
                        selected = audioSelection == (track.groupIndex to track.trackIndex),
                        onClick = { onAudioTrackSelected(track.groupIndex, track.trackIndex) },
                    )
                }
                if (externalAudioTracks.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                    externalAudioTracks.forEachIndexed { index, track ->
                        PlayerOptionRow(
                            title = track.lang,
                            subtitle = null,
                            selected = index == selectedExternalAudioIndex,
                            onClick = { onExternalAudioSelected(index) },
                        )
                    }
                }
                if (audioTracks.isEmpty() && externalAudioTracks.isEmpty()) {
                    PanelMessage(stringResource(MR.strings.player_no_audio_track))
                }
            }

            PlayerPanel.SUBTITLES -> {
                PanelTitle(stringResource(MR.strings.player_subtitles))
                val subtitleTracks = tracks.subtitleOptions()
                PlayerOptionRow(
                    title = stringResource(MR.strings.player_subtitles_off),
                    selected = subtitleSelection == null,
                    onClick = { onSubtitleTrackSelected(-1, null) },
                )
                subtitleTracks.forEach { track ->
                    PlayerOptionRow(
                        title = track.label,
                        subtitle = track.detail,
                        selected = subtitleSelection == (track.groupIndex to track.trackIndex),
                        onClick = { onSubtitleTrackSelected(track.groupIndex, track.trackIndex) },
                    )
                }
                if (subtitleTracks.isEmpty()) {
                    PanelMessage(stringResource(MR.strings.player_no_subtitle_track))
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(MR.strings.player_subtitle_size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val subtitleSizeState = rememberSliderState(subtitleScale, 0, 0.5f..2f)
                    LaunchedEffect(subtitleScale) { subtitleSizeState.value = subtitleScale }
                    Slider(
                        state = subtitleSizeState,
                        onValueChange = onSubtitleScaleChange,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = "${(subtitleScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PanelMessage(message: String) {
    Text(
        text = message,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PlayerOptionRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = Color.White.copy(alpha = 0.6f),
            ),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class TrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val detail: String?,
    val selected: Boolean,
)

private fun Tracks.describe(): String {
    return groups.joinToString { group ->
        val type = when (group.type) {
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_TEXT -> "text"
            else -> "type${group.type}"
        }
        val formats = (0 until group.length).joinToString { index ->
            val format = group.getTrackFormat(index)
            "${format.sampleMimeType}/${format.language}/selected=${group.isTrackSelected(index)}"
        }
        "$type[$formats]"
    }
}

private fun Tracks.audioOptions(): List<TrackOption> = options(C.TRACK_TYPE_AUDIO, isAudio = true)

private fun Tracks.subtitleOptions(): List<TrackOption> = options(C.TRACK_TYPE_TEXT, isAudio = false)

private fun Tracks.options(trackType: Int, isAudio: Boolean): List<TrackOption> {
    var unlabeledCount = 0
    return groups.flatMapIndexed { groupIndex, group ->
        if (group.type != trackType) return@flatMapIndexed emptyList()
        (0 until group.length).mapNotNull { trackIndex ->
            val selected = group.isTrackSelected(trackIndex)
            if (!selected && !group.isTrackSupported(trackIndex)) return@mapNotNull null
            val format = group.getTrackFormat(trackIndex)
            // ExoPlayer's HLS/TS extractor injects phantom unlabeled cea-608/cea-708 tracks; skip them unless labeled.
            if (!isAudio && format.isUnlabeledClosedCaption()) return@mapNotNull null
            val label = format.trackLabel(
                isAudio = isAudio,
                fallbackIndex = { ++unlabeledCount },
            )
            TrackOption(
                groupIndex = groupIndex,
                trackIndex = trackIndex,
                label = label,
                detail = format.trackDetail(isAudio),
                selected = selected,
            )
        }
    }
}

internal fun Format.isUnlabeledClosedCaption(): Boolean =
    isUnlabeledClosedCaption(sampleMimeType, label, language)

internal fun isUnlabeledClosedCaption(
    sampleMimeType: String?,
    label: String?,
    language: String?,
): Boolean {
    val isClosedCaption = sampleMimeType == MimeTypes.APPLICATION_CEA608 ||
        sampleMimeType == MimeTypes.APPLICATION_CEA708
    if (!isClosedCaption) return false
    val hasMeaningfulLabel = label?.trim()?.let { raw ->
        raw.isNotEmpty() &&
            !raw.equals("default", ignoreCase = true) &&
            !raw.equals("und", ignoreCase = true) &&
            !raw.equals("unk", ignoreCase = true) &&
            !raw.equals("cea-608", ignoreCase = true) &&
            !raw.equals("cea-708", ignoreCase = true)
    } ?: false
    val hasLanguage = language?.trim()?.let { tag ->
        tag.isNotEmpty() && !tag.equals("und", ignoreCase = true) && !tag.equals("unk", ignoreCase = true)
    } ?: false
    return !hasMeaningfulLabel && !hasLanguage
}

internal inline fun Format.trackLabel(isAudio: Boolean, fallbackIndex: () -> Int): String =
    resolveTrackLabel(label, language, isAudio, fallbackIndex)

internal inline fun resolveTrackLabel(
    label: String?,
    language: String?,
    isAudio: Boolean,
    fallbackIndex: () -> Int,
): String {
    val formatLabel = label?.trim()?.takeIf {
        it.isNotEmpty() && !it.equals("und", ignoreCase = true) && !it.equals("unk", ignoreCase = true)
    }
    if (formatLabel != null) return formatLabel

    val readableLanguage = language?.let(::resolveReadableLanguageOrNull)
    if (readableLanguage != null) return readableLanguage

    val index = fallbackIndex()
    return if (isAudio) "Audio $index" else "Subtitle $index"
}

private fun Format.trackDetail(isAudio: Boolean): String? {
    val parts = buildList {
        if (isAudio) {
            when (channelCount) {
                1 -> add("Mono")
                2 -> add("Stereo")
                6 -> add("5.1 surround")
                8 -> add("7.1 surround")
                else -> if (channelCount > 0) add("$channelCount channels")
            }
        }
        friendlyTrackMime(sampleMimeType)?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

internal fun friendlyTrackMime(mimeType: String?): String? {
    return when (mimeType) {
        null -> null
        MimeTypes.APPLICATION_CEA608 -> "CEA-608"
        MimeTypes.APPLICATION_CEA708 -> "CEA-708"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS/SSA"
        MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_MP4VTT -> "VTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        else -> mimeType.substringAfter('/')
    }
}

private fun Video.displayLabel(): String {
    return when {
        videoTitle.isNotBlank() -> videoTitle
        resolution != null -> "${resolution}p"
        else -> "Stream"
    }
}

private fun Video.requestHeaders(): Map<String, String> {
    return headers?.toMultimap()?.mapValues { (_, values) -> values.joinToString(", ") }.orEmpty()
}

internal fun String.looksLikeDash(): Boolean {
    val httpUrl = toHttpUrlOrNull()
    val path = httpUrl?.encodedPath ?: substringBefore('?').substringBefore('#')
    val typeParam = httpUrl?.queryParameter("type")
    val formatParam = httpUrl?.queryParameter("format")
    return path.endsWith(".mpd", ignoreCase = true) ||
        contains(".mpd", ignoreCase = true) ||
        typeParam.equals("dash", ignoreCase = true) ||
        formatParam.equals("dash", ignoreCase = true)
}

/**
 * Whether the URL points at an HLS playlist. Local proxy endpoints such as AnimePahe's `/mp4`
 * and DASH manifests must stay excluded so container sniffers or DASH factories handle them.
 */
internal fun String.looksLikeHls(): Boolean {
    if (looksLikeDash()) return false
    if (contains("googleusercontent.com", ignoreCase = true)) return false
    if (contains(".mkv", ignoreCase = true) ||
        contains(".mp4", ignoreCase = true) ||
        contains(".webm", ignoreCase = true)
    ) {
        return false
    }
    if (contains("m3u8", ignoreCase = true) || contains(".m3u", ignoreCase = true)) return true
    val path = toHttpUrlOrNull()?.encodedPath ?: substringBefore('?').substringBefore('#')
    val lastSegment = path.substringAfterLast('/').lowercase(Locale.ROOT)
    return lastSegment == "hls" || lastSegment == "master" || lastSegment == "playlist"
}

private fun Video.isDirectContainerHint(): Boolean {
    return videoUrl.contains("googleusercontent.com", ignoreCase = true) ||
        videoTitle.contains("mkv", ignoreCase = true) ||
        videoTitle.contains("mp4", ignoreCase = true) ||
        videoTitle.contains("webm", ignoreCase = true)
}

internal fun Video.isHlsStream(forcedHls: Boolean?): Boolean {
    return forcedHls ?: (
        !isDirectContainerHint() && !videoUrl.looksLikeDash() &&
            (videoUrl.looksLikeHls() || videoTitle.contains("hls", ignoreCase = true))
        )
}

/**
 * Builds the media source of a stream.
 *
 * Extensions can serve the audio as a separate rendition next to a video-only variant, which is
 * what hosts with multiple audio languages do. Such a stream only gets sound when the audio
 * rendition is merged into the video one, because the player then sees them as one source.
 */
private fun buildMediaSource(
    video: Video,
    audioIndex: Int,
    skipAudioMerge: Boolean,
    forcedHls: Boolean?,
    httpClient: OkHttpClient,
): MediaSource {
    val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
        .setDefaultRequestProperties(video.requestHeaders())
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    val videoSource = mediaSourceFactory.createMediaSource(video.toMediaItem(forcedHls))

    val audioTrack = video.audioTracks.getOrNull(audioIndex)
    if (audioTrack == null || skipAudioMerge) return videoSource

    val audioItem = MediaItem.Builder().setUri(audioTrack.url).apply {
        // Audio renditions hide their playlist type just as often as the video stream does.
        if (audioTrack.url.looksLikeHls()) {
            setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (audioTrack.url.looksLikeDash()) {
            setMimeType(MimeTypes.APPLICATION_MPD)
        }
    }.build()
    val audioSource = mediaSourceFactory.createMediaSource(audioItem)
    return MergingMediaSource(videoSource, audioSource)
}

private fun Video.toMediaItem(forcedHls: Boolean?): MediaItem {
    val builder = MediaItem.Builder().setUri(videoUrl)
    val hls = isHlsStream(forcedHls)
    val dash = forcedHls == null && !hls &&
        (videoUrl.looksLikeDash() || (!isDirectContainerHint() && videoTitle.contains("dash", ignoreCase = true)))
    when {
        hls -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        dash -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
    }
    val subtitleConfigurations = subtitleTracks.mapIndexed { index, track ->
        val displayLabel = resolveSubtitleConfigLabel(track.lang, index + 1)
        val languageTag = normalizeSubtitleLanguageTag(track.lang)
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
            .setMimeType(track.mimeType())
            .setLabel(displayLabel)
            .apply {
                if (languageTag != null) {
                    setLanguage(languageTag)
                }
            }
            .build()
    }
    if (subtitleConfigurations.isNotEmpty()) {
        builder.setSubtitleConfigurations(subtitleConfigurations)
    }
    return builder.build()
}

private val LANGUAGE_TAG_REGEX = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,4})?$")

internal fun normalizeSubtitleLanguageTag(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || !LANGUAGE_TAG_REGEX.matches(trimmed)) return null
    val normalized = trimmed.replace('_', '-')
    if (normalized.equals("und", ignoreCase = true) || normalized.equals("unk", ignoreCase = true)) {
        return null
    }
    val locale = Locale.forLanguageTag(normalized)
    return locale.language.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }?.let {
        normalized
    }
}

internal fun resolveSubtitleConfigLabel(raw: String, fallbackIndex: Int): String =
    resolveReadableLanguageOrNull(raw) ?: "Subtitle $fallbackIndex"

internal fun resolveReadableLanguageOrNull(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.equals("und", ignoreCase = true) || trimmed.equals("unk", ignoreCase = true)) {
        return null
    }
    val tag = normalizeSubtitleLanguageTag(trimmed)
    if (tag != null) {
        val locale = Locale.forLanguageTag(tag)
        val display = locale.getDisplayName(Locale.getDefault())
            .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) && !it.equals("und", ignoreCase = true) }
        if (display != null) {
            return display.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
    }
    return trimmed
}

internal fun eu.kanade.tachiyomi.animesource.model.Track.mimeType(): String {
    val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?').substringBefore('#')
    return when {
        path.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
        path.endsWith(".srt", ignoreCase = true) -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass", ignoreCase = true) -> MimeTypes.TEXT_SSA
        path.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}

/** Selects the given audio track, or lets the player pick one when [trackIndex] is null. */
private fun ExoPlayer.selectAudioTrack(tracks: Tracks, groupIndex: Int, trackIndex: Int?) {
    val builder = trackSelectionParameters.buildUpon()
    if (trackIndex == null) {
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
    } else {
        val group = tracks.groups.getOrNull(groupIndex)?.mediaTrackGroup ?: return
        builder.setOverrideForType(TrackSelectionOverride(group, trackIndex))
    }
    setTrackSelectionParameters(builder.build())
}

private fun ExoPlayer.selectSubtitleTrack(tracks: Tracks, groupIndex: Int, trackIndex: Int?) {
    val builder = trackSelectionParameters.buildUpon()
    if (trackIndex == null) {
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        val group = tracks.groups.getOrNull(groupIndex)?.mediaTrackGroup ?: return
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
    }
    setTrackSelectionParameters(builder.build())
}

private suspend fun findVideoSource(context: Context, chapterId: Long, mangaId: Long): VideoSource? {
    return withContext(Dispatchers.IO) {
        val graph = context.appGraph
        val chapter = if (chapterId > 0) graph.getChapter.await(chapterId) else null
        val mId = chapter?.mangaId ?: mangaId
        val manga = if (mId > 0) graph.getManga.await(mId) else null
        manga?.let { graph.sourceManager.get(it.source) as? VideoSource }
    }
}

private suspend fun chapterToSChapter(context: Context, chapterId: Long, mangaId: Long): SChapter {
    return withContext(Dispatchers.IO) {
        val graph = context.appGraph
        val chapter = if (chapterId > 0) graph.getChapter.await(chapterId) else null
        val mId = chapter?.mangaId ?: mangaId
        val manga = if (mId > 0) graph.getManga.await(mId) else null
        val sChapter = chapter?.toSChapter() ?: SChapter.create().apply {
            chapter_number = 1f
            name = manga?.title ?: "Movie"
        }
        if (sChapter.url.isBlank() && manga != null && manga.url.isNotBlank()) {
            sChapter.url = manga.url
        }
        sChapter
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return true
        current = current.cause
    }
    return false
}

private fun Long.toTimeString(): String {
    val totalSeconds = (this / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
