package com.Goyabu

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Player do Goyabu = Blogger (validado no HTML de nov/2026):
 *
 *   pagina do episodio tem inline:
 *     var playersData=[{"select":"blogger",
 *                       "url":"https:\/\/www.blogger.com\/video.g?token=<tok>",
 *                       "blogger_token":"<blob>"}]
 *
 *   A pagina do BLOGGER (sem Cloudflare!) traz os MP4 do googlevideo direto:
 *     https://rr*.googlevideo.com/videoplayback?expire=...&itag=18|22&...
 *   (itags 18=360p e 22=720p vistos ao vivo; o mapa abaixo cobre os comuns)
 *
 *   Caminho extra (admin-ajax do site):
 *     POST action=decode_blogger_video&token=<blogger_token>
 *     -> {"success":true,"data":{"play":[{"size":720,"src":"...mp4"}]}}
 */
object GoyabuExtractor {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Mobile Safari/537.36"

    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        133 to 240, 134 to 360, 135 to 480, 136 to 720,
        137 to 1080, 160 to 144, 242 to 240, 243 to 360,
        244 to 480, 247 to 720, 248 to 1080
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "User-Agent" to UA,
                "Referer" to "https://goyabu.io/"
            )
            val html = app.get(url, headers = headers).text
            var emitted = false

            val bloggerUrl = Regex("\"url\"\\s*:\\s*\"(https:[^\"]*?blogger\\.com[^\"]*)\"")
                .find(html)?.groupValues?.get(1)?.unescapeJs()

            // 1) scrape DIRETO da pagina do blogger: mp4 do googlevideo
            if (!bloggerUrl.isNullOrBlank()) {
                try {
                    val page = app.get(
                        bloggerUrl,
                        headers = mapOf("User-Agent" to UA, "Referer" to "https://goyabu.io/")
                    ).text
                    val norm = page.unescapeHtml()
                    Regex("https://[^\"'\\s<>]+googlevideo\\.com/[^\"'\\s<>]+")
                        .findAll(norm).map { it.value.trimEnd('\\') }.distinct().forEach { u ->
                            if (!u.contains("videoplayback")) return@forEach
                            val itag = Regex("[?&]itag=(\\d+)").find(u)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val q = itagQualityMap[itag] ?: 0
                            callback(
                                newExtractorLink("Goyabu", "$name (${if (q > 0) "${q}p" else "SD"})", u, ExtractorLinkType.VIDEO) {
                                    this.quality = q
                                    this.referer = "https://www.blogger.com/"
                                    this.headers = mapOf(
                                        "User-Agent" to UA,
                                        "Referer" to "https://www.blogger.com/"
                                    )
                                }
                            )
                            emitted = true
                        }
                } catch (_: Exception) {
                }
            }

            // 2) decode pelo site (admin-ajax) — bonus, traz mais qualidades
            if (!emitted) {
                val token = Regex("\"blogger_token\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                if (!token.isNullOrBlank()) {
                    try {
                        val resp = app.post(
                            "https://goyabu.io/wp-admin/admin-ajax.php",
                            headers = headers + mapOf("X-Requested-With" to "XMLHttpRequest"),
                            data = mapOf("action" to "decode_blogger_video", "token" to token)
                        ).text
                        val play = JSONObject(resp).optJSONObject("data")?.optJSONArray("play") ?: JSONArray()
                        for (i in 0 until play.length()) {
                            val p = play.optJSONObject(i) ?: continue
                            val src = p.optString("src")
                            if (!src.startsWith("http")) continue
                            val q = p.optInt("size", 0)
                            val label = p.optString("sizeText").ifBlank { if (q > 0) "${q}p" else "SD" }
                            callback(
                                newExtractorLink("Goyabu", "$name ($label)", src, ExtractorLinkType.VIDEO) {
                                    this.quality = q
                                    this.referer = "https://goyabu.io/"
                                }
                            )
                            emitted = true
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // 3) embed do blogger pelos extratores nativos — ultimo recurso
            if (!emitted && !bloggerUrl.isNullOrBlank()) {
                try {
                    loadExtractor(bloggerUrl, "https://goyabu.io/", { }, callback)
                    emitted = true
                } catch (_: Exception) {
                }
            }
            emitted
        } catch (e: Exception) {
            false
        }
    }

    /** desescapes JS usados nos JSONs inline (\/ \u003d \u0026 ...). */
    private fun String.unescapeJs(): String = this
        .replace("\\/", "/")
        .replace("\\u003d", "=").replace("\\u003D", "=")
        .replace("\\u0026", "&")
        .replace("\\u0025", "%")
        .replace("\\u002F", "/").replace("\\u003A", ":")

    /** HTML entities + desescapes JS (as configs vem &quot;-escapadas). */
    private fun String.unescapeHtml(): String = this
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .unescapeJs()
}
