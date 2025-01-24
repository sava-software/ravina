package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.response.EpochInfo;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.MINUTES;
import static software.sava.services.solana.config.ChainItemFormatter.commaSeparateInteger;

public record Epoch(long startedAt,
                    long endsAt,
                    EpochInfo info,
                    Epoch previousSample,
                    int defaultMillisPerSlot,
                    SlotPerformanceStats slotStats,
                    long sampledAt,
                    double epochSkipRate,
                    double sampleSkipRate) {

  private static final double MILLIS_PER_YEAR = Duration.ofDays(365).toMillis();

  private static int validateEpochSampleProgress(final EpochInfo latest, final Epoch previous) {
    final int epochCompare = Long.compareUnsigned(latest.epoch(), previous.epoch());
    if (epochCompare < 0) {
      throw new IllegalStateException(String.format(
          "Sample EpochInfo [epoch=%s] is before earliest sample [epoch=%s]",
          Long.toUnsignedString(latest.epoch()),
          Long.toUnsignedString(previous.epoch())
      ));
    } else {
      return epochCompare;
    }
  }

  private static double calculateSkipRate(final int epochCompare, final EpochInfo latest, final EpochInfo previous) {
    final long heightDelta = new BigInteger(Long.toUnsignedString(latest.blockHeight()))
        .subtract(new BigInteger(Long.toUnsignedString(previous.blockHeight())))
        .longValue();

    final long currentSlotIndex = latest.slotIndex();
    if (epochCompare > 0) {
      final long previousSlots = previous.slotsInEpoch() - previous.slotIndex();
      return 1.0 - (heightDelta / (double) (currentSlotIndex + previousSlots));
    } else {
      return 1.0 - (heightDelta / (double) (currentSlotIndex - previous.slotIndex()));
    }
  }

  public static Epoch create(final Epoch earliestSample,
                             final Epoch previousSample,
                             final EpochInfo latestEpochInfo,
                             final int defaultMillisPerSlot,
                             final SlotPerformanceStats slotStats,
                             final long sampledAt) {
    final long currentSlotIndex = latestEpochInfo.slotIndex();
    final long slotsRemaining = latestEpochInfo.slotsInEpoch() - currentSlotIndex;
    final int millsPerSlot = slotStats == null ? defaultMillisPerSlot : slotStats.median();
    final long millisRemaining = slotsRemaining * millsPerSlot;
    final long endsAt = sampledAt + millisRemaining;

    final long startedAt;
    final double epochSkipRate;
    final double sampleSkipRate;

    if (earliestSample == null) {
      startedAt = sampledAt - (currentSlotIndex * millsPerSlot);
      epochSkipRate = 0.0;
      sampleSkipRate = 0.0;
    } else {
      final int earliestEpochCompare = validateEpochSampleProgress(latestEpochInfo, earliestSample);
      if (earliestEpochCompare > 0) {
        startedAt = sampledAt - (currentSlotIndex * millsPerSlot);
      } else {
        startedAt = earliestSample.startedAt;
      }

      epochSkipRate = calculateSkipRate(earliestEpochCompare, latestEpochInfo, earliestSample.info);
      if (previousSample == earliestSample) {
        sampleSkipRate = epochSkipRate;
      } else {
        final int sampleEpochCompare = validateEpochSampleProgress(latestEpochInfo, previousSample);
        sampleSkipRate = calculateSkipRate(sampleEpochCompare, latestEpochInfo, previousSample.info);
      }
    }

    return new Epoch(
        startedAt,
        endsAt,
        latestEpochInfo,
        previousSample,
        millsPerSlot,
        slotStats,
        sampledAt,
        epochSkipRate,
        sampleSkipRate
    );
  }

  public int epochsPerYear(final int estimatedMillisPerSlot) {
    return (int) Math.round(MILLIS_PER_YEAR / (double) (estimatedMillisPerSlot * slotsPerEpoch()));
  }

  public long millisRemaining() {
    return endsAt - System.currentTimeMillis();
  }

  public long epoch() {
    return info.epoch();
  }

  public int slotsPerEpoch() {
    return info.slotsInEpoch();
  }

  public long timeRemaining(final TimeUnit timeUnit) {
    return timeUnit.convert(millisRemaining(), TimeUnit.MILLISECONDS);
  }

  public int medianMillisPerSlot() {
    return slotStats == null ? defaultMillisPerSlot : slotStats.median();
  }

  public long estimatedSlot(final long millisPerSlot) {
    return Math.min(
        info.slotIndex() + ((System.currentTimeMillis() - sampledAt) / millisPerSlot),
        info.slotsInEpoch()
    );
  }

  public long estimatedSlot() {
    return estimatedSlot(medianMillisPerSlot());
  }

  public BigInteger estimatedBlockHeightGivenSlotEstimate(final long estimatedSlot, final double skipRate) {
    final long slotDelta = estimatedSlot - info.slotIndex();
    final long missedSlots = Math.round(slotDelta * skipRate);
    return BigInteger.valueOf(info.blockHeight()).add(BigInteger.valueOf(slotDelta - missedSlots));
  }

  public BigInteger estimatedBlockHeight(final long millisPerSlot, final double skipRate) {
    final long estimatedSlot = estimatedSlot(millisPerSlot);
    return estimatedBlockHeightGivenSlotEstimate(estimatedSlot, skipRate);
  }

  public BigInteger estimatedBlockHeight() {
    return estimatedBlockHeight(medianMillisPerSlot(), sampleSkipRate);
  }

  public double percentComplete(final long estimatedSlot) {
    return (estimatedSlot / (double) slotsPerEpoch()) * 100.0;
  }

  public double percentComplete() {
    return percentComplete(estimatedSlot(medianMillisPerSlot()));
  }

  public String logFormat() {
    final int median = medianMillisPerSlot();
    final long millisRemaining = millisRemaining();
    final long estimatedSlot = estimatedSlot(median);
    final long slotsPerEpoch = slotsPerEpoch();
    final double percentProgress = percentComplete(estimatedSlot);
    final var blockHeight = estimatedBlockHeightGivenSlotEstimate(estimatedSlot, sampleSkipRate);
    final long startedAgo = System.currentTimeMillis() - startedAt;
    return String.format(
        "Epoch %s :: Start %s ago :: Ends in %s | %s :: %d ms/slot | %d epochs/year :: %,d / %,d | %.1f%% :: %.2f%% skip rate | %s height",
        Long.toUnsignedString(epoch()),
        Duration.ofMillis(startedAgo).truncatedTo(MINUTES).toString().substring(2),
        Duration.ofMillis(millisRemaining).truncatedTo(MINUTES).toString().substring(2),
        LocalDateTime.ofInstant(Instant.ofEpochMilli(endsAt), ZoneId.systemDefault()).truncatedTo(MINUTES),
        median,
        epochsPerYear(median),
        estimatedSlot, slotsPerEpoch,
        percentProgress,
        sampleSkipRate * 100.0,
        commaSeparateInteger(blockHeight.toString())
    );
  }
}
