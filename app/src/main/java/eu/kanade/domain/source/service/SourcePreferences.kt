package eu.kanade.domain.source.service

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.system.LocaleHelper
import mihon.domain.extension.model.ContentWarning
import mihon.domain.migration.models.MigrationFlag
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.core.common.preference.getEnumSet
import tachiyomi.core.common.preference.getLongArray
import tachiyomi.domain.library.model.LibraryDisplayMode

@Inject
@SingleIn(AppScope::class)
class SourcePreferences(
    preferenceStore: PreferenceStore,
) {

    val sourceDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "pref_display_mode_catalogue",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    val enabledLanguages: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_languages",
        LocaleHelper.getDefaultEnabledLanguages(),
    )

    val disabledSources: Preference<Set<String>> = preferenceStore.getStringSet("hidden_catalogues", emptySet())

    val incognitoExtensions: Preference<Set<String>> = preferenceStore.getStringSet("incognito_extensions", emptySet())

    val pinnedSources: Preference<Set<String>> = preferenceStore.getStringSet("pinned_catalogues", emptySet())

    val defaultSourcesPinned: Preference<Boolean> = preferenceStore.getBoolean("default_sources_pinned", false)

    val lastUsedSource: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("last_catalogue_source"),
        -1,
    )

    val enabledContentWarnings: Preference<Set<ContentWarning>> = preferenceStore.getEnumSet(
        "enabled_content_warnings",
        setOf(ContentWarning.SAFE, ContentWarning.MIXED, ContentWarning.NSFW),
    )

    val applyContentWarningsToInstalled: Preference<Boolean> = preferenceStore.getBoolean(
        "apply_content_warnings_to_installed",
        true,
    )

    val migrationSortingMode: Preference<SetMigrateSorting.Mode> = preferenceStore.getEnum(
        "pref_migration_sorting",
        SetMigrateSorting.Mode.ALPHABETICAL,
    )

    val migrationSortingDirection: Preference<SetMigrateSorting.Direction> = preferenceStore.getEnum(
        "pref_migration_direction",
        SetMigrateSorting.Direction.ASCENDING,
    )

    val hideInLibraryItems: Preference<Boolean> = preferenceStore.getBoolean("browse_hide_in_library_items", false)

    val extensionRepos: Preference<Set<String>> = preferenceStore.getStringSet("extension_repos", emptySet())

    val extensionUpdatesCount: Preference<Int> = preferenceStore.getInt("ext_updates_count", 0)

    val homeSourceName: Preference<String> = preferenceStore.getString("home_source_name", "Comix.to")
    val homeSourceId: Preference<Long> = preferenceStore.getLong("home_source_id", -1L)

    val contentRating: Preference<String> = preferenceStore.getString(
        "pref_extension_content_rating",
        CONTENT_RATING_SUGGESTIVE,
    )

    val trustedExtensions: Preference<Set<String>> = preferenceStore.getStringSet(
        Preference.appStateKey("trusted_extensions"),
        emptySet(),
    )

    val globalSearchFilterState: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("has_filters_toggle_state"),
        false,
    )

    val migrationSources: Preference<List<Long>> = preferenceStore.getLongArray("migration_sources", emptyList())

    val migrationFlags: Preference<Set<MigrationFlag>> = preferenceStore.getObjectFromInt(
        key = "migration_flags",
        defaultValue = MigrationFlag.entries.toSet(),
        serializer = { MigrationFlag.toBit(it) },
        deserializer = { value: Int -> MigrationFlag.fromBit(value) },
    )

    val migrationDeepSearchMode: Preference<Boolean> = preferenceStore.getBoolean("migration_deep_search", false)

    val migrationPrioritizeByChapters: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_prioritize_by_chapters",
        false,
    )

    val migrationHideUnmatched: Preference<Boolean> = preferenceStore.getBoolean("migration_hide_unmatched", false)

    val migrationHideWithoutUpdates: Preference<Boolean> = preferenceStore.getBoolean(
        "migration_hide_without_updates",
        false,
    )

    companion object {
        const val CONTENT_RATING_SAFE = "safe"
        const val CONTENT_RATING_SUGGESTIVE = "suggestive"
        const val CONTENT_RATING_EROTICA = "erotica"
        const val CONTENT_RATING_PORNOGRAPHIC = "pornographic"

        private val EXPLICIT_TAGS = setOf(
            "pornographic",
            "hentai",
            "erotica",
            "smut",
            "nsfw",
            "adult",
            "18+",
            "r18",
            "rx",
            "content rating: pornographic",
            "content rating: erotica",
        )

        private val SUGGESTIVE_TAGS = setOf(
            "suggestive",
            "ecchi",
            "mature",
            "borderline h",
            "content rating: suggestive",
        )

        fun contentWarningsForRating(rating: String): Set<ContentWarning> = when (rating) {
            CONTENT_RATING_SAFE -> setOf(ContentWarning.SAFE)
            CONTENT_RATING_SUGGESTIVE -> setOf(ContentWarning.SAFE, ContentWarning.MIXED)
            else -> ContentWarning.entries.toSet()
        }

        fun isMangaAllowedByContentRating(genres: List<String>?, rating: String): Boolean {
            if (genres.isNullOrEmpty()) return true
            if (rating == CONTENT_RATING_PORNOGRAPHIC || rating.isBlank()) return true
            val normalized = genres
                .asSequence()
                .flatMap { it.split(',').asSequence() }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (rating == CONTENT_RATING_EROTICA) {
                return "pornographic" !in normalized &&
                    "hentai" !in normalized &&
                    "content rating: pornographic" !in normalized
            }
            if (normalized.any { it in EXPLICIT_TAGS }) return false
            if (rating == CONTENT_RATING_SAFE && normalized.any { it in SUGGESTIVE_TAGS }) return false
            return true
        }

        fun syncExtensionContentRating(context: Context, sources: List<Source>, rating: String) {
            sources.forEach { source ->
                context.getSharedPreferences("source_${source.id}", Context.MODE_PRIVATE)
                    .edit()
                    .putString("pref_content_rating", rating)
                    .apply()
            }
        }
    }
}
