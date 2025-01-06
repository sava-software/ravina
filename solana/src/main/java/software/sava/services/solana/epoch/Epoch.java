package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.response.EpochInfo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static java.time.temporal.ChronoUnit.MINUTES;

public record Epoch(long startedAt,
                    long endsAt,
                    EpochInfo info,
                    int defaultMillisPerSlot,
                    SlotPerformanceStats slotStats,
                    long sampledAt) {

  private static final double MILLIS_PER_YEAR = Duration.ofDays(365).toMillis();

  public static Epoch create(final Epoch earliestEpochInfo,
                             final EpochInfo epochInfo,
                             final int defaultMillisPerSlot,
                             final SlotPerformanceStats slotStats,
                             final long sampledAt) {
    final long slotsRemaining = epochInfo.slotsInEpoch() - epochInfo.slotIndex();
    final int millsPerSlot = slotStats == null ? defaultMillisPerSlot : slotStats.median();
    final long millisRemaining = slotsRemaining * millsPerSlot;
    final long endsAt = sampledAt + millisRemaining;
    final long startedAt;
    if (earliestEpochInfo == null || Long.compareUnsigned(epochInfo.epoch(), earliestEpochInfo.epoch()) != 0) {
      startedAt = sampledAt - ((long) epochInfo.slotIndex() * millsPerSlot);
    } else {
      startedAt = earliestEpochInfo.startedAt;
    }
    return new Epoch(startedAt, endsAt, epochInfo, millsPerSlot, slotStats, sampledAt);
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

  public long estimatedSlot(final long millisPerSlot) {
    return Math.min(
        info.slotIndex() + ((System.currentTimeMillis() - sampledAt) / millisPerSlot),
        info.slotsInEpoch()
    );
  }

  public long estimatedSlot() {
    return estimatedSlot(slotStats == null ? defaultMillisPerSlot : slotStats.median());
  }

  public String logFormat() {
    final int median = slotStats == null ? defaultMillisPerSlot : slotStats.median();
    final long millisRemaining = millisRemaining();
    final long estimatedSlot = estimatedSlot(median);
    final long slotsPerEpoch = slotsPerEpoch();
    final double percentProgress = (estimatedSlot / (double) slotsPerEpoch) * 100;
    final long startedAgo = System.currentTimeMillis() - startedAt;
    return String.format(
        "Epoch %s :: Start %s ago :: Ends in %s | %s :: %d ms/slot | %d epochs/year :: %,d / %,d | %.1f%%",
        Long.toUnsignedString(epoch()),
        Duration.ofMillis(startedAgo).truncatedTo(MINUTES).toString().substring(2),
        Duration.ofMillis(millisRemaining).truncatedTo(MINUTES).toString().substring(2),
        LocalDateTime.ofInstant(Instant.ofEpochMilli(endsAt), ZoneId.systemDefault()).truncatedTo(MINUTES),
        median,
        epochsPerYear(median),
        estimatedSlot, slotsPerEpoch,
        percentProgress
    );
  }
}
