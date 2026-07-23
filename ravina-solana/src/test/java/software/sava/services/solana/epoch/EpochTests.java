package software.sava.services.solana.epoch;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.EpochInfo;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class EpochTests {

  private static EpochInfo epochInfo(final long blockHeight, final long epoch, final int slotIndex) {
    return new EpochInfo(0, blockHeight, epoch, slotIndex, 432_000, 0);
  }

  @Test
  void firstSampleDerivesBoundsFromTheSlotIndex() {
    final var info = epochInfo(1_000, 500, 100);
    final var epoch = Epoch.create(null, null, info, 400, null, 1_000_000);

    assertEquals(960_000, epoch.startedAt());
    assertEquals(1_000_000 + (431_900L * 400), epoch.endsAt());
    assertEquals(0.0, epoch.epochSkipRate());
    assertEquals(0.0, epoch.sampleSkipRate());
    assertEquals(500, epoch.epoch());
    assertEquals(432_000, epoch.slotsPerEpoch());
    assertEquals(400, epoch.medianMillisPerSlot());
  }

  @Test
  void skipRateWithinTheSameEpoch() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    final var latest = Epoch.create(earliest, earliest, epochInfo(1_090, 500, 200), 400, null, 2_000_000);

    // 100 slots elapsed, 90 blocks produced.
    assertEquals(0.1, latest.epochSkipRate(), 1e-12);
    assertEquals(latest.epochSkipRate(), latest.sampleSkipRate());
    // The epoch start carries over from the earliest sample.
    assertEquals(960_000, latest.startedAt());
    assertEquals(2_000_000 + (431_800L * 400), latest.endsAt());
  }

  @Test
  void skipRateAcrossAnEpochRollover() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 431_000), 400, null, 1_000_000);
    final var latest = Epoch.create(earliest, earliest, epochInfo(1_400, 501, 500), 400, null, 3_000_000);

    // 1,000 slots remained in the previous epoch plus 500 in the current one; 400 blocks produced.
    assertEquals(1.0 - (400 / 1_500.0), latest.epochSkipRate(), 1e-12);
    // A new epoch derives its start from the current slot index.
    assertEquals(3_000_000 - (500L * 400), latest.startedAt());
  }

  @Test
  void regressingEpochSamplesAreRejected() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    final var stale = epochInfo(900, 499, 100);
    assertThrows(IllegalStateException.class, () -> Epoch.create(earliest, earliest, stale, 400, null, 2_000_000));
  }

  @Test
  void estimatedBlockHeightDiscountsTheSkipRate() {
    final var epoch = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    assertEquals(BigInteger.valueOf(1_050), epoch.estimatedBlockHeightGivenSlotEstimate(200, 0.5));
    assertEquals(BigInteger.valueOf(1_100), epoch.estimatedBlockHeightGivenSlotEstimate(200, 0.0));
    assertEquals(BigInteger.valueOf(1_000), epoch.estimatedBlockHeightGivenSlotEstimate(100, 0.0));
  }

  @Test
  void derivedTimesUseTheProvidedNow() {
    final var epoch = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);

    assertEquals(epoch.endsAt() - 1_500_000, epoch.millisRemaining(1_500_000));
    assertEquals(0, epoch.millisRemaining(epoch.endsAt()));

    // 400,000ms since the sample at 400ms per slot advances 1,000 slots.
    assertEquals(1_100, epoch.estimatedSlot(400, 1_400_000));
    // The estimate never exceeds the slots in the epoch.
    assertEquals(432_000, epoch.estimatedSlot(1, 2_000_000));

    // Estimated slot 1,100 is 1,000 past the sample; half are skipped at a 0.5 skip rate.
    assertEquals(BigInteger.valueOf(1_500), epoch.estimatedBlockHeight(400, 0.5, 1_400_000));
    assertEquals(BigInteger.valueOf(2_000), epoch.estimatedBlockHeight(400, 0.0, 1_400_000));

    final var log = epoch.logFormat(1_400_000);
    assertTrue(log.startsWith("Epoch 500 :: "));
    assertTrue(log.contains("400 ms/slot"));
  }

  @Test
  void slotStatsMedianDrivesPacing() {
    final var stats = new SlotPerformanceStats(450, 460, 400, 520, 20.0, 5);
    final var epoch = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, stats, 1_000_000);

    assertEquals(450, epoch.medianMillisPerSlot());
    assertEquals(1_000_000 - (100L * 450), epoch.startedAt());
    assertEquals(1_000_000 + (431_900L * 450), epoch.endsAt());
    assertSame(stats, epoch.slotStats());
  }

  @Test
  void medianMillisPerSlotPrefersTheSlotStatsMedian() {
    // Constructed directly: create() resolves defaultMillisPerSlot to the stats
    // median, so only a raw record can hold a default that disagrees.
    final var stats = new SlotPerformanceStats(450, 460, 400, 520, 20.0, 5);
    final var withStats = new Epoch(960_000, 173_760_000, epochInfo(1_000, 500, 100), null, 400, stats, 1_000_000, 0.0, 0.0);
    assertEquals(450, withStats.medianMillisPerSlot());
    final var withoutStats = new Epoch(960_000, 173_760_000, epochInfo(1_000, 500, 100), null, 400, null, 1_000_000, 0.0, 0.0);
    assertEquals(400, withoutStats.medianMillisPerSlot());
  }

  @Test
  void sampleSkipRateUsesThePreviousSampleNotTheEarliest() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    final var middle = Epoch.create(earliest, earliest, epochInfo(1_090, 500, 200), 400, null, 2_000_000);
    final var latest = Epoch.create(earliest, middle, epochInfo(1_140, 500, 300), 400, null, 3_000_000);

    // Epoch skip rate spans back to the earliest sample: 200 slots, 140 blocks.
    assertEquals(0.3, latest.epochSkipRate(), 1e-12);
    // Sample skip rate only spans the previous sample: 100 slots, 50 blocks.
    assertEquals(0.5, latest.sampleSkipRate(), 1e-12);
  }

  @Test
  void logFormatReportsElapsedTimeAndSkipRate() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    final var latest = Epoch.create(earliest, earliest, epochInfo(1_090, 500, 200), 400, null, 2_000_000);

    final var log = latest.logFormat(2_400_000);
    // startedAt is 960,000, so 2,400,000 is 24 minutes after the epoch started.
    assertTrue(log.contains("Start 24M ago"), log);
    // endsAt is 174,720,000: 172,320,000 ms remaining is exactly 47 hours 52 minutes.
    assertTrue(log.contains("Ends in 47H52M"), log);
    assertTrue(log.contains("400 ms/slot | 183 epochs/year"), log);
    // The sample skip rate of 0.1 renders as a percentage.
    assertTrue(log.contains("10.00% skip rate"), log);
    // Estimated slot 1,200 is 1,000 past the sample; 10% skipped: 1,090 + 900.
    assertTrue(log.contains("1,990 height"), log);
  }

  @Test
  void logFormatTruncatesDurationsAndTheEndTimestampToMinutes() {
    final var earliest = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    final var latest = Epoch.create(earliest, earliest, epochInfo(1_090, 500, 200), 400, null, 2_000_000);

    // A `now` that is not a whole minute past the boundaries: the durations
    // only render without seconds if they were truncated.
    final var log = latest.logFormat(2_400_999);
    // 1,440,999 ms since the 960,000 start truncates to 24 whole minutes.
    assertTrue(log.contains("Start 24M ago"), log);
    // 172,319,001 ms remaining is 59.001 s short of 47H52M, so it truncates down.
    assertTrue(log.contains("Ends in 47H51M | "), log);
    // The end-of-epoch timestamp renders in the system zone, truncated to
    // minutes. A derived endsAt is always minute-aligned here, so a raw record
    // carries one with stray millis: only truncation removes them from the log.
    final var unaligned = new Epoch(960_000, 174_720_123, epochInfo(1_090, 500, 200), null, 400, null, 2_000_000, 0.1, 0.1);
    final var unalignedLog = unaligned.logFormat(2_400_999);
    final var expectedEnd = LocalDateTime.ofInstant(Instant.ofEpochMilli(174_720_123L), ZoneId.systemDefault())
        .truncatedTo(ChronoUnit.MINUTES);
    assertTrue(unalignedLog.contains("| " + expectedEnd + " ::"), unalignedLog);
  }

  @Test
  void derivedRatios() {
    final var epoch = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    assertEquals(50.0, epoch.percentComplete(216_000));
    assertEquals(100.0, epoch.percentComplete(432_000));
    // 31,536,000,000 millis per year / (400 ms per slot * 432,000 slots per epoch).
    assertEquals(183, epoch.epochsPerYear(400));
  }

  @Test
  void wallClockDelegatesFeedTheExplicitNowArithmetic() {
    // The arithmetic is pinned through the explicit-`now` overloads above; this
    // only asserts that the no-arg delegates feed a real reading into it. At
    // 2,000,000,000 ms/slot the epoch ends around the year 29,300, so every
    // bound below holds for any clock reading this millennium — nothing here is
    // a timing tolerance.
    final var info = epochInfo(1_000, 500, 100);
    final var epoch = Epoch.create(null, null, info, 2_000_000_000, null, 1);

    final long millisRemaining = epoch.millisRemaining();
    assertTrue(millisRemaining > 0, "the epoch ends ~27,000 years out");
    assertTrue(millisRemaining < epoch.endsAt(), "a real now is positive");

    assertTrue(epoch.timeRemaining(TimeUnit.MILLISECONDS) > 0);

    // A real now is at or past the sample, so the estimate never regresses
    // below the sampled slot index, and min() caps it at the epoch length.
    final long estimatedSlot = epoch.estimatedSlot();
    assertTrue(estimatedSlot >= 100 && estimatedSlot <= 432_000, () -> "slot " + estimatedSlot);
    final long estimatedSlotAt = epoch.estimatedSlot(400);
    assertTrue(estimatedSlotAt >= 100 && estimatedSlotAt <= 432_000, () -> "slot " + estimatedSlotAt);

    // A zero skip rate only ever adds slot progress to the sampled height.
    final var height = epoch.estimatedBlockHeight();
    assertNotNull(height);
    assertTrue(height.longValueExact() >= 1_000);
    final var heightAt = epoch.estimatedBlockHeight(400, 0.0);
    assertNotNull(heightAt);
    assertTrue(heightAt.longValueExact() >= 1_000);

    assertTrue(epoch.percentComplete() > 0.0, "at least the sampled 100 slots have passed");

    final var log = epoch.logFormat();
    assertTrue(log.startsWith("Epoch 500 :: "), log);
  }
}
