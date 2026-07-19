package software.sava.services.solana.epoch;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.PerfSample;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SlotPerformanceStatsTests {

  private static PerfSample sample(final long slot, final long numSlots, final int samplePeriodSecs) {
    return new PerfSample(slot, numSlots, 0, 0, samplePeriodSecs);
  }

  @Test
  void noUsableSamplesReturnsNull() {
    assertNull(SlotPerformanceStats.calculateStats(List.of(), 400, 1_000));
    assertNull(SlotPerformanceStats.calculateStats(List.of(
        sample(10_000, 0, 60),      // no slots
        sample(10_000, 100, 0),     // no sample period
        sample(50, 100, 60)         // opening epoch: numSlots >= slot
    ), 400, 1_000));
  }

  @Test
  void singleSampleIsItsOwnDistribution() {
    final var stats = SlotPerformanceStats.calculateStats(List.of(sample(10_000, 60, 30)), 400, 1_000);
    assertNotNull(stats);
    assertEquals(500, stats.median());
    assertEquals(500, stats.mean());
    assertEquals(500, stats.min());
    assertEquals(500, stats.max());
    assertEquals(0.0, stats.estimatedStdDev());
    assertEquals(1, stats.numPerfSamples());
  }

  @Test
  void oddSampleCountUsesTheMiddleValue() {
    final var stats = SlotPerformanceStats.calculateStats(List.of(
        sample(10_000, 100, 70),
        sample(10_000, 100, 45),
        sample(10_000, 100, 50)
    ), 400, 1_000);
    assertNotNull(stats);
    assertEquals(500, stats.median());
    assertEquals(550, stats.mean());
    assertEquals(450, stats.min());
    assertEquals(700, stats.max());
    assertEquals(250 / 6.0, stats.estimatedStdDev());
    assertEquals(3, stats.numPerfSamples());

    assertEquals(542, stats.medianPercentile68());
    assertEquals(569, stats.medianPercentile95());
    assertEquals(592, stats.percentile68());
    assertEquals(619, stats.percentile95());
  }

  @Test
  void evenSampleCountAveragesTheTwoMiddleValues() {
    final var stats = SlotPerformanceStats.calculateStats(List.of(
        sample(10_000, 100, 40),
        sample(10_000, 100, 70),
        sample(10_000, 100, 50),
        sample(10_000, 100, 60)
    ), 400, 1_000);
    assertNotNull(stats);
    assertEquals(550, stats.median());
    assertEquals(550, stats.mean());
    assertEquals(400, stats.min());
    assertEquals(700, stats.max());
    assertEquals(300 / 6.0, stats.estimatedStdDev());
    assertEquals(4, stats.numPerfSamples());
  }

  @Test
  void millisPerSlotIsClampedToTheGivenBounds() {
    final var stats = SlotPerformanceStats.calculateStats(List.of(
        sample(10_000, 100, 10),    // 100 ms/slot clamps up to 400
        sample(10_000, 100, 500)    // 5000 ms/slot clamps down to 1000
    ), 400, 1_000);
    assertNotNull(stats);
    assertEquals(400, stats.min());
    assertEquals(1_000, stats.max());
    assertEquals(700, stats.median());
    assertEquals(700, stats.mean());
  }

  @Test
  void convenienceOverloadsDelegateWithTheTargetBounds() {
    final var samples = List.of(sample(10_000, 60, 30)); // 500 ms per slot.
    // The single-argument overload uses TARGET_MILLIS_PER_SLOT as both bounds.
    final var oneArg = SlotPerformanceStats.calculateStats(samples);
    assertNotNull(oneArg);
    assertEquals(400, oneArg.median());
    assertEquals(1, oneArg.numPerfSamples());
    // The two-argument overload keeps the target floor but raises the ceiling.
    final var twoArg = SlotPerformanceStats.calculateStats(samples, 1_000);
    assertNotNull(twoArg);
    assertEquals(500, twoArg.median());
    assertEquals(1, twoArg.numPerfSamples());
  }

  @Test
  void epochOpeningBoundarySampleIsExcluded() {
    // numSlots == slot marks the opening sample of an epoch and must be ignored.
    assertNull(SlotPerformanceStats.calculateStats(List.of(sample(100, 100, 60)), 400, 1_000));
  }

  @Test
  void invalidSamplesAreFilteredOut() {
    final var stats = SlotPerformanceStats.calculateStats(List.of(
        sample(10_000, 100, 50),
        sample(10_000, 0, 60),
        sample(10_000, 100, 0),
        sample(50, 100, 60)
    ), 400, 1_000);
    assertNotNull(stats);
    assertEquals(1, stats.numPerfSamples());
    assertEquals(500, stats.median());
  }
}
