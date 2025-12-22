package jp.muo.dtc_simulator

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile

/**
 * PacketProcessor - Phase 1 Implementation
 *
 * VPNインターフェースからパケットを読み取り、処理するクラス。
 * Phase 1では単純なパケット転送を実装。
 * Phase 2で遅延やパケットロスのシミュレーション機能を追加予定。
 */
class PacketProcessor
/**
 * コンストラクタ
 *
 * @param vpnInterface VPNインターフェースのファイルディスクリプタ
 */(private val vpnInterface: ParcelFileDescriptor) : Runnable {
    @Volatile
    private var running = true

    // Latency managers
    private val outboundDelayManager = PacketDelayManager()
    private val inboundDelayManager = PacketDelayManager()

    /**
     * Update latency settings
     */
    fun updateLatency(outboundMs: Int, inboundMs: Int) {
        outboundDelayManager.setLatency(outboundMs)
        inboundDelayManager.setLatency(inboundMs)
    }

    /**
     * パケット処理メインループ
     * VPNインターフェースからパケットを読み取り、処理して送信する。
     */
    override fun run() {
        Log.i(TAG, "PacketProcessor started")

        var `in`: FileInputStream? = null
        var out: FileOutputStream? = null

        try {
            // VPNインターフェースの入出力ストリームを取得
            `in` = FileInputStream(vpnInterface.fileDescriptor)
            out = FileOutputStream(vpnInterface.fileDescriptor)

            // Delayed writer thread
            val writerThread = Thread({
                try {
                    while (running) {
                        // In this simple processor, we just use one manager
                        // because we don't know the direction easily without parsing IP headers.
                        // For simplicity, let's treat everything as outbound for now in this class.
                        val data = outboundDelayManager.pollReadyPacket()
                        if (data != null) {
                            out.write(data)
                        } else {
                            Thread.sleep(outboundDelayManager.getTimeToNextReady().coerceIn(1, 100))
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Writer thread error", e)
                }
            }, "PacketProcessorWriter")
            writerThread.start()

            // パケット用バッファを確保
            val packet = ByteBuffer.allocate(MTU)

            // メインループ
            while (running) {
                // VPNインターフェースからパケット読み取り
                val length = `in`.read(packet.array())

                if (length > 0) {
                    // パケット処理 (Add to delay manager)
                    outboundDelayManager.addPacket(packet.array(), length)
                    packet.clear()
                } else if (length < 0) {
                    // ストリーム終了
                    Log.w(TAG, "End of stream reached")
                    break
                }
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Packet processing error", e)
        } finally {
            // リソースのクリーンアップ
            closeStreams(`in`, out)
            Log.i(TAG, "PacketProcessor stopped")
        }
    }

    /**
     * パケット処理を停止する
     */
    fun stop() {
        Log.i(TAG, "Stopping PacketProcessor")
        running = false
    }

    /**
     * ストリームをクローズする
     *
     * @param in 入力ストリーム
     * @param out 出力ストリーム
     */
    private fun closeStreams(`in`: FileInputStream?, out: FileOutputStream?) {
        try {
            `in`?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing input stream", e)
        }

        try {
            out?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing output stream", e)
        }
    }

    companion object {
        private const val TAG = "PacketProcessor"

        // MTU設定 (Maximum Transmission Unit)
        private const val MTU = 1500
    }
}
