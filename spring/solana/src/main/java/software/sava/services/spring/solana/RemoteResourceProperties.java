package software.sava.services.spring.solana;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.convert.DurationUnit;
import software.sava.services.core.remote.call.BackoffStrategy;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Getter
@Setter
public class RemoteResourceProperties {

  public String endpoint;

  @DurationUnit(ChronoUnit.SECONDS)
  public Duration minCapacityDuration;
  public int maxCapacity;
  @DurationUnit(ChronoUnit.SECONDS)
  public Duration resetDuration;

  public BackoffStrategy backoffStrategy;
  public int initialRetryDelaySeconds;
  public int maxRetryDelaySeconds;
}
