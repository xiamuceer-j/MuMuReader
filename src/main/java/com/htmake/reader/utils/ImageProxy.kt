package com.htmake.reader.utils

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.SSLHelper
import io.legado.app.help.http.getProxyClient
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 远程图片(封面/图标)代理下载工具
 *
 * 相比直接使用 WebClient.getAbs 做了以下加固：
 * 1. 只允许 http/https，protocol-relative 地址补全为 https
 * 2. 解析目标地址并阻断内网/回环/链路本地地址，防止 SSRF
 * 3. 使用固定的 DNS 解析结果连接，避免 DNS rebinding 绕过校验
 * 4. 手动跟随重定向，每一跳都重新做安全校验
 * 5. 校验 HTTP 状态码、Content-Type 与文件头，拒绝把错误页当图片缓存
 * 6. 限制响应体大小，避免超大文件打满磁盘
 * 7. 携带 UA / Referer，绕过常见防盗链
 */
object ImageProxy {

    /**
     * 允许缓存的图片类型 -> 文件扩展名
     *
     * 故意不支持 svg：svg 可内嵌脚本，由本站同源返回会形成存储型 XSS
     */
    private val CONTENT_TYPE_EXT = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/pjpeg" to "jpg",
        "image/png" to "png",
        "image/apng" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/bmp" to "bmp",
        "image/x-ms-bmp" to "bmp",
        "image/x-icon" to "ico",
        "image/vnd.microsoft.icon" to "ico",
        "image/avif" to "avif",
        "image/heic" to "heic",
        "image/heif" to "heic",
        "image/tiff" to "tiff"
    )

    /** 查找缓存文件时探测的扩展名 */
    val KNOWN_IMAGE_EXT = listOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico", "avif", "heic", "tiff"
    )

    class ImageResult(val bytes: ByteArray, val ext: String, val contentType: String)

    /**
     * 继承 IOException，以便从 Dns.lookup / okhttp 调用栈中正常抛出
     */
    class ImageFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

    /**
     * 规范化图片地址
     *
     * 使用 OkHttp 的 HttpUrl 解析，可以容忍原始封面地址中的空格与非 ASCII 字符，
     * 同时保证只有 http/https 能通过。
     *
     * @return 规范化后的绝对地址，非法时返回 null
     */
    fun normalizeUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) {
            return null
        }
        var url = rawUrl.trim()
        // 拒绝含控制字符的地址，防止请求头注入
        if (url.any { it.code < 0x20 || it.code == 0x7f }) {
            return null
        }
        // protocol-relative 地址补全，Vert.x/OkHttp 都不接受 // 开头的绝对地址
        if (url.startsWith("//")) {
            url = "https:$url"
        }
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return null
        }
        if (httpUrl.host.isBlank()) {
            return null
        }
        return httpUrl.toString()
    }

    /**
     * 判断地址是否属于内网/回环/链路本地等不应被服务端主动访问的地址
     */
    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet4Address) {
            val b0 = bytes[0].toInt() and 0xff
            val b1 = bytes[1].toInt() and 0xff
            // 100.64.0.0/10 运营商级 NAT
            if (b0 == 100 && b1 in 64..127) {
                return true
            }
            // 192.0.0.0/24, 192.0.2.0/24, 198.18.0.0/15, 198.51.100.0/24, 203.0.113.0/24
            if (b0 == 192 && b1 == 0) {
                return true
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true
            }
            if (b0 == 198 && b1 == 51) {
                return true
            }
            if (b0 == 203 && b1 == 0) {
                return true
            }
            // 240.0.0.0/4 保留段
            if (b0 >= 240) {
                return true
            }
        } else if (address is Inet6Address) {
            // fc00::/7 唯一本地地址
            if ((bytes[0].toInt() and 0xfe) == 0xfc) {
                return true
            }
        }
        return false
    }

    /**
     * 校验并解析主机名，返回可用于连接的地址列表
     */
    private fun resolveAndCheck(host: String, allowPrivate: Boolean): List<InetAddress> {
        val addressList = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: UnknownHostException) {
            throw ImageFetchException("无法解析主机: $host", e)
        }
        if (addressList.isEmpty()) {
            throw ImageFetchException("无法解析主机: $host")
        }
        if (allowPrivate) {
            return addressList
        }
        val safeList = addressList.filterNot { isPrivateAddress(it) }
        if (safeList.isEmpty()) {
            throw ImageFetchException("拒绝访问内网地址: $host")
        }
        return safeList
    }

    /**
     * 构建限定 DNS 解析结果的 http client，避免解析校验后目标地址被改写
     *
     * 连接池/线程池复用成本较高，按参数组合缓存
     */
    private val clientCache = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    private fun buildClient(
        timeout: Long,
        allowPrivate: Boolean,
        proxy: String?
    ): OkHttpClient {
        val cacheKey = "$timeout-$allowPrivate-${proxy ?: ""}"
        return clientCache.computeIfAbsent(cacheKey) {
            val proxyHost = proxy?.let {
                Regex("^(?:http|socks4|socks5)://(.+?):\\d{2,5}(?:@.*@.*)?$")
                    .find(it)?.groupValues?.get(1)
            }
            val specs = arrayListOf(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT
            )
            getProxyClient(proxy).newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout * 2, TimeUnit.MILLISECONDS)
                .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
                .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
                .connectionSpecs(specs)
                // 手动跟随重定向，保证每一跳都做安全校验
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(true)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        // 私有代理地址是合法配置；目标主机已在每次请求前单独执行 SSRF 校验。
                        if (hostname == proxyHost) {
                            return InetAddress.getAllByName(hostname).toList()
                        }
                        return resolveAndCheck(hostname, allowPrivate)
                    }
                })
                .build()
        }
    }

    /**
     * 下载远程图片
     *
     * @param rawUrl        原始图片地址
     * @param timeout       单次请求超时(毫秒)
     * @param maxSize       允许的最大字节数
     * @param maxRedirect   最大重定向次数
     * @param allowPrivate  是否允许访问内网地址
     */
    fun fetch(
        rawUrl: String,
        timeout: Long = 8000L,
        maxSize: Long = 10L * 1024 * 1024,
        maxRedirect: Int = 5,
        allowPrivate: Boolean = false,
        source: BookSource? = null,
        referer: String? = null
    ): ImageResult {
        var currentUrl = normalizeUrl(rawUrl)
            ?: throw ImageFetchException("非法的图片地址: $rawUrl")

        val sourceHeaders = source?.getHeaderMap(true)?.toMutableMap() ?: mutableMapOf()
        val proxy = sourceHeaders.remove("proxy")
        val cookie = source?.getKey()?.let { CookieStore.getCookie(it) }
        if (!cookie.isNullOrBlank()) {
            sourceHeaders["Cookie"] = mergeCookie(cookie, sourceHeaders["Cookie"])
        }
        val client = buildClient(timeout, allowPrivate, proxy)
        var redirectCount = 0

        while (true) {
            val url = URL(currentUrl)
            // 使用代理时 OkHttp 的 DNS 只解析代理服务器，目标地址仍需在发送请求前显式校验。
            resolveAndCheck(url.host, allowPrivate)
            val fallbackReferer = source?.bookSourceUrl?.takeIf { normalizeUrl(it) != null }
                ?: "${url.protocol}://${url.authority}/"
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header(AppConst.UA_NAME, AppConst.userAgent)
                .header("Referer", normalizeUrl(referer) ?: fallbackReferer)
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            sourceHeaders.forEach { (name, value) ->
                if (isForwardableHeader(name, value)) {
                    requestBuilder.header(name, value)
                }
            }
            val request = requestBuilder.get().build()

            val response = try {
                client.newCall(request).execute()
            } catch (e: ImageFetchException) {
                throw e
            } catch (e: IOException) {
                // OkHttp 会把 Dns 抛出的异常包装成 UnknownHostException
                val cause = e.cause
                if (cause is ImageFetchException) {
                    throw cause
                }
                throw ImageFetchException("图片下载失败: ${e.message}", e)
            }

            response.use {
                val code = it.code
                if (code in 300..399) {
                    val location = it.header("Location")
                        ?: throw ImageFetchException("重定向缺少 Location: $currentUrl")
                    if (redirectCount >= maxRedirect) {
                        throw ImageFetchException("重定向次数过多: $rawUrl")
                    }
                    redirectCount++
                    val next = try {
                        URL(URL(currentUrl), location).toString()
                    } catch (e: Exception) {
                        throw ImageFetchException("非法的重定向地址: $location", e)
                    }
                    currentUrl = normalizeUrl(next)
                        ?: throw ImageFetchException("非法的重定向地址: $location")
                    return@use
                }

                if (!it.isSuccessful) {
                    throw ImageFetchException("图片下载失败，状态码: $code")
                }

                val contentType = (it.header("Content-Type") ?: "")
                    .substringBefore(";")
                    .trim()
                    .lowercase()

                val declaredLength = it.header("Content-Length")?.toLongOrNull()
                if (declaredLength != null && declaredLength > maxSize) {
                    throw ImageFetchException("图片体积过大: $declaredLength > $maxSize")
                }

                val body = it.body ?: throw ImageFetchException("图片内容为空")
                val bytes = readAtMost(body.byteStream(), maxSize)
                if (bytes.isEmpty()) {
                    throw ImageFetchException("图片内容为空")
                }

                // 优先按文件头判断类型，其次才信任 Content-Type
                val sniffedExt = sniffExt(bytes)
                val declaredExt = CONTENT_TYPE_EXT[contentType]
                val ext = sniffedExt ?: declaredExt
                    ?: throw ImageFetchException("返回内容不是图片: contentType=$contentType")

                return ImageResult(bytes, ext, contentType)
            }
        }
    }

    private fun mergeCookie(cookie: String, customCookie: String?): String {
        if (customCookie.isNullOrBlank()) {
            return cookie
        }
        val cookieMap = CookieStore.cookieToMap(cookie)
        cookieMap.putAll(CookieStore.cookieToMap(customCookie))
        return CookieStore.mapToCookie(cookieMap) ?: customCookie
    }

    private fun isForwardableHeader(name: String, value: String): Boolean {
        if (name.isBlank() || value.any { it == '\r' || it == '\n' }) {
            return false
        }
        return name.lowercase() !in setOf(
            "host", "content-length", "connection", "keep-alive", "transfer-encoding", "upgrade",
            "proxy-authorization", "proxy-connection"
        )
    }

    /**
     * 最多读取 maxSize 字节，超出直接报错，避免超大响应打满内存与磁盘
     */
    private fun readAtMost(input: InputStream, maxSize: Long): ByteArray {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) {
                    break
                }
                total += read
                if (total > maxSize) {
                    throw ImageFetchException("图片体积超过限制: $maxSize")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    /**
     * 根据文件头识别图片类型
     */
    fun sniffExt(bytes: ByteArray): String? {
        if (bytes.size < 12) {
            return null
        }
        fun byteAt(index: Int): Int = bytes[index].toInt() and 0xff

        // PNG
        if (byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E && byteAt(3) == 0x47) {
            return "png"
        }
        // JPEG
        if (byteAt(0) == 0xFF && byteAt(1) == 0xD8 && byteAt(2) == 0xFF) {
            return "jpg"
        }
        // GIF
        if (byteAt(0) == 0x47 && byteAt(1) == 0x49 && byteAt(2) == 0x46 && byteAt(3) == 0x38) {
            return "gif"
        }
        // BMP
        if (byteAt(0) == 0x42 && byteAt(1) == 0x4D) {
            return "bmp"
        }
        // ICO
        if (byteAt(0) == 0x00 && byteAt(1) == 0x00 && byteAt(2) == 0x01 && byteAt(3) == 0x00) {
            return "ico"
        }
        // TIFF
        if ((byteAt(0) == 0x49 && byteAt(1) == 0x49 && byteAt(2) == 0x2A && byteAt(3) == 0x00) ||
            (byteAt(0) == 0x4D && byteAt(1) == 0x4D && byteAt(2) == 0x00 && byteAt(3) == 0x2A)
        ) {
            return "tiff"
        }
        // RIFF 容器: WEBP
        if (byteAt(0) == 0x52 && byteAt(1) == 0x49 && byteAt(2) == 0x46 && byteAt(3) == 0x46 &&
            byteAt(8) == 0x57 && byteAt(9) == 0x45 && byteAt(10) == 0x42 && byteAt(11) == 0x50
        ) {
            return "webp"
        }
        // ISO BMFF 容器: AVIF / HEIC
        if (byteAt(4) == 0x66 && byteAt(5) == 0x74 && byteAt(6) == 0x79 && byteAt(7) == 0x70) {
            val brand = String(bytes, 8, 4, Charsets.ISO_8859_1).lowercase()
            if (brand.startsWith("avif") || brand.startsWith("avis")) {
                return "avif"
            }
            if (brand.startsWith("heic") || brand.startsWith("heix") ||
                brand.startsWith("hevc") || brand.startsWith("mif1")
            ) {
                return "heic"
            }
        }
        // SVG 可内嵌脚本，同源返回存在 XSS 风险，不予支持
        return null
    }

    /**
     * 根据扩展名返回响应 content-type
     */
    fun contentTypeOf(ext: String): String {
        return when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "tiff" -> "image/tiff"
            else -> "application/octet-stream"
        }
    }

    /**
     * 为即将写入的文件腾出空间，优先删除最久未使用的封面缓存。
     * 子目录属于其他缓存，不在这里处理。
     */
    fun trimCache(cacheDir: File, maxSize: Long, incomingSize: Long = 0L): Int {
        if (maxSize <= 0 || !cacheDir.exists()) {
            return 0
        }
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in KNOWN_IMAGE_EXT }
            ?.sortedBy { it.lastModified() }
            ?: return 0
        var totalSize = files.sumOf { it.length() }
        var deleted = 0
        for (file in files) {
            if (totalSize + incomingSize <= maxSize) {
                break
            }
            val fileSize = file.length()
            if (file.delete()) {
                totalSize -= fileSize
                deleted++
            }
        }
        return deleted
    }
}
