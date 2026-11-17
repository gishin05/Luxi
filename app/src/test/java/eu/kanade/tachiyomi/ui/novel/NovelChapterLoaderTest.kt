package eu.kanade.tachiyomi.ui.novel

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class NovelChapterLoaderTest {

    @Test
    fun `preserves paragraph breaks and inter-word spacing from html`() {
        val html = """
            <div class="chapter-content">
                <div class="ads">Sponsored Ad</div>
                <h1>Chapter 1: Dawn</h1>
                <p>First sentence here. Second sentence follows <em>closely</em>.</p>
                <p>Third paragraph starts <span>right</span> after the break.</p>
                <p>Line one<br>Line two</p>
                <hr />
                <div>Fourth block paragraph.</div>
                <div>Fifth block paragraph.</div>
            </div>
        """.trimIndent()

        val result = NovelChapterLoader.cleanHtmlContent(html, "Chapter 1")

        result shouldNotContain "Sponsored Ad"
        result shouldContain
            "Chapter 1: Dawn\n\nFirst sentence here. Second sentence follows closely.\n\nThird paragraph starts right after the break."
        result shouldContain "Line one\nLine two"
        result shouldContain "* * *\n\nFourth block paragraph.\n\nFifth block paragraph."
    }

    @Test
    fun `normalizes plain text chapters without collapsing paragraphs`() {
        val raw = "Chapter 2\r\n\r\nFirst paragraph.\r\n\r\n\r\nSecond paragraph."

        val result = NovelChapterLoader.cleanHtmlContent(raw, "Chapter 2")

        result shouldContain "Chapter 2\n\nFirst paragraph.\n\nSecond paragraph."
    }
}
