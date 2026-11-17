package eu.kanade.tachiyomi.source.anime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Presents an extension's [AnimeFilterList] to the browse UI as a [FilterList].
 *
 * Each mapped filter keeps the extension's own filter instance so that custom subclasses and
 * groups survive the round trip. [toAnimeFilterList] copies the UI state back before a search.
 */
internal object AnimeFilterMapper {

    fun toFilterList(animeFilters: AnimeFilterList): FilterList {
        return FilterList(animeFilters.map { it.toMappedFilter() })
    }

    /**
     * Returns null when [filters] didn't come from [toFilterList], so the caller can fall back to
     * the source's defaults instead of silently dropping every filter.
     */
    fun toAnimeFilterList(filters: FilterList): AnimeFilterList? {
        val mapped = filters.map { it as? MappedAnimeFilter ?: return null }
        mapped.forEach { it.sync() }
        return AnimeFilterList(mapped.map { it.target })
    }
}

private interface MappedAnimeFilter {
    val target: AnimeFilter<*>

    fun sync()
}

private fun AnimeFilter<*>.toMappedFilter(): Filter<*> {
    return when (this) {
        is AnimeFilter.Header -> MappedHeader(name, this)
        is AnimeFilter.Separator -> MappedSeparator(name, this)
        is AnimeFilter.Select<*> -> MappedSelect(
            name = name,
            values = values.map { it.toString() }.toTypedArray(),
            state = state,
            target = this,
        )
        is AnimeFilter.Text -> MappedText(name, state, this)
        is AnimeFilter.CheckBox -> MappedCheckBox(name, state, this)
        is AnimeFilter.TriState -> MappedTriState(name, state, this)
        is AnimeFilter.Sort -> MappedSort(
            name = name,
            values = values,
            state = state?.let { Filter.Sort.Selection(it.index, it.ascending) },
            target = this,
        )
        is AnimeFilter.Group<*> -> MappedGroup(
            name = name,
            children = state.filterIsInstance<AnimeFilter<*>>().map { it.toMappedFilter() },
            target = this,
        )
    }
}

private class MappedHeader(
    name: String,
    override val target: AnimeFilter.Header,
) : Filter.Header(name), MappedAnimeFilter {
    override fun sync() = Unit
}

private class MappedSeparator(
    name: String,
    override val target: AnimeFilter.Separator,
) : Filter.Separator(name), MappedAnimeFilter {
    override fun sync() = Unit
}

private class MappedSelect(
    name: String,
    values: Array<String>,
    state: Int,
    override val target: AnimeFilter.Select<*>,
) : Filter.Select<String>(name, values, state), MappedAnimeFilter {
    override fun sync() {
        target.state = state
    }
}

private class MappedText(
    name: String,
    state: String,
    override val target: AnimeFilter.Text,
) : Filter.Text(name, state), MappedAnimeFilter {
    override fun sync() {
        target.state = state
    }
}

private class MappedCheckBox(
    name: String,
    state: Boolean,
    override val target: AnimeFilter.CheckBox,
) : Filter.CheckBox(name, state), MappedAnimeFilter {
    override fun sync() {
        target.state = state
    }
}

private class MappedTriState(
    name: String,
    state: Int,
    override val target: AnimeFilter.TriState,
) : Filter.TriState(name, state), MappedAnimeFilter {
    override fun sync() {
        target.state = state
    }
}

private class MappedSort(
    name: String,
    values: Array<String>,
    state: Filter.Sort.Selection?,
    override val target: AnimeFilter.Sort,
) : Filter.Sort(name, values, state), MappedAnimeFilter {
    override fun sync() {
        target.state = state?.let { AnimeFilter.Sort.Selection(it.index, it.ascending) }
    }
}

private class MappedGroup(
    name: String,
    children: List<Filter<*>>,
    override val target: AnimeFilter.Group<*>,
) : Filter.Group<Filter<*>>(name, children), MappedAnimeFilter {
    override fun sync() {
        state.forEach { (it as? MappedAnimeFilter)?.sync() }
    }
}
