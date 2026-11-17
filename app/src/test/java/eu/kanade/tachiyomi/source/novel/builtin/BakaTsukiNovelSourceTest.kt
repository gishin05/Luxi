package eu.kanade.tachiyomi.source.novel.builtin

import eu.kanade.tachiyomi.source.novel.lnreader.LNReaderSource
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class BakaTsukiNovelSourceTest {

    @Test
    fun `parses mediawiki thumbnails and fallback image files while skipping icons`() {
        val payload = """
            {
              "query": {
                "normalized": [{"from": "Sword Art Online", "to": "Sword_Art_Online"}],
                "pages": {
                  "1": {
                    "title": "Sword_Art_Online",
                    "thumbnail": {"source": "https://www.baka-tsuki.org/project/images/thumb/sao_cover.jpg/300px-sao_cover.jpg"}
                  },
                  "2": {
                    "title": "Overlord",
                    "images": [
                      {"title": "File:Flag_EN.svg"},
                      {"title": "File:Site_Icon.png"},
                      {"title": "File:Overlord_v01_cover.jpg"}
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(payload).jsonObject
        val (covers, candidateFiles) = BakaTsukiNovelSource.parseQueryCoversAndFiles(root)

        covers["Sword Art Online"] shouldBe
            "https://www.baka-tsuki.org/project/images/thumb/sao_cover.jpg/300px-sao_cover.jpg"
        covers["Sword_Art_Online"] shouldBe
            "https://www.baka-tsuki.org/project/images/thumb/sao_cover.jpg/300px-sao_cover.jpg"
        candidateFiles shouldContainExactly mapOf("Overlord" to "File:Overlord_v01_cover.jpg")
    }

    @Test
    fun `extracts non-icon cover image from html and normalizes relative paths`() {
        val html = """
            <div>
              <img src="/project/images/thumb/Flag_JP.svg/24px-Flag_JP.svg.png" />
              <div class="thumbimage"><img src="/project/images/a/ab/Cover_Vol1.jpg" /></div>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://www.baka-tsuki.org")
        val cover = BakaTsukiNovelSource.extractCoverFromHtml(doc)

        cover shouldBe "https://www.baka-tsuki.org/project/images/a/ab/Cover_Vol1.jpg"
    }

    @Test
    fun `normalizes lnreader cover urls and filters dead default cover`() {
        LNReaderSource.normalizeCoverUrl(
            "https://raw.githubusercontent.com/LNReader/lnreader-sources/main/src/coverNotAvailable.jpg",
            "https://novelfull.com/",
        ) shouldBe ""
        LNReaderSource.normalizeCoverUrl("//cdn.example.com/cover.jpg", "https://novelfull.com/") shouldBe
            "https://cdn.example.com/cover.jpg"
        LNReaderSource.normalizeCoverUrl("/uploads/cover.jpg", "https://novelfull.com/") shouldBe
            "https://novelfull.com/uploads/cover.jpg"
        LNReaderSource.normalizeCoverUrl("data:image/png;base64,AAAA", "https://novelfull.com/") shouldBe
            "data:image/png;base64,AAAA"
    }
}
