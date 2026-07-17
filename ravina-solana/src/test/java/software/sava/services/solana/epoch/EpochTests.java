package software.sava.services.solana.epoch;

import org.junit.jupiter.api.Test;
import software.sava.rpc.json.http.response.EpochInfo;

import java.math.BigInteger;

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
  void derivedRatios() {
    final var epoch = Epoch.create(null, null, epochInfo(1_000, 500, 100), 400, null, 1_000_000);
    assertEquals(50.0, epoch.percentComplete(216_000));
    assertEquals(100.0, epoch.percentComplete(432_000));
    // 31,536,000,000 millis per year / (400 ms per slot * 432,000 slots per epoch).
    assertEquals(183, epoch.epochsPerYear(400));
  }
}
