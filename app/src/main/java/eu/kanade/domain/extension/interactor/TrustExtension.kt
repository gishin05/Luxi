package eu.kanade.domain.extension.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dev.zacsweers.metro.Inject
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.getAndSet

@Inject
class TrustExtension(
    private val repository: ExtensionStoreRepository,
    private val preferences: SourcePreferences,
) {

    fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>, stores: List<ExtensionStore>): Boolean {
        if (ExtensionLoader.isProtectedBuiltIn(pkgInfo.packageName)) return true
        val trustedFingerprints = stores.mapTo(HashSet()) { it.signingKey }
        trustedFingerprints += LUXI_SIGNING_KEY
        trustedFingerprints += KEIYOUSHI_SIGNING_KEY
        trustedFingerprints += KOHIDEN_SIGNING_KEY
        trustedFingerprints += YUZONO_SIGNING_KEY
        val fingerprint = fingerprints.lastOrNull() ?: return false
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:$fingerprint"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions.get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions.getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun revokeAll() {
        preferences.trustedExtensions.delete()
    }

    /**
     * Emits whenever what counts as trusted changes, either because a store was added or removed or
     * because an extension was trusted or had its trust revoked. Both sources replay their current
     * value, which is dropped.
     */
    fun changes(): Flow<Unit> {
        return merge(
            // Stores are rewritten whenever their index is refreshed, so only their keys matter here
            repository.getAllAsFlow()
                .map { stores -> stores.mapTo(HashSet()) { it.signingKey } }
                .distinctUntilChanged()
                .drop(1),
            preferences.trustedExtensions.changes()
                .distinctUntilChanged()
                .drop(1),
        )
            .map {}
    }

    private companion object {
        const val LUXI_SIGNING_KEY = "8bf8a0a410d83a1d23de76f89da5cfedf3f1d867f5f575a078c4e42a5cb9aee2"
        const val KEIYOUSHI_SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val KOHIDEN_SIGNING_KEY = "cbec121aa82ebb02aaa73806992e0368a97d47b5451ed6524816d03084c45905"
        const val YUZONO_SIGNING_KEY = "3b4f7d953a86b3e1f1e3522563c202a177396c41c24e4c70a835a15f87373270"
    }
}
