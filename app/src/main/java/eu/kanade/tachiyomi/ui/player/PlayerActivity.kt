package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

class PlayerActivity : BaseActivity() {

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Video is watched in landscape, and the player chrome covers the whole screen.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
        val chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
        val mangaTitle = intent.getStringExtra(EXTRA_MANGA_TITLE).orEmpty()
        val chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE) ?: "Episode 1"

        setComposeContent {
            PlayerScreen(
                mangaId = mangaId,
                chapterId = chapterId,
                mangaTitle = mangaTitle,
                chapterTitle = chapterTitle,
                onBackClick = { finish() },
                onFullscreenChange = { isFullscreen -> setFullscreen(isFullscreen) },
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) {
            setFullscreen(enabled = true)
        }
    }

    private var isFullscreen = true

    private fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_CHAPTER_ID = "chapter_id"
        const val EXTRA_MANGA_TITLE = "manga_title"
        const val EXTRA_CHAPTER_TITLE = "chapter_title"

        fun newIntent(
            context: Context,
            mangaId: Long,
            chapterId: Long,
            chapterTitle: String = "Episode 1",
            mangaTitle: String = "",
        ): Intent {
            return Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_MANGA_ID, mangaId)
                putExtra(EXTRA_CHAPTER_ID, chapterId)
                putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                putExtra(EXTRA_MANGA_TITLE, mangaTitle)
            }
        }
    }
}
