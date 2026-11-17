package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.jupiter.api.Test

class CloudflareInterceptorCookieTest {

    private val kwikUrl = "https://kwik.cx/d/abc123".toHttpUrl()

    @Test
    fun `appends missing cf_clearance while keeping manual session cookie`() {
        val request = Request.Builder()
            .url(kwikUrl)
            .header("Cookie", "kwik_session=manual_token")
            .build()

        val jarCookies = listOf(
            Cookie.Builder().domain("kwik.cx").name("cf_clearance").value("cf_ok").build(),
            Cookie.Builder().domain("kwik.cx").name("kwik_session").value("stale_webview").build(),
        )

        val updated = request.withCookieJarCookies(jarCookies, onlyNames = listOf("cf_clearance"))
        updated.header("Cookie") shouldBe "kwik_session=manual_token; cf_clearance=cf_ok"
    }

    @Test
    fun `does not overwrite existing clearance cookie`() {
        val request = Request.Builder()
            .url(kwikUrl)
            .header("Cookie", "cf_clearance=already_present")
            .build()

        val jarCookies = listOf(
            Cookie.Builder().domain("kwik.cx").name("cf_clearance").value("other").build(),
            Cookie.Builder().domain("kwik.cx").name("__cf_bm").value("bm_val").build(),
        )

        val onlyClearance = request.withCookieJarCookies(jarCookies, onlyNames = listOf("cf_clearance"))
        onlyClearance.header("Cookie") shouldBe "cf_clearance=already_present"

        val allMissing = request.withCookieJarCookies(jarCookies, onlyNames = null)
        allMissing.header("Cookie") shouldBe "cf_clearance=already_present; __cf_bm=bm_val"
    }
}
