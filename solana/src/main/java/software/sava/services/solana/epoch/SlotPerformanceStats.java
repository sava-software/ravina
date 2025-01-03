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

  public static SlotPerformanceStats calculateStats(final List<PerfSample> samples) {
    final var msPerSlotArray = samples.stream()
        .filter(s -> Long.compareUnsigned(s.numSlots(), s.slot()) < 0) // Ignore opening epoch slots.
        .mapToInt(s -> (int) Math.round((s.samplePeriodSecs() / (double) s.numSlots()) * 1_000))
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
          : (int) Math.round((msPerSlotArray[middle] + msPerSlotArray[middle + 1]) / 2.0);
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
