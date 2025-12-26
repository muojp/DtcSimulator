package jp.muo.dtc_simulator

import org.junit.Test
import org.junit.Assert.*

/**
 * Test PacketProcessor behavior when network profile is updated
 *
 * This test verifies:
 * 1. Initial profile settings are applied correctly
 * 2. Profile changes are reflected in packet processing
 * 3. Multiple profile updates work correctly
 */
class PacketProcessorProfileUpdateTest {

    @Test
    fun testProfileUpdate_PacketLossChanges() {
        val processor = PacketProcessor()

        // Set initial profile with no packet loss
        val noLossProfile = NetworkProfile(
            name = "No Loss",
            delay = NetworkProfile.DelayConfig(value = 0),
            loss = NetworkProfile.LossConfig(value = 0.0f),
            bandwidth = NetworkProfile.BandwidthConfig(value = 0)
        )

        processor.setNetworkProfile(noLossProfile)

        // Send multiple packets (should all get through)
        val testCount1 = 100
        var receivedCount1 = 0

        for (i in 0 until testCount1) {
            val packet = ByteArray(64) { i.toByte() }
            processor.processOutboundPacket(packet, packet.size)
        }

        // Poll all packets (with 0 delay, they should all be ready immediately)
        for (i in 0 until testCount1) {
            if (processor.pollReadyOutboundPacket(10) != null) {
                receivedCount1++
            }
        }

        val receivedRate1 = receivedCount1.toFloat() / testCount1 * 100f

        println("\n=== No Loss Profile ===")
        println("Sent: $testCount1, Received: $receivedCount1 (${receivedRate1}%)")

        // Verify most/all packets were received
        assertTrue("With no loss, should receive close to 100% of packets (got ${receivedRate1}%)", receivedRate1 >= 95f)

        // Update to high loss profile
        val highLossProfile = NetworkProfile(
            name = "High Loss",
            delay = NetworkProfile.DelayConfig(value = 0),
            loss = NetworkProfile.LossConfig(up = 50.0f, down = 50.0f),
            bandwidth = NetworkProfile.BandwidthConfig(value = 0)
        )

        processor.setNetworkProfile(highLossProfile)

        // Send multiple packets (many should be dropped)
        val testCount2 = 100
        var receivedCount2 = 0

        for (i in 0 until testCount2) {
            val packet = ByteArray(64) { i.toByte() }
            processor.processOutboundPacket(packet, packet.size)
        }

        // Poll all packets
        for (i in 0 until testCount2) {
            if (processor.pollReadyOutboundPacket(10) != null) {
                receivedCount2++
            }
        }

        val receivedRate2 = receivedCount2.toFloat() / testCount2 * 100f
        val lossRate2 = 100f - receivedRate2

        println("\n=== High Loss Profile (50% loss) ===")
        println("Sent: $testCount2, Received: $receivedCount2 (${receivedRate2}%), Lost: ~${lossRate2}%")

        // Verify significant packet loss occurred
        assertTrue(
            "With 50% loss, should receive around 30-70% of packets (got ${receivedRate2}%)",
            receivedRate2 in 30f..70f
        )
    }

    @Test
    fun testProfileUpdate_InboundVsOutbound() {
        val processor = PacketProcessor()

        // Profile with asymmetric delays
        val asymmetricProfile = NetworkProfile(
            name = "Asymmetric",
            delay = NetworkProfile.DelayConfig(up = 100, down = 20),
            loss = NetworkProfile.LossConfig(value = 0.0f),
            bandwidth = NetworkProfile.BandwidthConfig(value = 0)
        )

        processor.setNetworkProfile(asymmetricProfile)

        // Test outbound (should be ~100ms)
        val outboundPacket = ByteArray(64) { 1 }
        val startOut = System.currentTimeMillis()
        processor.processOutboundPacket(outboundPacket, outboundPacket.size)
        processor.pollReadyOutboundPacket(150)
        val outboundDelay = System.currentTimeMillis() - startOut

        // Test inbound (should be ~20ms)
        val inboundPacket = ByteArray(64) { 2 }
        val startIn = System.currentTimeMillis()
        processor.processInboundPacket(inboundPacket, inboundPacket.size)
        processor.pollReadyInboundPacket(50)
        val inboundDelay = System.currentTimeMillis() - startIn

        println("\n=== Asymmetric Profile ===")
        println("Outbound (100ms expected): ${outboundDelay}ms")
        println("Inbound (20ms expected): ${inboundDelay}ms")

        assertTrue("Outbound delay should be ~100ms (got ${outboundDelay}ms)", outboundDelay in 90..120)
        assertTrue("Inbound delay should be ~20ms (got ${inboundDelay}ms)", inboundDelay in 15..30)
        assertTrue("Outbound should be slower than inbound", outboundDelay > inboundDelay * 2)
    }
}
