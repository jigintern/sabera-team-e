package jp.jig.glasses.sample.kmp.openai

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android向けテストクラスパスでも使える、最小限のローカルHTTPサーバー。 */
internal class TestHttpServer(private val respond: (requestNumber: Int) -> TestHttpResponse) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val running = AtomicBoolean(true)
    val calls = AtomicInteger()
    val endpoint: String get() = "http://127.0.0.1:${socket.localPort}/test"

    private val worker = Thread {
        while (running.get()) {
            try {
                socket.accept().use { client ->
                    // ヘッダー終端まで読む。クライアントは本文を書き終えてから応答を待つため、
                    // 小さなJSON本文を読み残してもレスポンス送信には影響しない
                    val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
                    while (reader.readLine()?.isNotEmpty() == true) Unit

                    val response = respond(calls.incrementAndGet())
                    val output = client.getOutputStream()
                    val reason = if (response.status == 200) "OK" else "Service Unavailable"
                    output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Content-Type: ${response.contentType}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Content-Length: ${response.declaredLength}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("x-request-id: ${response.requestId}\r\n".toByteArray(Charsets.US_ASCII))
                    output.write("Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(response.body)
                    output.flush()
                }
            } catch (error: SocketException) {
                if (running.get()) throw error
            }
        }
    }.apply {
        isDaemon = true
        name = "openai-test-http"
        start()
    }

    override fun close() {
        running.set(false)
        socket.close()
        worker.join(1_000)
    }
}

internal data class TestHttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json",
    val requestId: String = "req-test",
    val declaredLength: Int = body.size,
)
