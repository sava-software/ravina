package software.sava.services.spring.solana;

import org.springframework.boot.convert.DurationUnit;
import software.sava.services.core.request_capacity.CapacityConfig;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

public class CapacityConfigProperties {

  @DurationUnit(ChronoUnit.SECONDS)
  private Duration minCapacityDuration;
  private int maxCapacity;
  @DurationUnit(ChronoUnit.SECONDS)
  private Duration resetDuration;

  public CapacityConfig createCapacityConfig() {
    return resetDuration == null
        ? null
        : CapacityConfig.createSimpleConfig(minCapacityDuration, maxCapacity, resetDuration);
  }

  public CapacityConfigProperties setMinCapacityDuration(final Duration minCapacityDuration) {
    this.minCapacityDuration = minCapacityDuration;
    return this;
  }

  public CapacityConfigProperties setMaxCapacity(final int maxCapacity) {
    this.maxCapacity = maxCapacity;
    return this;
  }

  public CapacityConfigProperties setResetDuration(final Duration resetDuration) {
    this.resetDuration = resetDuration;
    return this;
  }

  public Duration getMinCapacityDuration() {
    return minCapacityDuration;
  }

  public int getMaxCapacity() {
    return maxCapacity;
  }

  public Duration getResetDuration() {
    return resetDuration;
  }

  @Override
  public String toString() {
    return "CapacityConfigProperties{" +
        "minCapacityDuration=" + minCapacityDuration +
        ", maxCapacity=" + maxCapacity +
        ", resetDuration=" + resetDuration +
        '}';
  }
}
