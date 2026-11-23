package mihon.domain.source.interactor

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class UpdateMangaFromRemoteTest {

    @Test
    fun `matchesComixManga accepts short hid, slug, and title paths`() {
        UpdateMangaFromRemote.matchesComixManga(
            mangaUrl = "/abc12",
            hid = "abc12",
            pathname = "/title/abc12-solo-leveling",
        ) shouldBe true

        UpdateMangaFromRemote.matchesComixManga(
            mangaUrl = "/abc12-solo-leveling",
            hid = "abc12",
            pathname = "/title/abc12-solo-leveling",
        ) shouldBe true

        UpdateMangaFromRemote.matchesComixManga(
            mangaUrl = "/title/abc12-solo-leveling",
            hid = "abc12",
            pathname = "/title/abc12-solo-leveling",
        ) shouldBe true
    }

    @Test
    fun `matchesComixManga rejects mismatched hid and slug`() {
        UpdateMangaFromRemote.matchesComixManga(
            mangaUrl = "/xyz99",
            hid = "abc12",
            pathname = "/title/abc12-solo-leveling",
        ) shouldBe false
    }

    @Test
    fun `isComixIncompleteHistory detects empty, partial, gapped, and completed histories`() {
        val baseManga = Manga.create()

        UpdateMangaFromRemote.isComixIncompleteHistory(baseManga, emptyList()) shouldBe true

        val partial = (81..100).map { chapter(it.toDouble()) }
        UpdateMangaFromRemote.isComixIncompleteHistory(baseManga, partial) shouldBe true

        val complete = (1..100).map { chapter(it.toDouble()) }
        UpdateMangaFromRemote.isComixIncompleteHistory(baseManga, complete) shouldBe false

        val gapped = ((1..10) + (15..20)).map { chapter(it.toDouble()) }
        UpdateMangaFromRemote.isComixIncompleteHistory(baseManga, gapped) shouldBe true

        val markedComplete = baseManga.copy(
            memo = buildJsonObject {
                put(UpdateMangaFromRemote.COMIX_COMPLETE_MEMO_KEY, JsonPrimitive(true))
            },
        )
        UpdateMangaFromRemote.isComixIncompleteHistory(markedComplete, partial) shouldBe false
    }

    private fun chapter(number: Double) = Chapter.create().copy(
        url = "/title/abc12/ch-$number",
        name = "Chapter $number",
        chapterNumber = number,
    )
}
