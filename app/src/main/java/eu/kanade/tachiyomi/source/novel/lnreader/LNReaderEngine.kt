package eu.kanade.tachiyomi.source.novel.lnreader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SuppressLint("SetJavaScriptEnabled")
class LNReaderEngine(
    private val context: Context,
    private val networkHelper: NetworkHelper,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val callbacks = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val idGen = AtomicLong(1)
    private val loadedPlugins = ConcurrentHashMap<String, Boolean>()
    private val pluginLoadLocks = ConcurrentHashMap<String, Mutex>()
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())

    private val initMutex = Mutex()
    private var initDeferred: CompletableDeferred<Unit>? = null
    private var idlePauseJob: Job? = null

    @Volatile
    private var isWebViewPaused = false

    private inner class LuxiBridge {
        @JavascriptInterface
        fun onShellReady() {
            lastActivityMs.set(System.currentTimeMillis())
            initDeferred?.complete(Unit)
        }

        @JavascriptInterface
        fun onSuccess(callId: Long, result: String) {
            lastActivityMs.set(System.currentTimeMillis())
            callbacks.remove(callId)?.complete(result)
        }

        @JavascriptInterface
        fun onError(callId: Long, error: String) {
            lastActivityMs.set(System.currentTimeMillis())
            callbacks.remove(callId)?.completeExceptionally(RuntimeException(error))
        }

        @JavascriptInterface
        fun nativeFetchAsync(fetchId: Long, url: String, optionsJson: String) {
            lastActivityMs.set(System.currentTimeMillis())
            ioScope.launch {
                val payload = executeFetch(url, optionsJson)
                lastActivityMs.set(System.currentTimeMillis())
                mainHandler.post {
                    webView?.evaluateJavascript(
                        "window.__luxi_onFetchResult($fetchId, ${JSONObject.quote(payload)});",
                        null,
                    )
                }
            }
        }
    }

    private fun executeFetch(url: String, optionsJson: String): String {
        return try {
            val reqBuilder = Request.Builder().url(url)
            var method = "GET"
            var bodyStr: String? = null
            var referrer: String? = null

            if (optionsJson.isNotBlank() && optionsJson != "{}") {
                try {
                    val obj = JSONObject(optionsJson)
                    if (obj.has("method")) {
                        method = obj.optString("method", "GET").uppercase()
                    }
                    if (obj.has("referrer")) {
                        referrer = obj.optString("referrer", "").takeIf { it.isNotBlank() }
                    }
                    if (obj.has("headers")) {
                        val headersObj = obj.optJSONObject("headers")
                        if (headersObj != null) {
                            for (key in headersObj.keys()) {
                                val v = headersObj.optString(key, "")
                                if (v.isNotBlank()) {
                                    reqBuilder.header(key, v)
                                }
                            }
                        }
                    }
                    if (obj.has("body")) {
                        bodyStr = obj.optString("body")
                    }
                } catch (_: Exception) {}
            }

            if (!referrer.isNullOrBlank()) {
                reqBuilder.header("Referer", referrer)
            }

            if (method == "POST" || method == "PUT" || method == "PATCH") {
                val contentType = reqBuilder.build().header("Content-Type")?.toMediaTypeOrNull()
                val reqBody = (bodyStr ?: "").toRequestBody(contentType)
                reqBuilder.method(method, reqBody)
            } else {
                reqBuilder.method(method, null)
            }

            networkHelper.client.newCall(reqBuilder.build()).execute().use { response ->
                val contentType = response.body.contentType()?.toString()?.lowercase().orEmpty()
                val isBinary = contentType.startsWith("image/") ||
                    contentType.startsWith("application/octet-stream")

                val headersMap = mutableMapOf<String, String>()
                for (i in 0 until response.headers.size) {
                    headersMap[response.headers.name(i).lowercase()] = response.headers.value(i)
                }

                JSONObject().apply {
                    put("status", response.code)
                    put("statusText", response.message)
                    put("ok", response.isSuccessful)
                    put("contentType", contentType)
                    if (isBinary) {
                        val bytes = response.body.bytes()
                        put("body", "")
                        put("bodyBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } else {
                        put("body", response.body.string())
                    }
                    put("headers", JSONObject(headersMap as Map<*, *>))
                }.toString()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("status", 500)
                put("statusText", e.message ?: "Network error")
                put("ok", false)
                put("body", "")
                put("headers", JSONObject())
                put("error", e.message ?: "Network error")
            }.toString()
        }
    }

    private suspend fun ensureReady() = withContext(ioScope.coroutineContext) {
        idlePauseJob?.cancel()
        idlePauseJob = null
        val existing = initDeferred
        if (existing != null && existing.isCompleted && !existing.isCancelled) {
            try {
                existing.await()
                if (isWebViewPaused) {
                    withContext(Dispatchers.Main) {
                        webView?.onResume()
                        isWebViewPaused = false
                    }
                }
                return@withContext
            } catch (_: Throwable) {
                initDeferred = null
            }
        }

        val deferred = initMutex.withLock {
            val current = initDeferred?.takeUnless { it.isCancelled }
            if (current != null) {
                current
            } else {
                CompletableDeferred<Unit>().also { created ->
                    initDeferred = created
                    mainHandler.post {
                        try {
                            val wv = WebView(context.applicationContext)
                            val settings = wv.settings
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.cacheMode = WebSettings.LOAD_DEFAULT

                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    created.complete(Unit)
                                }
                            }
                            wv.addJavascriptInterface(LuxiBridge(), "LuxiBridge")
                            webView = wv
                            isWebViewPaused = false
                            wv.loadDataWithBaseURL("https://lnreader.app", HTML_SHELL, "text/html", "UTF-8", null)
                        } catch (e: Exception) {
                            initDeferred = null
                            created.completeExceptionally(e)
                        }
                    }
                }
            }
        }
        try {
            val ready = withTimeoutOrNull(10_000L) {
                deferred.await()
                true
            } ?: false
            if (!ready) {
                initDeferred = null
                throw IllegalStateException("LNReader WebView shell initialization timed out")
            }
        } catch (t: Throwable) {
            initDeferred = null
            throw t
        }
        if (isWebViewPaused) {
            withContext(Dispatchers.Main) {
                webView?.onResume()
                isWebViewPaused = false
            }
        }
    }

    private fun scheduleIdlePause() {
        if (callbacks.isNotEmpty()) return
        idlePauseJob?.cancel()
        idlePauseJob = ioScope.launch {
            delay(10_000)
            if (callbacks.isEmpty() && System.currentTimeMillis() - lastActivityMs.get() >= 9_500) {
                mainHandler.post {
                    if (callbacks.isEmpty()) {
                        webView?.onPause()
                        isWebViewPaused = true
                    }
                }
            }
        }
    }

    suspend fun loadPlugin(pluginId: String, script: String) = withContext(ioScope.coroutineContext) {
        ensureReady()
        if (loadedPlugins[pluginId] == true) return@withContext

        val lock = pluginLoadLocks.getOrPut(pluginId) { Mutex() }
        lock.withLock {
            if (loadedPlugins[pluginId] == true) return@withLock

            // Pass raw plugin code through JSONObject.quote + new Function so regexes, backticks,
            // backslashes, and dollar signs execute verbatim without escaping corruption.
            val quotedScript = JSONObject.quote(script)
            val quotedId = JSONObject.quote(pluginId)
            val wrapper = """
                (function() {
                    try {
                        window.__plugins = window.__plugins || {};
                        if (window.__plugins[$quotedId]) return "ok";
                        var exports = {};
                        var module = { exports: exports };
                        var fn = new Function('require', 'module', 'exports', $quotedScript);
                        fn(window.require, module, exports);
                        window.__plugins[$quotedId] = module.exports.default || module.exports;
                        return "ok";
                    } catch(e) {
                        return "err:" + (e && (e.stack || e.message) ? (e.stack || e.message) : String(e));
                    }
                })();
            """.trimIndent()

            val evalDeferred = CompletableDeferred<String>()
            withContext(Dispatchers.Main) {
                val wv = webView ?: run {
                    evalDeferred.completeExceptionally(IllegalStateException("WebView unavailable"))
                    return@withContext
                }
                wv.evaluateJavascript(wrapper) { raw ->
                    evalDeferred.complete(raw ?: "\"err:null eval\"")
                }
            }

            val rawStatus = evalDeferred.await()
            val status = runCatching { json.decodeFromString<String>(rawStatus) }.getOrDefault(rawStatus)
            if (status != "ok") {
                throw IllegalStateException("Failed to evaluate LNReader plugin $pluginId: $status")
            }
            loadedPlugins[pluginId] = true
        }
    }

    private suspend fun evalAsync(script: String, idleTimeoutMs: Long = 30000L, maxTimeoutMs: Long = 180000L): String {
        ensureReady()
        val callId = idGen.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        callbacks[callId] = deferred
        lastActivityMs.set(System.currentTimeMillis())

        withContext(Dispatchers.Main) {
            val callWrapper = "__luxi_call($callId, $script);"
            webView?.evaluateJavascript(callWrapper, null)
        }

        val startMs = System.currentTimeMillis()
        return try {
            while (!deferred.isCompleted) {
                val now = System.currentTimeMillis()
                val elapsed = now - startMs
                if (elapsed >= maxTimeoutMs) {
                    throw IllegalStateException("LNReader execution exceeded ${maxTimeoutMs}ms")
                }
                val idle = now - lastActivityMs.get()
                if (idle >= idleTimeoutMs) {
                    throw IllegalStateException("LNReader execution stalled for ${idleTimeoutMs}ms")
                }
                val waitWindow = minOf(idleTimeoutMs - idle, maxTimeoutMs - elapsed)
                val completed = withTimeoutOrNull(waitWindow) {
                    select {
                        deferred.onAwait { true }
                    }
                }
                if (completed == true) break
            }
            deferred.await()
        } catch (t: Throwable) {
            callbacks.remove(callId)
            throw t
        } finally {
            scheduleIdlePause()
        }
    }

    suspend fun getPopularNovels(pluginId: String, script: String, page: Int): List<LNNovelItem> {
        loadPlugin(pluginId, script)
        val qId = JSONObject.quote(pluginId)
        val res = evalAsync("window.__plugins[$qId].popularNovels($page, { showLatestNovels: false })")
        return json.decodeFromString(res)
    }

    suspend fun getLatestNovels(pluginId: String, script: String, page: Int): List<LNNovelItem> {
        loadPlugin(pluginId, script)
        val qId = JSONObject.quote(pluginId)
        val res = evalAsync("window.__plugins[$qId].popularNovels($page, { showLatestNovels: true })")
        return json.decodeFromString(res)
    }

    suspend fun searchNovels(pluginId: String, script: String, query: String, page: Int): List<LNNovelItem> {
        loadPlugin(pluginId, script)
        val qId = JSONObject.quote(pluginId)
        val qQuery = JSONObject.quote(query)
        val res = evalAsync("window.__plugins[$qId].searchNovels($qQuery, $page)")
        return json.decodeFromString(res)
    }

    suspend fun parseNovel(pluginId: String, script: String, path: String): LNNovelDetails {
        loadPlugin(pluginId, script)
        val qId = JSONObject.quote(pluginId)
        val qPath = JSONObject.quote(path)
        val res = evalAsync("window.__plugins[$qId].parseNovel($qPath)")
        return json.decodeFromString(res)
    }

    suspend fun parseNovelProgressively(
        pluginId: String,
        script: String,
        path: String,
        onDetails: suspend (LNNovelDetails) -> Unit,
        onChapters: suspend (List<LNChapterItem>, isFinal: Boolean) -> Unit,
    ) {
        val details = parseNovel(pluginId, script, path)
        onDetails(details)

        val totalPages = details.totalPages
        if (totalPages <= 1) {
            onChapters(details.chapters, true)
            return
        }

        if (details.chapters.isNotEmpty()) {
            onChapters(details.chapters, false)
        }

        val qId = JSONObject.quote(pluginId)
        val qPath = JSONObject.quote(path)
        val pageChunks = (2..totalPages).chunked(3)
        pageChunks.forEachIndexed { batchIdx, pageBatch ->
            val pagesResult = coroutineScope {
                pageBatch.map { p ->
                    async {
                        val raw =
                            evalAsync("window.__plugins[$qId].parsePage($qPath, ${JSONObject.quote(p.toString())})")
                        json.decodeFromString<LNPageChapters>(raw).chapters
                    }
                }.awaitAll()
            }
            val mergedBatch = pagesResult.flatten()
            val isLastBatch = batchIdx == pageChunks.lastIndex
            onChapters(mergedBatch, isLastBatch)
        }
    }

    suspend fun parseChapter(pluginId: String, script: String, path: String): String {
        loadPlugin(pluginId, script)
        val qId = JSONObject.quote(pluginId)
        val qPath = JSONObject.quote(path)
        return evalAsync("window.__plugins[$qId].parseChapter($qPath)")
    }

    companion object {
        private val HTML_SHELL = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"></head>
            <body>
            <script>
            (function() {
                window.__luxi_callbacks = {};
                window.__luxi_fetches = {};
                var __luxi_fetchId = 1;

                window.__luxi_call = function(id, promise) {
                    Promise.resolve(promise)
                        .then(function(result) {
                            var json = typeof result === 'string' ? result : JSON.stringify(result);
                            window.LuxiBridge.onSuccess(id, json);
                        })
                        .catch(function(err) {
                            window.LuxiBridge.onError(id, err ? (err.message || String(err)) : "Error");
                        });
                };

                window.__luxi_onFetchResult = function(id, rawResult) {
                    var resolve = window.__luxi_fetches[id];
                    if (!resolve) return;
                    delete window.__luxi_fetches[id];
                    var resp;
                    try {
                        resp = JSON.parse(rawResult);
                    } catch(e) {
                        resp = { status: 200, statusText: 'OK', ok: true, body: rawResult, headers: {}, contentType: 'text/plain' };
                    }
                    resolve({
                        ok: resp.ok,
                        status: resp.status,
                        statusText: resp.statusText || '',
                        headers: {
                            get: function(h) {
                                if (!h) return null;
                                return (resp.headers && resp.headers[h.toLowerCase()]) || null;
                            }
                        },
                        text: function() { return Promise.resolve(resp.body || ""); },
                        json: function() { return Promise.resolve(JSON.parse(resp.body || "{}")); },
                        blob: function() {
                            if (resp.bodyBase64) {
                                var bin = atob(resp.bodyBase64);
                                var len = bin.length;
                                var bytes = new Uint8Array(len);
                                for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
                                return Promise.resolve(new Blob([bytes], { type: resp.contentType || 'application/octet-stream' }));
                            }
                            return Promise.resolve(new Blob([resp.body || ''], { type: resp.contentType || 'text/plain' }));
                        }
                    });
                };

                var _defaultCover = "";

                var _novelStatus = {
                    Unknown: "Unknown",
                    Ongoing: "Ongoing",
                    Completed: "Completed",
                    Licensed: "Licensed",
                    PublishingFinished: "Publishing Finished",
                    Cancelled: "Cancelled",
                    OnHiatus: "On Hiatus"
                };

                var _filterTypes = {
                    TextInput: "TextInput",
                    Picker: "Picker",
                    CheckboxGroup: "CheckboxGroup",
                    Switch: "Switch",
                    ExcludableCheckboxGroup: "ExcludableCheckboxGroup"
                };

                var _storage = {
                    get: function(key) { return null; },
                    set: function(key, val) {}
                };

                var _fetch = {
                    fetchApi: function(url, init) {
                        var optionsJson = init ? JSON.stringify(init) : "{}";
                        return new Promise(function(resolve) {
                            var id = __luxi_fetchId++;
                            window.__luxi_fetches[id] = resolve;
                            window.LuxiBridge.nativeFetchAsync(id, url, optionsJson);
                        });
                    },
                    fetchText: function(url, init, encoding) {
                        return _fetch.fetchApi(url, init).then(function(r) { return r.text(); });
                    },
                    fetchFile: function(url, init) {
                        return _fetch.fetchApi(url, init).then(function(r) { return r.text(); });
                    }
                };

                function createCheerio() {
                    return {
                        load: function(html) {
                            var parser = new DOMParser();
                            var doc = parser.parseFromString(html || "", 'text/html');

                            function wrap(nodes, prevWrap) {
                                var arr = Array.isArray(nodes) ? nodes : (nodes ? [nodes] : []);
                                var q = function(selector) {
                                    var found = [];
                                    arr.forEach(function(el) {
                                        try {
                                            if (el && el.querySelectorAll) {
                                                var nl = el.querySelectorAll(selector);
                                                for (var i = 0; i < nl.length; i++) found.push(nl[i]);
                                            }
                                        } catch(e) {}
                                    });
                                    return wrap(found, q);
                                };
                                q.length = arr.length;
                                arr.forEach(function(item, idx) { q[idx] = item; });

                                q.each = function(callback) {
                                    for (var i = 0; i < arr.length; i++) {
                                        callback.call(arr[i], i, arr[i]);
                                    }
                                    return q;
                                };
                                q.map = function(callback) {
                                    var res = [];
                                    for (var i = 0; i < arr.length; i++) {
                                        res.push(callback.call(arr[i], i, arr[i]));
                                    }
                                    return {
                                        get: function() { return res; },
                                        toArray: function() { return res; }
                                    };
                                };
                                q.find = function(sel) {
                                    return q(sel);
                                };
                                q.not = function(sel) {
                                    if (typeof sel === 'string') {
                                        return wrap(arr.filter(function(el) { return !el || !el.matches || !el.matches(sel); }), q);
                                    }
                                    return q;
                                };
                                q.is = function(sel) {
                                    return arr.some(function(el) { return el && el.matches && el.matches(sel); });
                                };
                                q.closest = function(sel) {
                                    var res = [];
                                    arr.forEach(function(el) {
                                        var c = el && el.closest ? el.closest(sel) : null;
                                        if (c && res.indexOf(c) === -1) res.push(c);
                                    });
                                    return wrap(res, q);
                                };
                                q.text = function() {
                                    return arr.map(function(el) { return el.textContent || ''; }).join('');
                                };
                                q.html = function() {
                                    return arr.map(function(el) { return el.innerHTML || ''; }).join('');
                                };
                                q.attr = function(name, val) {
                                    if (val !== undefined) {
                                        arr.forEach(function(el) { if (el && el.setAttribute) el.setAttribute(name, val); });
                                        return q;
                                    }
                                    return arr[0] && arr[0].getAttribute ? arr[0].getAttribute(name) || undefined : undefined;
                                };
                                q.removeAttr = function(name) {
                                    arr.forEach(function(el) { if (el && el.removeAttribute) el.removeAttribute(name); });
                                    return q;
                                };
                                q.remove = function() {
                                    arr.forEach(function(el) { if (el && el.remove) el.remove(); });
                                    return q;
                                };
                                q.first = function() { return wrap(arr[0] ? [arr[0]] : [], q); };
                                q.last = function() { return wrap(arr.length ? [arr[arr.length - 1]] : [], q); };
                                q.eq = function(idx) { return wrap(arr[idx] ? [arr[idx]] : [], q); };
                                q.filter = function(fn) {
                                    if (typeof fn === 'function') {
                                        return wrap(arr.filter(function(item, idx) { return fn.call(item, idx, item); }), q);
                                    }
                                    if (typeof fn === 'string') {
                                        return wrap(arr.filter(function(item) { return item && item.matches && item.matches(fn); }), q);
                                    }
                                    return q;
                                };
                                q.contents = function() {
                                    var childList = [];
                                    arr.forEach(function(el) {
                                        if (el && el.childNodes) {
                                            for (var i = 0; i < el.childNodes.length; i++) {
                                                childList.push(el.childNodes[i]);
                                            }
                                        }
                                    });
                                    return wrap(childList, q);
                                };
                                q.addBack = function() {
                                    var combined = arr.slice();
                                    if (prevWrap) {
                                        for (var i = 0; i < prevWrap.length; i++) {
                                            if (combined.indexOf(prevWrap[i]) === -1) combined.push(prevWrap[i]);
                                        }
                                    }
                                    return wrap(combined, prevWrap);
                                };
                                q.replaceWith = function(content) {
                                    arr.forEach(function(el) {
                                        if (!el || !el.parentNode) return;
                                        if (typeof content === 'string') {
                                            var temp = doc.createElement('div');
                                            temp.innerHTML = content;
                                            while (temp.firstChild) {
                                                el.parentNode.insertBefore(temp.firstChild, el);
                                            }
                                            el.parentNode.removeChild(el);
                                        } else if (content && content.nodeType) {
                                            el.parentNode.replaceChild(content, el);
                                        }
                                    });
                                    return q;
                                };
                                q.parent = function() {
                                    var parents = [];
                                    arr.forEach(function(el) {
                                        if (el && el.parentElement && parents.indexOf(el.parentElement) === -1) {
                                            parents.push(el.parentElement);
                                        }
                                    });
                                    return wrap(parents, q);
                                };
                                q.parents = function(sel) {
                                    var parents = [];
                                    arr.forEach(function(el) {
                                        var p = el ? el.parentElement : null;
                                        while (p) {
                                            if (!sel || (p.matches && p.matches(sel))) {
                                                if (parents.indexOf(p) === -1) parents.push(p);
                                            }
                                            p = p.parentElement;
                                        }
                                    });
                                    return wrap(parents, q);
                                };
                                q.children = function(sel) {
                                    var childs = [];
                                    arr.forEach(function(el) {
                                        if (el && el.children) {
                                            for (var i = 0; i < el.children.length; i++) {
                                                var c = el.children[i];
                                                if (!sel || (c.matches && c.matches(sel))) {
                                                    if (childs.indexOf(c) === -1) childs.push(c);
                                                }
                                            }
                                        }
                                    });
                                    return wrap(childs, q);
                                };
                                q.prev = function() {
                                    return wrap(arr[0] && arr[0].previousElementSibling ? [arr[0].previousElementSibling] : [], q);
                                };
                                q.next = function() {
                                    return wrap(arr[0] && arr[0].nextElementSibling ? [arr[0].nextElementSibling] : [], q);
                                };
                                q.hasClass = function(cls) {
                                    return arr.some(function(el) { return el && el.classList && el.classList.contains(cls); });
                                };
                                return q;
                            }

                            var root = function(selector) {
                                if (typeof selector === 'string') {
                                    var nl = doc.querySelectorAll(selector);
                                    var found = [];
                                    for (var i = 0; i < nl.length; i++) found.push(nl[i]);
                                    return wrap(found);
                                }
                                return wrap(selector);
                            };
                            root.html = function() { return doc.body ? doc.body.innerHTML : ''; };
                            return root;
                        }
                    };
                }

                var _cheerio = createCheerio();

                var _voidElements = {
                    area: true, base: true, br: true, col: true, embed: true, hr: true,
                    img: true, input: true, link: true, meta: true, param: true, source: true,
                    track: true, wbr: true
                };

                function isVoidElement(name) {
                    return !!_voidElements[(name || "").toLowerCase()];
                }

                function HtmlParser(handler) {
                    this.isVoidElement = isVoidElement;
                    this.write = function(chunk) {
                        var parser = new DOMParser();
                        var doc = parser.parseFromString(chunk || "", 'text/html');
                        function walk(node) {
                            if (node.nodeType === 1) {
                                var tag = node.nodeName.toLowerCase();
                                var attrs = {};
                                if (node.attributes) {
                                    for (var i = 0; i < node.attributes.length; i++) {
                                        attrs[node.attributes[i].name] = node.attributes[i].value;
                                    }
                                }
                                if (handler.onopentag) handler.onopentag(tag, attrs);
                                var child = node.firstChild;
                                while (child) {
                                    walk(child);
                                    child = child.nextSibling;
                                }
                                if (handler.onclosetag) handler.onclosetag(tag);
                            } else if (node.nodeType === 3) {
                                if (handler.ontext) handler.ontext(node.nodeValue);
                            }
                        }
                        if (doc.body) walk(doc.body);
                    };
                    this.end = function() {
                        if (handler.onend) handler.onend();
                    };
                    this.reset = function() {};
                }
                HtmlParser.isVoidElement = isVoidElement;

                var _dayjs = function(d) {
                    var dt = d ? new Date(d) : new Date();
                    return {
                        format: function(f) {
                            try {
                                return dt.toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
                            } catch(e) {
                                return dt.toDateString();
                            }
                        },
                        subtract: function(amount, unit) {
                            var newDt = new Date(dt.getTime());
                            var num = parseInt(amount, 10) || 0;
                            if (/second/i.test(unit)) newDt.setSeconds(newDt.getSeconds() - num);
                            else if (/minute|min/i.test(unit)) newDt.setMinutes(newDt.getMinutes() - num);
                            else if (/hour/i.test(unit)) newDt.setHours(newDt.getHours() - num);
                            else if (/day/i.test(unit)) newDt.setDate(newDt.getDate() - num);
                            else if (/week/i.test(unit)) newDt.setDate(newDt.getDate() - num * 7);
                            else if (/month/i.test(unit)) newDt.setMonth(newDt.getMonth() - num);
                            else if (/year/i.test(unit)) newDt.setFullYear(newDt.getFullYear() - num);
                            return _dayjs(newDt);
                        }
                    };
                };
                _dayjs.default = _dayjs;

                var _entities = {
                    decodeHTML: function(s) {
                        var doc = new DOMParser().parseFromString(s || "", 'text/html');
                        return doc.body ? doc.body.textContent : s;
                    },
                    decode: function(s) {
                        var doc = new DOMParser().parseFromString(s || "", 'text/html');
                        return doc.body ? doc.body.textContent : s;
                    }
                };

                window.require = function(mod) {
                    if (mod === 'cheerio') return _cheerio;
                    if (mod === 'htmlparser2') return { Parser: HtmlParser, isVoidElement: isVoidElement };
                    if (mod === '@libs/fetch') return _fetch;
                    if (mod === '@libs/novelStatus') return { NovelStatus: _novelStatus };
                    if (mod === '@libs/defaultCover') return { defaultCover: _defaultCover };
                    if (mod === '@libs/storage') return { storage: _storage };
                    if (mod === '@libs/filterInputs') return { FilterTypes: _filterTypes };
                    if (mod === '@libs/isAbsoluteUrl') return { isUrlAbsolute: function(u) { return /^(?:[a-z]+:)?\/\//i.test(u); } };
                    if (mod === 'dayjs') return _dayjs;
                    if (mod === 'entities' || mod === 'he') return _entities;
                    return {};
                };
                if (window.LuxiBridge && window.LuxiBridge.onShellReady) {
                    window.LuxiBridge.onShellReady();
                }
            })();
            </script>
            </body>
            </html>
        """.trimIndent()
    }
}
