package jp.muo.dtc_simulator

import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

/**
 * Test that percentile interpolation works as expected
 *
 * Verifies that when a percentile between p25 and p50 is sampled,
 * the delay value is linearly interpolated between p25's value and p50's value.
 */
class PercentileInterpolationTest {

    @Test
    fun testInterpolationBetweenP25AndP50() {
        // Setup: p25=60ms, p50=80ms (both up and down use same value)
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(value = 60),
            p50 = NetworkProfile.PercentileValue(value = 80)
        )

        println("\n=== Testing Interpolation Between P25 and P50 ===")
        println("Configuration: p25=60ms, p50=80ms (value field, applies to both directions)")
        println()

        // Test specific percentiles
        // Note: With value field, same delay is used for both up and down
        val testCases = listOf(
            // percentile -> expected value (same for up and down)
            0.0 to 0,      // Extrapolate down to 0
            25.0 to 60,    // Exact p25
            30.0 to 64,    // 20% between p25 and p50: 60 + 0.2*(80-60) = 64
            37.5 to 70,    // 50% between p25 and p50: 60 + 0.5*(80-60) = 70
            40.0 to 72,    // 60% between p25 and p50: 60 + 0.6*(80-60) = 72
            50.0 to 80,    // Exact p50
            75.0 to 100    // Extrapolate above p50: rate=(80-60)/(50-25)=0.8, 80+25*0.8=100
        )

        for ((percentile, expectedValue) in testCases) {
            // Create a custom random that always returns the specific percentile
            val fixedRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextDouble(from: Double, until: Double): Double = percentile
            }

            val (up, down) = delayConfig.sampleFromDistribution(fixedRandom)

            println("Percentile %.1f: up=%dms (expected=%dms), down=%dms (expected=%dms)"
                .format(percentile, up, expectedValue, down, expectedValue))

            // With value field, both up and down should get the same interpolated value
            assertEquals("Uplink at percentile $percentile", expectedValue, up)
            assertEquals("Downlink at percentile $percentile", expectedValue, down)
        }
    }

    @Test
    fun testInterpolationWithUpDownSplit() {
        // Setup with separate up/down values
        // p25: up=60ms, down=30ms
        // p50: up=80ms, down=65ms
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65)
        )

        println("\n=== Testing Interpolation With Up/Down Split ===")
        println("Configuration:")
        println("  p25: up=60ms, down=30ms")
        println("  p50: up=80ms, down=65ms")
        println()

        val testCases = listOf(
            // percentile -> (expectedUp, expectedDown)
            25.0 to Pair(60, 30),     // Exact p25
            30.0 to Pair(64, 37),     // 20% between: up=60+0.2*20=64, down=30+0.2*35=37
            37.5 to Pair(70, 47),     // 50% between: up=60+0.5*20=70, down=30+0.5*35=47.5→47
            40.0 to Pair(72, 51),     // 60% between: up=60+0.6*20=72, down=30+0.6*35=51
            50.0 to Pair(80, 65)      // Exact p50
        )

        for ((percentile, expected) in testCases) {
            val fixedRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextDouble(from: Double, until: Double): Double = percentile
            }

            val (up, down) = delayConfig.sampleFromDistribution(fixedRandom)

            println("Percentile %.1f: up=%dms (expected=%dms), down=%dms (expected=%dms)"
                .format(percentile, up, expected.first, down, expected.second))

            assertEquals("Uplink at percentile $percentile", expected.first, up)
            assertEquals("Downlink at percentile $percentile", expected.second, down)
        }
    }

    @Test
    fun testInterpolationBetweenP50AndP90() {
        // Test the larger gap between p50 and p90
        val delayConfig = NetworkProfile.DelayConfig(
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
            p90 = NetworkProfile.PercentileValue(up = 300, down = 175)
        )

        println("\n=== Testing Interpolation Between P50 and P90 ===")
        println("Configuration:")
        println("  p50: up=80ms, down=65ms")
        println("  p90: up=300ms, down=175ms")
        println()

        val testCases = listOf(
            50.0 to Pair(80, 65),      // Exact p50
            60.0 to Pair(135, 92),     // 25% between: up=80+0.25*220=135, down=65+0.25*110=92.5→92
            70.0 to Pair(190, 120),    // 50% between: up=80+0.5*220=190, down=65+0.5*110=120
            80.0 to Pair(245, 147),    // 75% between: up=80+0.75*220=245, down=65+0.75*110=147.5→147
            90.0 to Pair(300, 175)     // Exact p90
        )

        for ((percentile, expected) in testCases) {
            val fixedRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextDouble(from: Double, until: Double): Double = percentile
            }

            val (up, down) = delayConfig.sampleFromDistribution(fixedRandom)

            println("Percentile %.1f: up=%dms (expected=%dms, diff=%d), down=%dms (expected=%dms, diff=%d)"
                .format(percentile, up, expected.first, up - expected.first, down, expected.second, down - expected.second))

            assertEquals("Uplink at percentile $percentile", expected.first, up)
            assertEquals("Downlink at percentile $percentile", expected.second, down)
        }
    }

    @Test
    fun testAllFourPercentiles() {
        // Test with all four percentiles defined (LEO Satellite config)
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
            p90 = NetworkProfile.PercentileValue(up = 300, down = 175),
            p95 = NetworkProfile.PercentileValue(up = 350, down = 240)
        )

        println("\n=== Testing Full LEO Satellite Configuration ===")
        println("Configuration:")
        println("  p25: up=60ms, down=30ms")
        println("  p50: up=80ms, down=65ms")
        println("  p90: up=300ms, down=175ms")
        println("  p95: up=350ms, down=240ms")
        println()

        // Sample 100 values at each 1% percentile
        for (p in 0..100 step 10) {
            val fixedRandom = object : Random() {
                override fun nextBits(bitCount: Int): Int = 0
                override fun nextDouble(from: Double, until: Double): Double = p.toDouble()
            }

            val (up, down) = delayConfig.sampleFromDistribution(fixedRandom)

            // Determine which range we're in and calculate expected values
            val (expectedUp, expectedDown) = when {
                p < 25 -> {
                    // Extrapolate from 0 to p25
                    val fracUp = (60.0 * p / 25.0).toInt()
                    val fracDown = (30.0 * p / 25.0).toInt()
                    Pair(fracUp, fracDown)
                }
                p < 50 -> {
                    // Interpolate between p25 and p50
                    val frac = (p - 25) / 25.0
                    val upVal = (60 + frac * (80 - 60)).toInt()
                    val downVal = (30 + frac * (65 - 30)).toInt()
                    Pair(upVal, downVal)
                }
                p < 90 -> {
                    // Interpolate between p50 and p90
                    val frac = (p - 50) / 40.0
                    val upVal = (80 + frac * (300 - 80)).toInt()
                    val downVal = (65 + frac * (175 - 65)).toInt()
                    Pair(upVal, downVal)
                }
                p < 95 -> {
                    // Interpolate between p90 and p95
                    val frac = (p - 90) / 5.0
                    val upVal = (300 + frac * (350 - 300)).toInt()
                    val downVal = (175 + frac * (240 - 175)).toInt()
                    Pair(upVal, downVal)
                }
                else -> {
                    // Extrapolate above p95
                    val frac = (p - 95) / 5.0
                    val upVal = (350 + frac * (350 - 300)).toInt()
                    val downVal = (240 + frac * (240 - 175)).toInt()
                    Pair(upVal, downVal)
                }
            }

            println("P%3d: up=%3dms (exp=%3dms, diff=%+3d), down=%3dms (exp=%3dms, diff=%+3d)"
                .format(p, up, expectedUp, up - expectedUp, down, expectedDown, down - expectedDown))

            // Allow ±1ms tolerance for rounding
            assertTrue("Uplink at P$p: expected $expectedUp±1, got $up",
                kotlin.math.abs(up - expectedUp) <= 1)
            assertTrue("Downlink at P$p: expected $expectedDown±1, got $down",
                kotlin.math.abs(down - expectedDown) <= 1)
        }
    }
}
