package software.sava.services.solana.epoch;

import software.sava.rpc.json.http.response.PerfSample;

import java.util.Arrays;
import java.util.List;

public record SlotPerformanceStats(int median,
                                   int mean,
                                   int min,
                                   int max,
                                   double estimatedStdDev,
                                   int numPerfSamples) {

  /// The fastest target in SIMD-0525's staged slot-time rollout. This is the
  /// lower end of the supported target range, not the cluster's current slot
  /// duration; the latter is measured from performance samples.
  public static final int MIN_TARGET_MILLIS_PER_SLOT = 200;

  /// The default sample floor leaves room for ordinary drift below the 200ms
  /// target.
  public static final int DEFAULT_MIN_MILLIS_PER_SLOT = MIN_TARGET_MILLIS_PER_SLOT - 10;

  /// The default sample ceiling retains room for unusually slow slots.
  public static final int DEFAULT_MAX_MILLIS_PER_SLOT = 500;

  /**
   * @deprecated A cluster has no single compile-time target during the staged
   * rollout. Use {@link #MIN_TARGET_MILLIS_PER_SLOT} as a boundary or measured
   * slot performance for wall-clock estimates.
   */
  @Deprecated(forRemoval = false)
  public static final int TARGET_MILLIS_PER_SLOT = MIN_TARGET_MILLIS_PER_SLOT;

  /// Calculates observed slot-time statistics within the default supported
  /// range of [#DEFAULT_MIN_MILLIS_PER_SLOT] through
  /// [#DEFAULT_MAX_MILLIS_PER_SLOT].
  public static SlotPerformanceStats calculateStats(final List<PerfSample> samples) {
    return calculateStats(samples, DEFAULT_MIN_MILLIS_PER_SLOT, DEFAULT_MAX_MILLIS_PER_SLOT);
  }

  /// Calculates observed slot-time statistics using the default floor and the
  /// supplied ceiling.
  public static SlotPerformanceStats calculateStats(final List<PerfSample> samples, final int maxMillis) {
    return calculateStats(samples, DEFAULT_MIN_MILLIS_PER_SLOT, maxMillis);
  }

  /// Calculates observed slot-time statistics after bounding every usable
  /// sample to the supplied inclusive range.
  public static SlotPerformanceStats calculateStats(final List<PerfSample> samples,
                                                    final int minMillis,
                                                    final int maxMillis) {
    if (minMillis > maxMillis) {
      throw new IllegalArgumentException(String.format(
          "Minimum millis per slot (%d) cannot exceed maximum millis per slot (%d).",
          minMillis, maxMillis
      ));
    }
    final var msPerSlotArray = samples.stream()
        .filter(s ->
            Long.compareUnsigned(s.numSlots(), s.slot()) < 0 // Ignore opening epoch slots.
                && s.samplePeriodSecs() > 0
                && s.numSlots() > 0)
        .mapToInt(s -> {
          final int millisPerSlot = (int) Math.round((s.samplePeriodSecs() / (double) s.numSlots()) * 1_000);
          return Math.clamp(millisPerSlot, minMillis, maxMillis);
        })
        .sorted()
        .toArray();
    final int numPerfSamples = msPerSlotArray.length;
    if (numPerfSamples == 0) {
      return null;
    } else if (numPerfSamples == 1) {
      final int minMaxAvg = msPerSlotArray[0];
      return new SlotPerformanceStats(
          minMaxAvg,
          minMaxAvg,
          minMaxAvg,
          minMaxAvg,
          0,
          1
      );
    } else {
      final int middle = numPerfSamples >> 1;
      final int median = (numPerfSamples & 1) == 1
          ? msPerSlotArray[middle]
          : (int) Math.round((msPerSlotArray[middle - 1] + msPerSlotArray[middle]) / 2.0);
      final int mean = (int) Math.round(Arrays.stream(msPerSlotArray).average().orElseThrow());

      final int min = msPerSlotArray[0];
      final int max = msPerSlotArray[numPerfSamples - 1];
      final double estimatedStd = (max - min) / 6.0;
      return new SlotPerformanceStats(
          median,
          mean,
          min,
          max,
          estimatedStd,
          numPerfSamples
      );
    }
  }

  public int medianPercentile(final double zScore) {
    return (int) Math.round(median + (zScore * estimatedStdDev));
  }

  public int medianPercentile95() {
    return medianPercentile(1.645);
  }

  public int medianPercentile68() {
    return (int) Math.round(median + estimatedStdDev);
  }

  public int percentile(final double zScore) {
    return (int) Math.round(mean + (zScore * estimatedStdDev));
  }

  public int percentile95() {
    return percentile(1.645);
  }

  public int percentile68() {
    return (int) Math.round(mean + estimatedStdDev);
  }
}
