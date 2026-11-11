
package com.asjohn720.s1mods.grpc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ConnectivityState
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Direct gRPC client for the scooter diagnostics endpoint on TCP port 50005.
 */
class S1GrpcClient(
    val host: String,
    val port: Int = 50005,
    val scope: Int? = null
) {
    private var channel: ManagedChannel? = null

    private fun descriptor(method: String): MethodDescriptor<ByteArray, ByteArray> =
        MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(
                MethodDescriptor.generateFullMethodName(
                    "wifican.WifiCanRpc",
                    method
                )
            )
            .setRequestMarshaller(ByteArrayMarshaller)
            .setResponseMarshaller(ByteArrayMarshaller)
            .build()

    private val executeAdbDescriptor = descriptor("ExecuteADBCommand")

    private object ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
        override fun stream(value: ByteArray): InputStream =
            ByteArrayInputStream(value)

        override fun parse(stream: InputStream): ByteArray =
            stream.readBytes()
    }

    /**
     * Resolves the target host while preserving an IPv6 interface scope.
     */
    fun resolveTargetHost(): String {
        var target =
            com.asjohn720.s1mods.discovery.NetworkScanner.normalizeHost(host)

        if (
            target.startsWith("fe80", ignoreCase = true) &&
            !target.contains("%") &&
            scope != null
        ) {
            target = "$target%$scope"
        }

        return try {
            InetAddress.getByName(target).hostAddress ?: target
        } catch (_: Exception) {
            target
        }
    }

    /**
     * Creates the plaintext gRPC channel and waits until it is READY.
     */
    suspend fun connect(timeoutSeconds: Long = 6L) =
        withContext(Dispatchers.IO) {
            val targetHost = resolveTargetHost()

            channel?.shutdownNow()

            val newChannel = OkHttpChannelBuilder
                .forAddress(targetHost, port)
                .usePlaintext()
                .maxInboundMessageSize(64 * 1024 * 1024)
                .build()

            channel = newChannel

            try {
                newChannel.getState(true)

                val deadline =
                    System.currentTimeMillis() + timeoutSeconds * 1000

                while (
                    newChannel.getState(false) != ConnectivityState.READY
                ) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw IllegalStateException(
                            "gRPC channel connection timed out"
                        )
                    }

                    if (newChannel.isShutdown || newChannel.isTerminated) {
                        throw IllegalStateException(
                            "gRPC channel was shut down before becoming ready"
                        )
                    }

                    delay(200)
                }
            } catch (e: Exception) {
                newChannel.shutdownNow()
                channel = null
                throw e
            }
        }

    /**
     * Executes the daemon's ExecuteADBCommand RPC.
     */
    suspend fun executeRaw(
        command: String,
        timeoutSeconds: Long = 30L
    ): Pair<Int, String> =
        unary(
            executeAdbDescriptor,
            ProtoUtils.buildCommandPayload(command),
            timeoutSeconds
        )

    /**
     * Tests the separate GetSwVersion RPC using an empty request.
     */
    suspend fun getSwVersion(timeoutSeconds: Long = 15L): String {
        val (_, message) = unary(
            descriptor("GetSwVersion"),
            ByteArray(0),
            timeoutSeconds
        )
        return message
    }

    private suspend fun unary(
        method: MethodDescriptor<ByteArray, ByteArray>,
        payload: ByteArray,
        timeoutSeconds: Long
    ): Pair<Int, String> = withContext(Dispatchers.IO) {

        val activeChannel = channel
            ?: throw IllegalStateException(
                "gRPC channel has not been created. Call connect() first."
            )

        val call: ClientCall<ByteArray, ByteArray> = activeChannel.newCall(
            method,
            CallOptions.DEFAULT.withDeadlineAfter(
                timeoutSeconds,
                TimeUnit.SECONDS
            )
        )

        val resultHolder = CompletableResult<ByteArray>()

        call.start(object : ClientCall.Listener<ByteArray>() {

            override fun onMessage(message: ByteArray) {
                resultHolder.complete(message)
            }

            override fun onClose(
                status: io.grpc.Status,
                trailers: io.grpc.Metadata
            ) {
                if (!status.isOk && !resultHolder.isDone()) {
                    resultHolder.completeExceptionally(
                        status.asRuntimeException()
                    )
                } else if (status.isOk && !resultHolder.isDone()) {
                    resultHolder.completeExceptionally(
                        IllegalStateException(
                            "gRPC completed without a response message"
                        )
                    )
                }
            }
        }, io.grpc.Metadata())

        call.request(1)
        call.sendMessage(payload)
        call.halfClose()

        ProtoUtils.decodeResponse(resultHolder.await())
    }

    /**
     * Executes arbitrary shell commands through the ADB command RPC.
     */
    suspend fun shell(
        command: String,
        timeoutSeconds: Long = 30L
    ): Pair<Int, String> {

        val encoded = Base64.encodeToString(
            command.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )

        val wrapped =
            "sh -c echo\${IFS}$encoded|base64\${IFS}-d|sh"

        return executeRaw(wrapped, timeoutSeconds)
    }

    /**
     * Pushes binary data to a remote file using base64 chunks.
     */
    suspend fun push(
        data: ByteArray,
        remotePath: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {

        val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
        val chunkSize = 50_000
        val totalChunks =
            (encoded.length + chunkSize - 1) / chunkSize

        var chunkIndex = 0

        for (start in encoded.indices step chunkSize) {
            val end = (start + chunkSize).coerceAtMost(encoded.length)
            val part = encoded.substring(start, end)
            val redirect = if (start == 0) ">" else ">>"

            val command =
                "sh -c echo\${IFS}$part|base64\${IFS}-d$redirect$remotePath"

            val (status, message) = executeRaw(command, 60L)

            if (status !in 0..1) {
                throw IllegalStateException(
                    "Push failed at character $start: $message"
                )
            }

            chunkIndex++
            onProgress(chunkIndex.toFloat() / totalChunks.toFloat())
        }

        true
    }

    /**
     * Pulls a remote file using base64.
     */
    suspend fun pull(
        remotePath: String,
        timeoutSeconds: Long = 120L
    ): ByteArray = withContext(Dispatchers.IO) {

        val (status, encoded) = shell(
            "base64 $remotePath",
            timeoutSeconds
        )

        if (status !in 0..1) {
            throw IllegalStateException("Pull failed: $encoded")
        }

        Base64.decode(encoded.trim(), Base64.DEFAULT)
    }

    /**
     * Captures a screenshot from the scooter and returns it as a Bitmap.
     */
    suspend fun takeScreenshot(): Bitmap = withContext(Dispatchers.IO) {

        val tempPath = "/data/local/tmp/_dash_shot.png"
        val (status, output) =
            executeRaw("screencap -p $tempPath", 60L)

        if (status !in 0..1) {
            throw IllegalStateException("Screencap failed: $output")
        }

        val bytes = pull(tempPath, 120L)
        shell("rm -f $tempPath", 20L)

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException(
                "Failed to decode screenshot bitmap"
            )
    }

    /**
     * Attempts the existing in-memory ADB key installation procedure.
     */
    suspend fun unlockAdb(adbPublicKeyContent: String): Boolean {

        val keyBytes = adbPublicKeyContent.trim()
            .plus("\n")
            .toByteArray(StandardCharsets.UTF_8)

        push(keyBytes, "/data/local/tmp/adb_keys_mine")
        shell("chmod 644 /data/local/tmp/adb_keys_mine", 15L)
        shell("setprop persist.ola.security.scootermode serv", 15L)

        val bindScript = """
            #!/system/bin/sh
            if [ -f /data/misc/adb/adbkeymoveos3_pub_prod ]; then
                T=/data/misc/adb/adbkeymoveos3_pub_prod
            elif [ -f /data/misc/adb/adb_keys ]; then
                T=/data/misc/adb/adb_keys
            else
                T=/adbkeymoveos3_pub_prod
            fi
            mount | grep -q " ${'$'}T " && umount "${'$'}T"
            mount --bind /data/local/tmp/adb_keys_mine "${'$'}T" || exit 1
            echo "bound ${'$'}T"
        """.trimIndent()

        push(
            bindScript.toByteArray(StandardCharsets.UTF_8),
            "/data/local/tmp/bind_key.sh"
        )

        shell("chmod 755 /data/local/tmp/bind_key.sh", 15L)

        val (status, output) =
            shell("sh /data/local/tmp/bind_key.sh", 20L)

        return status in 0..1 && output.contains("bound")
    }

    fun close() {
        try {
            channel?.shutdownNow()
        } catch (_: Exception) {
        }

        channel = null
    }

    private class CompletableResult<T> {
        private val lock = Object()
        private var result: T? = null
        private var exception: Throwable? = null
        private var done = false

        fun complete(value: T) {
            synchronized(lock) {
                if (done) return

                result = value
                done = true
                lock.notifyAll()
            }
        }

        fun completeExceptionally(error: Throwable) {
            synchronized(lock) {
                if (done) return

                exception = error
                done = true
                lock.notifyAll()
            }
        }

        fun isDone(): Boolean =
            synchronized(lock) { done }

        fun await(): T {
            synchronized(lock) {
                while (!done) {
                    lock.wait()
                }

                exception?.let { throw it }

                return result
                    ?: throw IllegalStateException(
                        "gRPC returned a null response"
                    )
            }
        }
    }
}
