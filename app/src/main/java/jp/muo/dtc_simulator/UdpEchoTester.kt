package jp.muo.dtc_simulator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * UdpEchoTester - Utility for testing UDP echo server connectivity
 *
 * This utility sends a UDP packet to a specified echo server and waits for a response.
 * It provides a simple way to test network connectivity and UDP communication.
 */
object UdpEchoTester {
    private const val TAG = "UdpEchoTester"

    // Default configuration
    private const val DEFAULT_HOST = "192.168.0.157"
    private const val DEFAULT_PORT = 22840
    private const val DEFAULT_MESSAGE = "hello"
    private const val SOCKET_TIMEOUT_MS = 5000  // 5 seconds
    private const val OPERATION_TIMEOUT_MS = 10000L  // 10 seconds total operation timeout

    // Coroutine scope for network operations
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Result of UDP echo test
     */
    data class EchoResult(
        val success: Boolean,
        val message: String,
        val responseData: String? = null,
        val roundTripTimeMs: Long? = null,
        val error: Throwable? = null
    )

    /**
     * Test UDP echo server connectivity
     *
     * @param context Android context (not currently used but available for future features)
     * @param host Server IP address (default: 192.168.0.157)
     * @param port Server port (default: 22840)
     * @param message Message to send (default: "hello")
     * @param callback Callback function that receives the EchoResult
     */
    fun testEcho(
        context: Context,
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        message: String = DEFAULT_MESSAGE,
        callback: (EchoResult) -> Unit
    ) {
        // Launch coroutine on IO dispatcher for network operations
        coroutineScope.launch {
            val result = testEchoSuspend(host, port, message)

            // Post result back to main thread
            withContext(Dispatchers.Main) {
                callback(result)
            }
        }
    }

    /**
     * Suspend version of testEcho for use in coroutines
     *
     * @param host Server IP address
     * @param port Server port
     * @param message Message to send
     * @return EchoResult containing test outcome
     */
    suspend fun testEchoSuspend(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        message: String = DEFAULT_MESSAGE
    ): EchoResult = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null

        try {
            Log.i(TAG, "Starting UDP echo test to $host:$port")

            // Apply overall timeout to the entire operation
            withTimeout(OPERATION_TIMEOUT_MS) {
                // Create UDP socket
                socket = DatagramSocket()
                socket?.soTimeout = SOCKET_TIMEOUT_MS

                // Prepare message
                val sendData = message.toByteArray(Charsets.UTF_8)
                val serverAddress = InetAddress.getByName(host)

                Log.d(TAG, "Sending message: '$message' (${sendData.size} bytes)")

                // Send packet
                val sendPacket = DatagramPacket(
                    sendData,
                    sendData.size,
                    serverAddress,
                    port
                )

                val startTime = System.currentTimeMillis()
                socket?.send(sendPacket)
                Log.d(TAG, "Packet sent successfully")

                // Prepare receive buffer
                val receiveBuffer = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                // Wait for response
                Log.d(TAG, "Waiting for response...")
                socket?.receive(receivePacket)

                val endTime = System.currentTimeMillis()
                val roundTripTime = endTime - startTime

                // Extract response data
                val responseData = String(
                    receivePacket.data,
                    0,
                    receivePacket.length,
                    Charsets.UTF_8
                )

                Log.i(TAG, "Received response: '$responseData' (${receivePacket.length} bytes)")
                Log.i(TAG, "Round-trip time: ${roundTripTime}ms")

                // Check if echo matches
                if (responseData == message) {
                    EchoResult(
                        success = true,
                        message = "Echo successful!",
                        responseData = responseData,
                        roundTripTimeMs = roundTripTime
                    )
                } else {
                    EchoResult(
                        success = false,
                        message = "Echo mismatch. Sent: '$message', Received: '$responseData'",
                        responseData = responseData,
                        roundTripTimeMs = roundTripTime
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Socket timeout waiting for response", e)
            EchoResult(
                success = false,
                message = "Timeout: No response received within ${SOCKET_TIMEOUT_MS}ms",
                error = e
            )
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Operation timeout", e)
            EchoResult(
                success = false,
                message = "Timeout: Operation exceeded ${OPERATION_TIMEOUT_MS}ms",
                error = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP echo test", e)
            EchoResult(
                success = false,
                message = "Error: ${e.javaClass.simpleName} - ${e.message}",
                error = e
            )
        } finally {
            // Clean up socket
            try {
                socket?.close()
                Log.d(TAG, "Socket closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }

    /**
     * Synchronous version of testEcho (blocks current thread)
     * Use this only from background threads!
     *
     * @param host Server IP address
     * @param port Server port
     * @param message Message to send
     * @return EchoResult containing test outcome
     */
    @JvmStatic
    fun testEchoBlocking(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        message: String = DEFAULT_MESSAGE
    ): EchoResult {
        var socket: DatagramSocket? = null

        try {
            Log.i(TAG, "Starting UDP echo test (blocking) to $host:$port")

            // Create UDP socket
            socket = DatagramSocket()
            socket.soTimeout = SOCKET_TIMEOUT_MS

            // Prepare message
            val sendData = message.toByteArray(Charsets.UTF_8)
            val serverAddress = InetAddress.getByName(host)

            Log.d(TAG, "Sending message: '$message' (${sendData.size} bytes)")

            // Send packet
            val sendPacket = DatagramPacket(
                sendData,
                sendData.size,
                serverAddress,
                port
            )

            val startTime = System.currentTimeMillis()
            socket.send(sendPacket)
            Log.d(TAG, "Packet sent successfully")

            // Prepare receive buffer
            val receiveBuffer = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            // Wait for response
            Log.d(TAG, "Waiting for response...")
            socket.receive(receivePacket)

            val endTime = System.currentTimeMillis()
            val roundTripTime = endTime - startTime

            // Extract response data
            val responseData = String(
                receivePacket.data,
                0,
                receivePacket.length,
                Charsets.UTF_8
            )

            Log.i(TAG, "Received response: '$responseData' (${receivePacket.length} bytes)")
            Log.i(TAG, "Round-trip time: ${roundTripTime}ms")

            // Check if echo matches
            return if (responseData == message) {
                EchoResult(
                    success = true,
                    message = "Echo successful! Response received in ${roundTripTime}ms",
                    responseData = responseData,
                    roundTripTimeMs = roundTripTime
                )
            } else {
                EchoResult(
                    success = false,
                    message = "Echo mismatch. Sent: '$message', Received: '$responseData'",
                    responseData = responseData,
                    roundTripTimeMs = roundTripTime
                )
            }
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Socket timeout waiting for response", e)
            return EchoResult(
                success = false,
                message = "Timeout: No response received within ${SOCKET_TIMEOUT_MS}ms",
                error = e
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP echo test", e)
            return EchoResult(
                success = false,
                message = "Error: ${e.javaClass.simpleName} - ${e.message}",
                error = e
            )
        } finally {
            // Clean up socket
            try {
                socket?.close()
                Log.d(TAG, "Socket closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing socket", e)
            }
        }
    }
}
