package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.net.Uri
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.nullArray
import com.github.salomonbrys.kotson.nullLong
import com.github.salomonbrys.kotson.nullObj
import com.github.salomonbrys.kotson.nullString
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.LewdSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.NHENTAI_SOURCE_ID
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.NHentaiSearchMetadata.Companion.TAG_TYPE_DEFAULT
import exh.metadata.metadata.base.RaisedTag
import exh.util.urlImportFetchSearchManga
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable

/**
 * NHentai source
 */

class NHentai(context: Context) : HttpSource(), LewdSource<NHentaiSearchMetadata, Response>, UrlImportableSource {
    override val metaClass = NHentaiSearchMetadata::class

    override val client: OkHttpClient = network.cloudflareClient.newBuilder().build()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        // TODO There is currently no way to get the most popular mangas
        // TODO Instead, we delegate this to the latest updates thing to avoid confusing users with an empty screen
        return fetchLatestUpdates(page)
    }

    override fun popularMangaRequest(page: Int) = throw UnsupportedOperationException()

    override fun popularMangaParse(response: Response) = throw UnsupportedOperationException()

    // Support direct URL importing
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        val trimmedIdQuery = query.trim().removePrefix("id:")
        val newQuery = if (trimmedIdQuery.toIntOrNull() ?: -1 >= 0) {
            "$baseUrl/g/$trimmedIdQuery/"
        } else query

        return urlImportFetchSearchManga(newQuery) {
            searchMangaRequestObservable(page, query, filters).flatMap {
                client.newCall(it).asObservableSuccess()
            }.map { response ->
                searchMangaParse(response)
            }
        }
    }

    private fun searchMangaRequestObservable(page: Int, query: String, filters: FilterList): Observable<Request> {
        val langFilter = filters.filterIsInstance<filterLang>().firstOrNull()
        var langFilterString = ""
        if (langFilter != null) {
            langFilterString = SOURCE_LANG_LIST.first { it.first == langFilter.values[langFilter.state] }.second
        }

        val uri = if (query.isNotBlank()) {
            Uri.parse("$baseUrl/search/").buildUpon().apply {
                appendQueryParameter("q", query + langFilterString)
            }
        } else {
            Uri.parse(baseUrl).buildUpon()
        }

        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()?.state
            ?: defaultSortFilterSelection()

        if (sortFilter.index == 1) {
            if (query.isBlank()) error("You must specify a search query if you wish to sort by popularity!")
            uri.appendQueryParameter("sort", "popular")
        }

        if (sortFilter.ascending) {
            return client.newCall(nhGet(uri.toString()))
                .asObservableSuccess()
                .map {
                    val doc = it.asJsoup()

                    val lastPage = doc.selectFirst(".last")
                        ?.attr("href")
                        ?.substringAfterLast('=')
                        ?.toIntOrNull() ?: 1

                    val thisPage = lastPage - (page - 1)

                    uri.appendQueryParameter(REVERSE_PARAM, (thisPage > 1).toString())
                    uri.appendQueryParameter("page", thisPage.toString())

                    nhGet(uri.toString(), page)
                }
        }

        uri.appendQueryParameter("page", page.toString())

        return Observable.just(nhGet(uri.toString(), page))
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = throw UnsupportedOperationException()

    override fun searchMangaParse(response: Response) = parseResultPage(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val uri = Uri.parse(baseUrl).buildUpon()
        uri.appendQueryParameter("page", page.toString())
        return nhGet(uri.toString(), page)
    }

    override fun latestUpdatesParse(response: Response) = parseResultPage(response)

    override fun mangaDetailsParse(response: Response) = throw UnsupportedOperationException()

    /**
     * Returns an observable with the updated details for a manga. Normally it's not needed to
     * override this method.
     *
     * @param manga the manga to be updated.
     */
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(mangaDetailsRequest(manga))
            .asObservableSuccess()
            .flatMap {
                parseToManga(manga, it).andThen(
                    Observable.just(
                        manga.apply {
                            initialized = true
                        }
                    )
                )
            }
    }

    override fun mangaDetailsRequest(manga: SManga) = nhGet(baseUrl + manga.url)

    fun parseResultPage(response: Response): MangasPage {
        val doc = response.asJsoup()

        // TODO Parse lang + tags

        val mangas = doc.select(".gallery > a").map {
            SManga.create().apply {
                url = it.attr("href")

                title = it.selectFirst(".caption")!!.text()

                // last() is a hack to ignore the lazy-loader placeholder image on the front page
                thumbnail_url = it.select("img").last()!!.attr("src")
                // In some pages, the thumbnail url does not include the protocol
                if (!thumbnail_url!!.startsWith("https:")) thumbnail_url = "https:$thumbnail_url"
            }
        }

        val hasNextPage = if (!response.request.url.queryParameterNames.contains(REVERSE_PARAM)) {
            doc.selectFirst(".next") != null
        } else {
            response.request.url.queryParameter(REVERSE_PARAM)!!.toBoolean()
        }

        return MangasPage(mangas, hasNextPage)
    }

    override fun parseIntoMetadata(metadata: NHentaiSearchMetadata, input: Response) {
        val json = GALLERY_JSON_REGEX.find(input.body!!.string())!!.groupValues[1].replace(UNICODE_ESCAPE_REGEX, { it.groupValues[1].toInt(radix = 16).toChar().toString() })
        val obj = JsonParser.parseString(json).asJsonObject

        with(metadata) {
            nhId = obj["id"].asLong

            uploadDate = obj["upload_date"].nullLong

            favoritesCount = obj["num_favorites"].nullLong

            mediaId = obj["media_id"].nullString

            obj["title"].nullObj?.let { title ->
                japaneseTitle = title["japanese"].nullString
                shortTitle = title["pretty"].nullString
                englishTitle = title["english"].nullString
            }

            obj["images"].nullObj?.let {
                coverImageType = it["cover"]?.get("t").nullString
                it["pages"].nullArray?.mapNotNull {
                    it?.asJsonObject?.get("t").nullString
                }?.let {
                    pageImageTypes = it
                }
                thumbnailImageType = it["thumbnail"]?.get("t").nullString
            }

            scanlator = obj["scanlator"].nullString

            obj["tags"]?.asJsonArray?.map {
                val asObj = it.asJsonObject
                Pair(asObj["type"].nullString, asObj["name"].nullString)
            }?.apply {
                tags.clear()
            }?.forEach {
                if (it.first != null && it.second != null) {
                    tags.add(RaisedTag(it.first!!, it.second!!, TAG_TYPE_DEFAULT))
                }
            }
        }
    }

    fun getOrLoadMetadata(mangaId: Long?, nhId: Long) = getOrLoadMetadata(mangaId) {
        client.newCall(nhGet(baseUrl + NHentaiSearchMetadata.nhIdToPath(nhId)))
            .asObservableSuccess()
            .toSingle()
    }

    override fun fetchChapterList(manga: SManga) = Observable.just(
        listOf(
            SChapter.create().apply {
                url = manga.url
                name = "Chapter"
                chapter_number = 1f
            }
        )
    )

    override fun fetchPageList(chapter: SChapter) = getOrLoadMetadata(chapter.mangaId, NHentaiSearchMetadata.nhUrlToId(chapter.url)).map { metadata ->
        if (metadata.mediaId == null) {
            emptyList()
        } else {
            metadata.pageImageTypes.mapIndexed { index, s ->
                val imageUrl = imageUrlFromType(metadata.mediaId!!, index + 1, s)
                Page(index, imageUrl!!, imageUrl)
            }
        }
    }.toObservable()

    override fun fetchImageUrl(page: Page) = Observable.just(page.imageUrl!!)!!

    fun imageUrlFromType(mediaId: String, page: Int, t: String) = NHentaiSearchMetadata.typeToExtension(t)?.let {
        "https://i.nhentai.net/galleries/$mediaId/$page.$it"
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        throw NotImplementedError("Unused method called!")
    }

    override fun pageListParse(response: Response): List<Page> {
        throw NotImplementedError("Unused method called!")
    }

    override fun imageUrlParse(response: Response): String {
        throw NotImplementedError("Unused method called!")
    }

    override fun getFilterList() = FilterList(SortFilter(), filterLang())

    // language filtering
    private class filterLang : Filter.Select<String>("Language", SOURCE_LANG_LIST.map { it.first }.toTypedArray())

    class SortFilter : Filter.Sort(
        "Sort",
        arrayOf("Date", "Popular"),
        defaultSortFilterSelection()
    )

    val appName by lazy {
        context.getString(R.string.app_name)
    }

    fun nhGet(url: String, tag: Any? = null) = GET(url, headers)
        .newBuilder()
        .tag(tag).build()

    override val id = NHENTAI_SOURCE_ID

    override val lang = "all"

    override val name = "nhentai"

    override val baseUrl = NHentaiSearchMetadata.BASE_URL

    override val supportsLatest = true

    // === URL IMPORT STUFF

    override val matchingHosts = listOf(
        "nhentai.net"
    )

    override fun mapUrlToMangaUrl(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull()?.toLowerCase() != "g") {
            return null
        }

        return "$baseUrl/g/${uri.pathSegments[1]}/"
    }

    companion object {
        private val GALLERY_JSON_REGEX = Regex(".parse\\(\"(.*)\"\\);")
        private val UNICODE_ESCAPE_REGEX = Regex("\\\\u([0-9a-fA-F]{4})")
        private const val REVERSE_PARAM = "TEH_REVERSE"

        private fun defaultSortFilterSelection() = Filter.Sort.Selection(0, false)

        private val SOURCE_LANG_LIST = listOf(
            Pair("All", ""),
            Pair("English", " english"),
            Pair("Japanese", " japanese"),
            Pair("Chinese", " chinese")
        )
    }
}
