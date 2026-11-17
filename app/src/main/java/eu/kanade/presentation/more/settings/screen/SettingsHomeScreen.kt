package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.more.settings.Preference
import kotlinx.collections.immutable.persistentMapOf
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsHomeScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_home

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val sourcePreferences = remember { context.appGraph.sourcePreferences }
        val sourceManager = remember { context.appGraph.sourceManager }

        val ratingEntries = persistentMapOf(
            SourcePreferences.CONTENT_RATING_SAFE to stringResource(MR.strings.pref_content_rating_safe),
            SourcePreferences.CONTENT_RATING_SUGGESTIVE to stringResource(MR.strings.pref_content_rating_suggestive),
            SourcePreferences.CONTENT_RATING_EROTICA to stringResource(MR.strings.pref_content_rating_erotica),
            SourcePreferences.CONTENT_RATING_PORNOGRAPHIC to stringResource(
                MR.strings.pref_content_rating_pornographic,
            ),
        )

        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_content_rating_title),
                preferenceItems = listOf(
                    Preference.PreferenceItem.ListPreference(
                        preference = sourcePreferences.contentRating,
                        entries = ratingEntries,
                        title = stringResource(MR.strings.pref_content_rating_title),
                        subtitle = "%s",
                        onValueChanged = { newValue ->
                            val sources = sourceManager.getAll()
                            SourcePreferences.syncExtensionContentRating(context, sources, newValue)
                            sourcePreferences.enabledContentWarnings.set(
                                SourcePreferences.contentWarningsForRating(newValue),
                            )
                            true
                        },
                    ),
                    Preference.PreferenceItem.InfoPreference(
                        stringResource(MR.strings.pref_content_rating_summary),
                    ),
                ),
            ),
        )
    }
}
