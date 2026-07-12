package com.tesla.screenflow

import fi.iki.elonen.NanoHTTPD
import java.io.IOException

/**
 * NanoHTTPD Web 服务器 — 托管前端页面给车机浏览器访问。
 */
class WebServer(
    host: String,
    port: Int,
    private val assetLoader: (String) -> String?
) : NanoHTTPD(host, port) {

    companion object {
        private const val TAG = "TeslaScreenFlow::WebServer"
    }

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                .withCorsHeaders()
        }

        if (session.method != Method.GET) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method Not Allowed"
            ).withCorsHeaders()
        }

        val uri = session.uri.trimStart('/')
        val assetPath = if (uri.isEmpty()) "index.html" else uri
        val mimeType = inferMimeType(assetPath)

        val content = try {
            assetLoader(assetPath)
        } catch (e: IOException) {
            null
        }

        return if (content != null) {
            newFixedLengthResponse(Response.Status.OK, mimeType, content).withCorsHeaders()
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404: $assetPath")
                .withCorsHeaders()
        }
    }

    private fun inferMimeType(path: String): String = when {
        path.lowercase().endsWith(".html") -> "text/html; charset=utf-8"
        path.lowercase().endsWith(".js")   -> "application/javascript"
        path.lowercase().endsWith(".css")  -> "text/css"
        path.lowercase().endsWith(".png")  -> "image/png"
        path.lowercase().endsWith(".ico")  -> "image/x-icon"
        else -> MIME_PLAINTEXT
    }

    private fun Response.withCorsHeaders(): Response {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return this
    }
}
