package mihon.data.extension.repository

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ExtensionStoreRepositoryImpl(
    @AppCoroutineScope scope: CoroutineScope,
    private val service: ExtensionStoreService,
    private val database: Database,
) : ExtensionStoreRepository {

    private val stores = database.extension_storeQueries.getAll(::extensionStoreMapper)
        .subscribeToList()
        .catch {
            logcat(LogPriority.ERROR, it) { "Failed to query extension stores" }
            emit(emptyList())
        }
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(
                stopTimeout = 5.seconds,
                replayExpiration = Duration.ZERO,
            ),
            replay = 1,
        )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                database.transaction {
                    BUILTIN_STORES.forEach { store ->
                        if (!database.extension_storeQueries.contains(store.indexUrl).awaitAsOne()) {
                            upsert(store)
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to seed built-in extension stores" }
            }
        }
    }

    override suspend fun insert(indexUrl: String): Result<Unit> {
        return service.fetch(indexUrl).mapCatching { upsert(it) }
    }

    override suspend fun insertFromPreference(indexUrl: String, name: String) {
        database.extension_storeQueries.upsert(
            indexUrl = indexUrl,
            name = name,
            badgeLabel = name,
            signingKey = "NO_SIGNING_KEY",
            contactWebsite = indexUrl,
            contactDiscord = null,
            isLegacy = false,
            extensionListUrl = null,
        )
    }

    override suspend fun refreshAll() {
        try {
            val refreshed = stores.first().mapNotNull { store ->
                service.fetch(store.indexUrl)
                    .onFailure {
                        logcat(LogPriority.ERROR, it) {
                            "Failed to refresh extension store '${store.name} (${store.indexUrl})'"
                        }
                    }
                    .getOrNull()
                    ?.let { store.indexUrl to it }
            }
            database.transaction {
                refreshed.forEach { (indexUrl, store) ->
                    if (!database.extension_storeQueries.contains(indexUrl).awaitAsOne()) {
                        return@forEach
                    }
                    upsert(store)
                    if (indexUrl != store.indexUrl) {
                        database.extension_storeQueries.delete(indexUrl)
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    override suspend fun upsert(store: ExtensionStore) {
        database.extension_storeQueries.upsert(
            indexUrl = store.indexUrl,
            name = store.name,
            badgeLabel = store.badgeLabel,
            signingKey = store.signingKey,
            contactWebsite = store.contact.website,
            contactDiscord = store.contact.discord,
            isLegacy = store.isLegacy,
            extensionListUrl = store.extensionListUrl,
        )
    }

    override suspend fun fetchExtensions(): List<Extension.Available> {
        return try {
            supervisorScope {
                database.extension_storeQueries.getAll(::extensionStoreMapper).awaitAsList().map { store ->
                    async {
                        service.getExtensions(store).onFailure {
                            this@ExtensionStoreRepositoryImpl.logcat(LogPriority.ERROR, it) {
                                "Failed to fetch extensions for store '${store.name} (${store.indexUrl})'"
                            }
                        }
                    }
                }
                    .awaitAll()
                    .flatMap { it.getOrDefault(emptyList()) }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    override suspend fun getAll(): List<ExtensionStore> {
        return database.extension_storeQueries.getAll(::extensionStoreMapper).awaitAsList()
    }

    override fun getAllAsFlow(): Flow<List<ExtensionStore>> {
        return stores
    }

    override fun getCountAsFlow(): Flow<Long> {
        return database.extension_storeQueries
            .getCount()
            .subscribeToOne()
    }

    override suspend fun remove(indexUrl: String) {
        database.extension_storeQueries.delete(indexUrl)
    }

    private fun extensionStoreMapper(
        indexUrl: String,
        name: String,
        badgeLabel: String,
        signingKey: String,
        contactWebsite: String,
        contactDiscord: String?,
        isLegacy: Boolean,
        extensionListUrl: String?,
    ): ExtensionStore = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contact = ExtensionStore.Contact(
            website = contactWebsite,
            discord = contactDiscord,
        ),
        isLegacy = isLegacy,
        extensionListUrl = extensionListUrl,
    )

    companion object {
        private val BUILTIN_STORES = listOf(
            ExtensionStore(
                indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json",
                name = "Keiyoushi (Manga)",
                badgeLabel = "Keiyoushi",
                signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                contact = ExtensionStore.Contact(
                    website = "https://keiyoushi.github.io",
                    discord = null,
                ),
                isLegacy = true,
                extensionListUrl = null,
            ),
            ExtensionStore(
                indexUrl = "https://kohiden.xyz/Kohi-den/extensions/raw/branch/main/repo.json",
                name = "Kohi-den (Anime & Movies)",
                badgeLabel = "Kohi-den",
                signingKey = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905",
                contact = ExtensionStore.Contact(
                    website = "https://kohiden.xyz/Kohi-den/extensions",
                    discord = null,
                ),
                isLegacy = true,
                extensionListUrl = null,
            ),
            ExtensionStore(
                indexUrl = "https://raw.githubusercontent.com/yuzono/anime-repo/repo/repo.json",
                name = "Yuzono (Anime & Movies)",
                badgeLabel = "Yuzono",
                signingKey = "3b4f7d953a86b3e1f1e3522563c202a177396c41c24e4c70a835a15f87373270",
                contact = ExtensionStore.Contact(
                    website = "https://yuzono.github.io",
                    discord = null,
                ),
                isLegacy = true,
                extensionListUrl = null,
            ),
            ExtensionStore(
                indexUrl = "https://raw.githubusercontent.com/lnreader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json",
                name = "LNReader (Novels)",
                badgeLabel = "LNReader",
                signingKey = "LNREADER_BUILTIN_NOVELS",
                contact = ExtensionStore.Contact(
                    website = "https://github.com/LNReader/lnreader-plugins",
                    discord = null,
                ),
                isLegacy = true,
                extensionListUrl = null,
            ),
        )
    }
}
