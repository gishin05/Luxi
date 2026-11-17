package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import mihon.icons.materialsymbols.rounded.Settings
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.presentation.core.components.material.Scaffold

class NovelReaderActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        val mangaTitle = intent.getStringExtra(EXTRA_MANGA_TITLE).orEmpty()
        val chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE) ?: "Chapter 1"

        setContent {
            MaterialTheme {
                NovelReaderContent(
                    mangaId = mangaId,
                    initialChapterId = chapterId,
                    mangaTitle = mangaTitle,
                    initialChapterTitle = chapterTitle,
                    onBackClick = { finish() },
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun NovelReaderContent(
        mangaId: Long,
        initialChapterId: Long,
        mangaTitle: String,
        initialChapterTitle: String,
        onBackClick: () -> Unit,
    ) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
        var currentChapterId by remember { mutableLongStateOf(initialChapterId) }
        var currentChapterTitle by remember { mutableStateOf(initialChapterTitle) }
        var currentIndex by remember { mutableIntStateOf(0) }

        val prefs = remember { context.getSharedPreferences("novel_reader_settings", Context.MODE_PRIVATE) }
        var fontSize by remember {
            mutableFloatStateOf(prefs.getFloat("font_size", 18f))
        }
        var themeMode by remember {
            val savedName = prefs.getString("theme_mode", ThemeMode.DARK.name) ?: ThemeMode.DARK.name
            mutableStateOf(ThemeMode.entries.find { it.name == savedName } ?: ThemeMode.DARK)
        }
        var fontFamilyName by remember {
            mutableStateOf(prefs.getString("font_family", "serif") ?: "serif")
        }
        val fontFamily = when (fontFamilyName) {
            "sans" -> FontFamily.SansSerif
            "mono" -> FontFamily.Monospace
            else -> FontFamily.Serif
        }
        var showSettings by remember { mutableStateOf(false) }

        LaunchedEffect(fontSize) {
            prefs.edit().putFloat("font_size", fontSize).apply()
        }
        LaunchedEffect(themeMode) {
            prefs.edit().putString("theme_mode", themeMode.name).apply()
        }
        LaunchedEffect(fontFamilyName) {
            prefs.edit().putString("font_family", fontFamilyName).apply()
        }

        // Load chapter list
        LaunchedEffect(mangaId) {
            withContext(Dispatchers.IO) {
                try {
                    val list = context.appGraph.getChaptersByMangaId
                        .await(mangaId, applyScanlatorFilter = false)
                        .sortedBy { it.chapterNumber }
                    if (list.isNotEmpty()) {
                        chapters = list
                        val foundIndex = list.indexOfFirst { it.id == currentChapterId }
                        if (foundIndex != -1) {
                            currentIndex = foundIndex
                            currentChapterTitle = list[foundIndex].name
                        }
                    }
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }

        // Mark read whenever chapter changes
        LaunchedEffect(currentChapterId) {
            withContext(Dispatchers.IO) {
                try {
                    if (currentChapterId > 0) {
                        context.appGraph.updateChapter.await(
                            ChapterUpdate(id = currentChapterId) {
                                read = true
                            },
                        )
                    }
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }

        val bgColor = when (themeMode) {
            ThemeMode.DARK -> Color(0xFF1E1E2E)
            ThemeMode.AMOLED -> Color.Black
            ThemeMode.SEPIA -> Color(0xFFFBF0D9)
            ThemeMode.LIGHT -> Color(0xFFFAFAFA)
        }

        val textColor = when (themeMode) {
            ThemeMode.DARK -> Color(0xFFDCD7BA)
            ThemeMode.AMOLED -> Color(0xFFE0E0E0)
            ThemeMode.SEPIA -> Color(0xFF5F4B32)
            ThemeMode.LIGHT -> Color(0xFF2B2B2B)
        }

        var content by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var reloadTrigger by remember { mutableIntStateOf(0) }

        LaunchedEffect(currentChapterId, currentChapterTitle, reloadTrigger) {
            isLoading = true
            errorMessage = null
            try {
                content = NovelChapterLoader.getChapterText(
                    context = context,
                    mangaId = mangaId,
                    chapterId = currentChapterId,
                    mangaTitle = mangaTitle,
                    chapterTitle = currentChapterTitle,
                )
                isLoading = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load chapter"
                isLoading = false
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val displayTitle = if (mangaTitle.isNotBlank()) {
                            "$mangaTitle • $currentChapterTitle"
                        } else {
                            currentChapterTitle
                        }
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                                contentDescription = "Back",
                                tint = textColor,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Settings,
                                contentDescription = "Typography",
                                tint = textColor,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = bgColor,
                    ),
                )
            },
            bottomBar = {
                Surface(
                    color = bgColor,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val hasPrev = currentIndex > 0
                        val hasNext = chapters.isNotEmpty() && currentIndex < chapters.size - 1

                        TextButton(
                            onClick = {
                                if (hasPrev) {
                                    val prevIdx = currentIndex - 1
                                    currentIndex = prevIdx
                                    val ch = chapters[prevIdx]
                                    currentChapterId = ch.id
                                    currentChapterTitle = ch.name
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            },
                            enabled = hasPrev,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                                contentDescription = "Previous Chapter",
                                tint = if (hasPrev) textColor else textColor.copy(alpha = 0.3f),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Prev",
                                color = if (hasPrev) textColor else textColor.copy(alpha = 0.3f),
                            )
                        }

                        val chapterCountText = if (chapters.isNotEmpty()) {
                            "${currentIndex + 1} / ${chapters.size}"
                        } else {
                            currentChapterTitle
                        }

                        Text(
                            text = chapterCountText,
                            color = textColor.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium,
                        )

                        TextButton(
                            onClick = {
                                if (hasNext) {
                                    val nextIdx = currentIndex + 1
                                    currentIndex = nextIdx
                                    val ch = chapters[nextIdx]
                                    currentChapterId = ch.id
                                    currentChapterTitle = ch.name
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            },
                            enabled = hasNext,
                        ) {
                            Text(
                                text = "Next",
                                color = if (hasNext) textColor else textColor.copy(alpha = 0.3f),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward,
                                contentDescription = "Next Chapter",
                                tint = if (hasNext) textColor else textColor.copy(alpha = 0.3f),
                            )
                        }
                    }
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(paddingValues),
            ) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Loading chapter...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = textColor.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                    errorMessage != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp),
                            ) {
                                Text(
                                    text = errorMessage ?: "Error loading chapter",
                                    color = textColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                FilledTonalButton(onClick = { reloadTrigger++ }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                    else -> {
                        val paragraphs = remember(content) {
                            content
                                .split(Regex("\\n\\s*\\n"))
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            itemsIndexed(
                                items = paragraphs,
                                key = { index, _ -> "${currentChapterId}_$index" },
                                contentType = { _, _ -> "paragraph" },
                            ) { index, paragraph ->
                                val isHeading = index == 0 && paragraphs.size > 1 && (
                                    paragraph.equals(currentChapterTitle, ignoreCase = true) ||
                                        (
                                            paragraph.length < 120 &&
                                                paragraph.startsWith("Chapter", ignoreCase = true)
                                            )
                                    )
                                val paraFontSize = if (isHeading) (fontSize + 4).sp else fontSize.sp
                                val paraLineHeight = if (isHeading) {
                                    ((fontSize + 4) * 1.45f).sp
                                } else {
                                    (fontSize * 1.85f).sp
                                }
                                Text(
                                    text = paragraph,
                                    color = textColor,
                                    fontSize = paraFontSize,
                                    fontWeight = if (isHeading) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = fontFamily,
                                    lineHeight = paraLineHeight,
                                    letterSpacing = (fontSize * 0.025f).sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .widthIn(max = 680.dp)
                                        .padding(bottom = if (isHeading) 6.dp else 0.dp),
                                )
                            }

                            item(
                                key = "bottom_spacer",
                                contentType = "spacer",
                            ) {
                                Spacer(modifier = Modifier.height(64.dp))
                            }
                        }
                    }
                }
            }

            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false },
                    containerColor = bgColor,
                    contentColor = textColor,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = "Reader Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Font Size: ${fontSize.toInt()}sp", color = textColor)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { if (fontSize > 12) fontSize -= 2 },
                                ) {
                                    Text("A-")
                                }
                                FilledTonalButton(
                                    onClick = { if (fontSize < 32) fontSize += 2 },
                                ) {
                                    Text("A+")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(color = textColor.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Theme", style = MaterialTheme.typography.labelLarge, color = textColor)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = themeMode == mode,
                                    onClick = { themeMode = mode },
                                    label = {
                                        Text(
                                            text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                                        )
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDivider(color = textColor.copy(alpha = 0.2f))
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Font Family", style = MaterialTheme.typography.labelLarge, color = textColor)
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "Serif" to "serif",
                                "Sans-Serif" to "sans",
                                "Monospace" to "mono",
                            ).forEach { (label, key) ->
                                FilterChip(
                                    selected = fontFamilyName == key,
                                    onClick = { fontFamilyName = key },
                                    label = { Text(label) },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    enum class ThemeMode {
        DARK,
        AMOLED,
        SEPIA,
        LIGHT,
    }

    companion object {
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"
        const val EXTRA_MANGA_TITLE = "manga_title"

        fun newIntent(
            context: Context,
            mangaId: Long,
            chapterId: Long,
            chapterTitle: String = "Chapter",
            mangaTitle: String = "",
        ): Intent {
            return Intent(context, NovelReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA_ID, mangaId)
                putExtra(EXTRA_CHAPTER_ID, chapterId)
                putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                putExtra(EXTRA_MANGA_TITLE, mangaTitle)
            }
        }
    }
}
