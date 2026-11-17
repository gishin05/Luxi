package eu.kanade.tachiyomi.ui.novel

import android.content.Context
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File

object NovelChapterLoader {

    suspend fun getChapterText(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        mangaTitle: String,
        chapterTitle: String,
    ): String = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile(context, mangaId, chapterId)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            try {
                return@withContext cacheFile.readText()
            } catch (_: Exception) {
                // If cache read fails, continue to fetch
            }
        }

        try {
            val appGraph = context.appGraph
            val chapter = appGraph.getChapter.await(chapterId)
            val manga = appGraph.getManga.await(mangaId)

            if (chapter != null && manga != null) {
                val source = appGraph.sourceManager.get(manga.source)
                if (source is NovelSource) {
                    val sChapter = SChapter.create().apply {
                        url = chapter.url
                        name = chapter.name
                        chapter_number = chapter.chapterNumber.toFloat()
                    }
                    val rawContent = source.getChapterContent(sChapter)
                    if (rawContent.isNotBlank()) {
                        val cleaned = cleanHtmlContent(rawContent, chapter.name)
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(cleaned)
                        return@withContext cleaned
                    }
                }
            }
        } catch (_: Exception) {
            // Log and fallback
        }

        NovelChapterContentProvider.getContent(mangaTitle, chapterTitle)
    }

    internal fun cleanHtmlContent(rawHtml: String, chapterTitle: String): String {
        val trimmedInput = rawHtml.trim()
        if (trimmedInput.isBlank()) {
            return "$chapterTitle\n\nThis chapter appears to be empty or could not be parsed."
        }

        if (!trimmedInput.contains('<')) {
            val plain = trimmedInput
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .replace(Regex("[ \\t]+\n"), "\n")
                .replace(Regex("\n[ \\t]+"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
            return if (plain.isBlank()) {
                "$chapterTitle\n\nThis chapter appears to be empty or could not be parsed."
            } else {
                plain
            }
        }

        val doc = Jsoup.parse(rawHtml)
        doc.select(
            "script, style, nav, header, footer, .ad, .ads, .advertisement, noscript, .mw-editsection, .toc, svg, button, form",
        ).remove()

        val sb = StringBuilder()

        fun trimTrailingSpaces() {
            while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                sb.deleteCharAt(sb.length - 1)
            }
        }

        fun appendParagraphBreak() {
            trimTrailingSpaces()
            if (sb.isEmpty()) return
            val len = sb.length
            when {
                len >= 2 && sb[len - 1] == '\n' && sb[len - 2] == '\n' -> Unit
                sb[len - 1] == '\n' -> sb.append('\n')
                else -> sb.append("\n\n")
            }
        }

        fun appendLineBreak() {
            trimTrailingSpaces()
            if (sb.isEmpty()) return
            val len = sb.length
            if (len >= 2 && sb[len - 1] == '\n' && sb[len - 2] == '\n') return
            sb.append('\n')
        }

        // Walk the DOM directly so block boundaries become paragraph breaks rather than Jsoup
        // prettyPrint normalizing newlines down to single spaces.
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> {
                            val normalized = node.wholeText
                                .replace('\u00A0', ' ')
                                .replace(Regex("[\\r\\n\\t ]+"), " ")
                            if (normalized.isEmpty()) return
                            val text = if (sb.isEmpty() || sb[sb.length - 1] == '\n' || sb[sb.length - 1] == ' ') {
                                normalized.trimStart()
                            } else {
                                normalized
                            }
                            sb.append(text)
                        }
                        is Element -> {
                            val tag = node.tagName().lowercase()
                            when {
                                tag == "br" -> appendLineBreak()
                                tag == "hr" -> {
                                    appendParagraphBreak()
                                    sb.append("* * *")
                                    appendParagraphBreak()
                                }
                                tag == "li" -> {
                                    appendLineBreak()
                                    sb.append("• ")
                                }
                                tag in PARAGRAPH_BLOCK_TAGS -> appendParagraphBreak()
                            }
                        }
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element) {
                        val tag = node.tagName().lowercase()
                        if (tag in PARAGRAPH_BLOCK_TAGS) {
                            appendParagraphBreak()
                        } else if (tag == "li") {
                            appendLineBreak()
                        }
                    }
                }
            },
            doc.body(),
        )

        val cleaned = sb.toString()
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return if (cleaned.isBlank()) {
            "$chapterTitle\n\nThis chapter appears to be empty or could not be parsed."
        } else {
            cleaned
        }
    }

    private val PARAGRAPH_BLOCK_TAGS = setOf(
        "p",
        "div",
        "section",
        "article",
        "blockquote",
        "pre",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "tr",
        "ul",
        "ol",
        "dt",
        "dd",
    )

    private fun getCacheFile(context: Context, mangaId: Long, chapterId: Long): File {
        val dir = File(context.cacheDir, "novel_chapters_v2/$mangaId")
        return File(dir, "$chapterId.txt")
    }
}
