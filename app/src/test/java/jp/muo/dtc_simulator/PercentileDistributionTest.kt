package jp.muo.dtc_simulator

import org.junit.Test
import org.junit.Assert.*
import kotlin.random.Random

/**
 * Test percentile distribution sampling
 *
 * This test verifies that the percentile-based delay configuration
 * produces samples that match the configured percentile values.
 */
class PercentileDistributionTest {

    @Test
    fun testLEOSatelliteUplink_1000Samples() {
        // LEO Satellite Uplink configuration
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
            p90 = NetworkProfile.PercentileValue(up = 300, down = 175),
            p95 = NetworkProfile.PercentileValue(up = 350, down = 240)
        )

        val sampleCount = 1000
        val random = Random(42) // Fixed seed for reproducibility

        // Collect samples
        val uplinkSamples = mutableListOf<Int>()
        val downlinkSamples = mutableListOf<Int>()

        repeat(sampleCount) {
            val (up, down) = delayConfig.sampleFromDistribution(random)
            uplinkSamples.add(up)
            downlinkSamples.add(down)
        }

        // Sort samples to calculate actual percentiles
        uplinkSamples.sort()
        downlinkSamples.sort()

        // Calculate actual percentiles from samples
        val actualUpP25 = percentile(uplinkSamples, 25.0)
        val actualUpP50 = percentile(uplinkSamples, 50.0)
        val actualUpP90 = percentile(uplinkSamples, 90.0)
        val actualUpP95 = percentile(uplinkSamples, 95.0)

        val actualDownP25 = percentile(downlinkSamples, 25.0)
        val actualDownP50 = percentile(downlinkSamples, 50.0)
        val actualDownP90 = percentile(downlinkSamples, 90.0)
        val actualDownP95 = percentile(downlinkSamples, 95.0)

        // Print results for analysis
        println("\n=== LEO Satellite Uplink Distribution (${sampleCount} samples) ===")
        println("Uplink:")
        println("  P25: expected=60ms, actual=${actualUpP25}ms, diff=${actualUpP25 - 60}ms")
        println("  P50: expected=80ms, actual=${actualUpP50}ms, diff=${actualUpP50 - 80}ms")
        println("  P90: expected=300ms, actual=${actualUpP90}ms, diff=${actualUpP90 - 300}ms")
        println("  P95: expected=350ms, actual=${actualUpP95}ms, diff=${actualUpP95 - 350}ms")
        println("  Min: ${uplinkSamples.minOrNull()}ms, Max: ${uplinkSamples.maxOrNull()}ms")
        println("  Mean: ${"%.1f".format(uplinkSamples.average())}ms")
        println()
        println("Downlink:")
        println("  P25: expected=30ms, actual=${actualDownP25}ms, diff=${actualDownP25 - 30}ms")
        println("  P50: expected=65ms, actual=${actualDownP50}ms, diff=${actualDownP50 - 65}ms")
        println("  P90: expected=175ms, actual=${actualDownP90}ms, diff=${actualDownP90 - 175}ms")
        println("  P95: expected=240ms, actual=${actualDownP95}ms, diff=${actualDownP95 - 240}ms")
        println("  Min: ${downlinkSamples.minOrNull()}ms, Max: ${downlinkSamples.maxOrNull()}ms")
        println("  Mean: ${"%.1f".format(downlinkSamples.average())}ms")

        // Verify percentiles are reasonably close (±15% tolerance due to sampling variance)
        assertPercentileWithinTolerance(60, actualUpP25, 0.15, "Uplink P25")
        assertPercentileWithinTolerance(80, actualUpP50, 0.15, "Uplink P50")
        assertPercentileWithinTolerance(300, actualUpP90, 0.15, "Uplink P90")
        assertPercentileWithinTolerance(350, actualUpP95, 0.15, "Uplink P95")

        assertPercentileWithinTolerance(30, actualDownP25, 0.15, "Downlink P25")
        assertPercentileWithinTolerance(65, actualDownP50, 0.15, "Downlink P50")
        assertPercentileWithinTolerance(175, actualDownP90, 0.15, "Downlink P90")
        assertPercentileWithinTolerance(240, actualDownP95, 0.15, "Downlink P95")
    }

    @Test
    fun testLEOSatellite_10000Samples() {
        // Test with larger sample size for better accuracy
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
            p90 = NetworkProfile.PercentileValue(up = 300, down = 175),
            p95 = NetworkProfile.PercentileValue(up = 350, down = 240)
        )

        val sampleCount = 10000
        val random = Random(42)

        val uplinkSamples = mutableListOf<Int>()
        val downlinkSamples = mutableListOf<Int>()

        repeat(sampleCount) {
            val (up, down) = delayConfig.sampleFromDistribution(random)
            uplinkSamples.add(up)
            downlinkSamples.add(down)
        }

        uplinkSamples.sort()
        downlinkSamples.sort()

        val actualUpP25 = percentile(uplinkSamples, 25.0)
        val actualUpP50 = percentile(uplinkSamples, 50.0)
        val actualUpP90 = percentile(uplinkSamples, 90.0)
        val actualUpP95 = percentile(uplinkSamples, 95.0)

        val actualDownP25 = percentile(downlinkSamples, 25.0)
        val actualDownP50 = percentile(downlinkSamples, 50.0)
        val actualDownP90 = percentile(downlinkSamples, 90.0)
        val actualDownP95 = percentile(downlinkSamples, 95.0)

        println("\n=== LEO Satellite Distribution (${sampleCount} samples) ===")
        println("Uplink:")
        println("  P25: expected=60ms, actual=${actualUpP25}ms, diff=${actualUpP25 - 60}ms")
        println("  P50: expected=80ms, actual=${actualUpP50}ms, diff=${actualUpP50 - 80}ms")
        println("  P90: expected=300ms, actual=${actualUpP90}ms, diff=${actualUpP90 - 300}ms")
        println("  P95: expected=350ms, actual=${actualUpP95}ms, diff=${actualUpP95 - 350}ms")
        println("  Min: ${uplinkSamples.minOrNull()}ms, Max: ${uplinkSamples.maxOrNull()}ms")
        println("  Mean: ${"%.1f".format(uplinkSamples.average())}ms")
        println("  Std Dev: ${"%.1f".format(standardDeviation(uplinkSamples))}ms")
        println()
        println("Downlink:")
        println("  P25: expected=30ms, actual=${actualDownP25}ms, diff=${actualDownP25 - 30}ms")
        println("  P50: expected=65ms, actual=${actualDownP50}ms, diff=${actualDownP50 - 65}ms")
        println("  P90: expected=175ms, actual=${actualDownP90}ms, diff=${actualDownP90 - 175}ms")
        println("  P95: expected=240ms, actual=${actualDownP95}ms, diff=${actualDownP95 - 240}ms")
        println("  Min: ${downlinkSamples.minOrNull()}ms, Max: ${downlinkSamples.maxOrNull()}ms")
        println("  Mean: ${"%.1f".format(downlinkSamples.average())}ms")
        println("  Std Dev: ${"%.1f".format(standardDeviation(downlinkSamples))}ms")

        // Tighter tolerance for 10k samples (±10%)
        assertPercentileWithinTolerance(60, actualUpP25, 0.10, "Uplink P25")
        assertPercentileWithinTolerance(80, actualUpP50, 0.10, "Uplink P50")
        assertPercentileWithinTolerance(300, actualUpP90, 0.10, "Uplink P90")
        assertPercentileWithinTolerance(350, actualUpP95, 0.10, "Uplink P95")

        assertPercentileWithinTolerance(30, actualDownP25, 0.10, "Downlink P25")
        assertPercentileWithinTolerance(65, actualDownP50, 0.10, "Downlink P50")
        assertPercentileWithinTolerance(175, actualDownP90, 0.10, "Downlink P90")
        assertPercentileWithinTolerance(240, actualDownP95, 0.10, "Downlink P95")
    }

    @Test
    fun testDistributionVariance() {
        // Verify that the distribution has reasonable variance
        val delayConfig = NetworkProfile.DelayConfig(
            p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
            p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
            p90 = NetworkProfile.PercentileValue(up = 300, down = 175),
            p95 = NetworkProfile.PercentileValue(up = 350, down = 240)
        )

        val sampleCount = 5000
        val random = Random(42)

        val uplinkSamples = mutableListOf<Int>()
        repeat(sampleCount) {
            val (up, _) = delayConfig.sampleFromDistribution(random)
            uplinkSamples.add(up)
        }

        // Check that we have variance (samples are not all the same)
        val uniqueValues = uplinkSamples.toSet()
        // With integer interpolation, unique values are limited by range (0-450ms = ~450 possible values)
        // Expect at least 200 unique values out of 5000 samples
        assertTrue(
            "Expected significant variance, but got only ${uniqueValues.size} unique values out of $sampleCount samples",
            uniqueValues.size > 200
        )

        // Verify min/max ranges make sense
        val min = uplinkSamples.minOrNull()!!
        val max = uplinkSamples.maxOrNull()!!

        println("\n=== Variance Test ===")
        println("Unique values: ${uniqueValues.size} / $sampleCount (${uniqueValues.size * 100.0 / sampleCount}%)")
        println("Range: ${min}ms - ${max}ms")
        println("Expected range: roughly 0ms - 450ms")

        // Min should be close to 0, max should extrapolate beyond p95
        assertTrue("Min value too high: $min", min < 20)
        assertTrue("Max value too low: $max", max > 350)
    }

    /**
     * Calculate percentile from sorted list
     */
    private fun percentile(sortedList: List<Int>, p: Double): Int {
        require(p in 0.0..100.0) { "Percentile must be between 0 and 100" }
        if (sortedList.isEmpty()) return 0

        val index = (p / 100.0) * (sortedList.size - 1)
        val lower = index.toInt()
        val upper = kotlin.math.min(lower + 1, sortedList.size - 1)
        val fraction = index - lower

        return (sortedList[lower] + fraction * (sortedList[upper] - sortedList[lower])).toInt()
    }

    /**
     * Calculate standard deviation
     */
    private fun standardDeviation(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }

    /**
     * Assert that actual percentile is within tolerance of expected
     */
    private fun assertPercentileWithinTolerance(
        expected: Int,
        actual: Int,
        tolerance: Double,
        label: String
    ) {
        val diff = kotlin.math.abs(actual - expected)
        val maxDiff = (expected * tolerance).toInt()
        assertTrue(
            "$label: Expected $expected±${maxDiff}ms, but got ${actual}ms (diff=${diff}ms)",
            diff <= maxDiff
        )
    }
}
