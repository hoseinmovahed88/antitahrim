package com.antitahrim.azad.net

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * کلاینت HTTPS کوچک و دستی.
 *
 * چرا با دست نوشته شده و از HttpsURLConnection استفاده نشده؟ چون سه چیز را
 * باید کنترل کنیم که آن کلاس اجازه‌اش را نمی‌دهد:
 *   ۱. آدرس مقصد را خودمان از DNS ایرانی گرفته‌ایم و نمی‌خواهیم دوباره
 *      با DNS آلوده اپراتور حل شود.
 *   ۲. اولین بسته دست‌دادن TLS باید تکه‌تکه فرستاده شود.
 *   ۳. گاهی لازم است SNI را دستی عوض کنیم.
 *
 * اعتبارسنجی گواهی و نام میزبان همچنان کامل انجام می‌شود.
 */
object Http {

    data class Response(val code: Int, val body: String)

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 25_000

    /**
     * @param sniOverride اگر داده شود، همین نام در دست‌دادن TLS فرستاده می‌شود.
     *        هشدار: بیشتر سرورها (از جمله Cloudflare) وقتی SNI با Host نخواند
     *        درخواست را رد می‌کنند. برای پنهان کردن SNI از [FragmentConfig]
     *        استفاده کنید نه از این.
     */
    fun request(
        method: String,
        host: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        fragment: FragmentConfig = FragmentConfig(),
        sniOverride: String? = null,
        resolved: List<InetAddress>? = null
    ): Response {
        val addresses = resolved?.takeIf { it.isNotEmpty() } ?: IranDns.resolve(host)
        if (addresses.isEmpty()) throw IOException("نام $host با هیچ حل‌کننده‌ای پیدا نشد")

        var lastError: Exception? = null
        for (address in addresses) {
            try {
                return requestVia(address, method, host, path, headers, body, fragment, sniOverride)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("اتصال به $host ممکن نشد")
    }

    private fun requestVia(
        address: InetAddress,
        method: String,
        host: String,
        path: String,
        headers: Map<String, String>,
        body: String?,
        fragment: FragmentConfig,
        sniOverride: String?
    ): Response {
        val plain = openSocket(address, 443, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, fragment)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(plain, host, 443, true) as SSLSocket

        ssl.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val params = socket.sslParameters
            params.serverNames = listOf<SNIServerName>(SNIHostName(sniOverride ?: host))
            socket.sslParameters = params
            socket.startHandshake()

            // نام میزبان همیشه در برابر نام واقعی بررسی می‌شود، حتی وقتی SNI عوض شده
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(host, socket.session)) {
                throw IOException("گواهی سرور با نام $host نمی‌خواند")
            }

            val payload = body?.toByteArray(Charsets.UTF_8)
            val request = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (payload != null) append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.UTF_8))
            if (payload != null) out.write(payload)
            out.flush()

            return readResponse(BufferedInputStream(socket.getInputStream()))
        }
    }

    private fun readResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw IOException("پاسخی از سرور نیامد")
        val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
            ?: throw IOException("خط وضعیت نامفهوم: $statusLine")

        var contentLength = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: -1
                "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
            }
        }

        val body = when {
            chunked -> readChunked(input)
            contentLength >= 0 -> readExactly(input, contentLength)
            else -> input.readBytes()
        }
        return Response(code, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val s = buf.toString("UTF-8")
                return if (s.endsWith("\r")) s.dropLast(1) else s
            }
            buf.write(b)
        }
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n == -1) throw IOException("پاسخ ناقص از سرور")
            read += n
        }
        return out
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(readExactly(input, size))
            readLine(input)   // CRLF بعد از هر تکه
        }
        return out.toByteArray()
    }
}
