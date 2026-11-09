package com.mymovieapp.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Minimal TMDB client. Only title names/years are ever sent; your files stay on the device. */
class Tmdb(private val keyProvider: () -> String) {
    private val http = OkHttpClient()

    data class Hit(val id: Int, val title: String, val year: Int?, val posterUrl: String?, val overview: String?)
    data class Info(
        val id: Int, val name: String, val year: Int?, val posterUrl: String?, val backdropUrl: String?,
        val overview: String?, val cast: String?, val rating: Double?
    )
    data class Ep(val number: Int, val name: String, val overview: String?, val stillUrl: String?)

    val hasKey: Boolean get() = keyProvider().isNotBlank()

    private fun JSONObject.str(k: String): String? =
        if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

    private fun img(p: String?, size: String) = p?.let { "https://image.tmdb.org/t/p/$size$it" }
    private fun yearOf(d: String?) = d?.take(4)?.toIntOrNull()

    private suspend fun call(path: String, params: Map<String, String> = emptyMap()): JSONObject? =
        withContext(Dispatchers.IO) {
            val key = keyProvider().trim()
            if (key.isEmpty()) return@withContext null
            val bearer = key.startsWith("eyJ")
            val url = HttpUrl.Builder().scheme("https").host("api.themoviedb.org")
                .addPathSegments("3/$path")
                .apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    if (!bearer) addQueryParameter("api_key", key)
                }.build()
            val req = Request.Builder().url(url).apply { if (bearer) header("Authorization", "Bearer $key") }.build()
            try {
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) r.body?.string()?.let { JSONObject(it) } else null
                }
            } catch (e: Exception) {
                null
            }
        }

    suspend fun search(kind: String, query: String, year: Int?): List<Hit> {
        val tv = kind == "series"
        val params = mutableMapOf("query" to query, "include_adult" to "false")
        if (year != null) params[if (tv) "first_air_date_year" else "year"] = year.toString()
        val arr = call(if (tv) "search/tv" else "search/movie", params)?.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Hit(
                o.getInt("id"),
                o.str(if (tv) "name" else "title") ?: "",
                yearOf(o.str(if (tv) "first_air_date" else "release_date")),
                img(o.str("poster_path"), "w342"),
                o.str("overview")
            )
        }
    }

    suspend fun info(kind: String, id: Int): Info? {
        val tv = kind == "series"
        val o = call("${if (tv) "tv" else "movie"}/$id", mapOf("append_to_response" to "credits")) ?: return null
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")?.let { a ->
            (0 until minOf(a.length(), 12)).mapNotNull { a.getJSONObject(it).str("name") }.joinToString(", ")
        }?.ifBlank { null }
        val rating = o.optDouble("vote_average", 0.0).takeIf { it > 0 }
        return Info(
            id, o.str(if (tv) "name" else "title") ?: "",
            yearOf(o.str(if (tv) "first_air_date" else "release_date")),
            img(o.str("poster_path"), "w500"), img(o.str("backdrop_path"), "w1280"),
            o.str("overview"), cast, rating
        )
    }

    suspend fun season(tvId: Int, season: Int): List<Ep> {
        val arr = call("tv/$tvId/season/$season")?.optJSONArray("episodes") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            Ep(o.optInt("episode_number"), o.str("name") ?: "", o.str("overview"), img(o.str("still_path"), "w300"))
        }
    }
}
