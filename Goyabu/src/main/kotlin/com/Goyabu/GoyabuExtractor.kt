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
 * Player do Goyabu = Blogger decodificado pelo PROPRIO site (validado no HTML
 * de nov/2026):
 *
 *   pagina do episodio tem inline:
 *     var playersData=[{"select":"blogger",
 *                       "url":"https://www.blogger.com/video.g?token=...",
 *                       "blogger_token":"<blob base64>"}]
 *
 *   POST https://goyabu.io/wp-admin/admin-ajax.php
 *        action=decode_blogger_video&token=<blogger_token>
 *   -> {"success":true,"data":{"play":[{"size":720,"sizeText":"HD",
 *        "src":"https://...googlevideo.../x.mp4?expire=...","type":"video/mp4"}]}}
 *
 *   src = MP4 direto por qualidade (expira por expire=).
 *   Fallback: data-blogger-url-encrypted = atob(segredo).reversed() -> embed
 *   do blogger, resolvido pelos extratores nativos do app.
 */
object GoyabuExtractor {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0 Mobile Safari/537.36"

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

            // 1) decode pelo site -> MP4 direto por qualidade
            val token = Regex("\"blogger_token\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (!token.isNullOrBlank()) {
                try {
                    val resp = app.post(
                        "https://goyabu.io/wp-admin/admin-ajax.php",
                        headers = headers,
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

            // 2) fallback: embed do blogger (url direta ou url_encrypted)
            if (!emitted) {
                val embed = Regex("\"url\"\\s*:\\s*\"(https:[^\"]*blogger[^\"]*)\"")
                    .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: Regex("data-blogger-url-encrypted=\"([^\"]+)\"")
                        .find(html)?.groupValues?.get(1)?.let { decodeEncryptedUrl(it) }
                if (!embed.isNullOrBlank()) {
                    try {
                        loadExtractor(embed, "https://goyabu.io/", { }, callback)
                        emitted = true
                    } catch (_: Exception) {
                    }
                }
            }
            emitted
        } catch (e: Exception) {
            false
        }
    }

    /** data-blogger-url-encrypted = atob(...) + reverse. */
    private fun decodeEncryptedUrl(data: String): String? = try {
        String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8).reversed()
    } catch (e: Exception) {
        null
    }
}
