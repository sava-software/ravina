package software.sava.services.core.request_capacity;

import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;
import systems.comodal.jsoniter.ContextFieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.time.Duration;

import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record CapacityConfig(int minCapacity,
                             int maxCapacity,
                             Duration resetDuration,
                             int maxGroupedErrorResponses,
                             Duration maxGroupedErrorExpiration,
                             Duration tooManyErrorsBackoffDuration,
                             Duration serverErrorBackOffDuration,
                             Duration rateLimitedBackOffDuration) {

  public static CapacityConfig createSimpleConfig(final Duration minCapacityDuration,
                                                  final int maxCapacity,
                                                  final Duration resetDuration) {
    final var builder = new Builder();
    builder.minCapacityDuration = minCapacityDuration;
    builder.maxCapacity = maxCapacity;
    builder.resetDuration = resetDuration;
    return builder.createConfig();
  }

  public static CapacityConfig parse(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      return ji.testObject(new Builder(), PARSER).createConfig();
    }
  }

  public <R> ErrorTrackedCapacityMonitor<R> createMonitor(final String serviceName,
                                                          final ErrorTrackerFactory<R> errorTrackerFactory) {
    final var capacityState = new CapacityStateVal(this);
    return new CapacityMonitorRecord<>(
        serviceName,
        capacityState,
        errorTrackerFactory.createTracker(capacityState)
    );
  }

  public ErrorTrackedCapacityMonitor<HttpResponse<byte[]>> createHttpResponseMonitor(final String serviceName) {
    final var capacityState = new CapacityStateVal(this);
    return new CapacityMonitorRecord<>(
        serviceName,
        capacityState,
        HttpErrorTrackerFactory.INSTANCE.createTracker(capacityState)
    );
  }

  private static final ContextFieldBufferPredicate<Builder> PARSER = (builder, buf, offset, len, ji) -> {
    if (fieldEquals("minCapacity", buf, offset, len)) {
      builder.minCapacity = ji.readInt();
    } else if (fieldEquals("minCapacityDuration", buf, offset, len)) {
      builder.minCapacityDuration = ServiceConfigUtil.parseDuration(ji);
    } else if (fieldEquals("maxCapacity", buf, offset, len)) {
      builder.maxCapacity = ji.readInt();
    } else if (fieldEquals("resetDuration", buf, offset, len)) {
      builder.resetDuration = ServiceConfigUtil.parseDuration(ji);
    } else if (fieldEquals("maxGroupedErrorResponses", buf, offset, len)) {
      builder.maxGroupedErrorResponses = ji.readInt();
    } else if (fieldEquals("maxGroupedErrorExpiration", buf, offset, len)) {
      builder.maxGroupedErrorExpiration = ServiceConfigUtil.parseDuration(ji);
    } else if (fieldEquals("tooManyErrorsBackoffDuration", buf, offset, len)) {
      builder.tooManyErrorsBackoffDuration = ServiceConfigUtil.parseDuration(ji);
    } else if (fieldEquals("serverErrorBackOffDuration", buf, offset, len)) {
      builder.serverErrorBackOffDuration = ServiceConfigUtil.parseDuration(ji);
    } else if (fieldEquals("rateLimitedBackOffDuration", buf, offset, len)) {
      builder.rateLimitedBackOffDuration = ServiceConfigUtil.parseDuration(ji);
    } else {
      throw new IllegalStateException("Unhandled CapacityConfig field " + new String(buf, offset, len));
    }
    return true;
  };

  private static final class Builder {

    private int minCapacity;
    private Duration minCapacityDuration;
    private int maxCapacity;
    private Duration resetDuration;
    private int maxGroupedErrorResponses;
    private Duration maxGroupedErrorExpiration;
    private Duration serverErrorBackOffDuration;
    private Duration tooManyErrorsBackoffDuration;
    private Duration rateLimitedBackOffDuration;

    private Builder() {
    }

    private static int capacityFromDuration(final Duration resetDuration,
                                            final int maxCapacity,
                                            final Duration minCapacityDuration) {
      final long millis = resetDuration.toMillis();
      final long capacityPerMillis = maxCapacity * millis;
      final long minCapacityDurationMillis = minCapacityDuration.toMillis();
      return (int) -((capacityPerMillis * minCapacityDurationMillis) / 1_000_000);
    }

    CapacityConfig createConfig() {
      if (minCapacityDuration != null) {
        this.minCapacity = capacityFromDuration(resetDuration, maxCapacity, minCapacityDuration);
      }
      return new CapacityConfig(
          minCapacity,
          maxCapacity,
          resetDuration,
          maxGroupedErrorResponses == 0 ? 8 : maxGroupedErrorResponses,
          requireNonNullElse(maxGroupedErrorExpiration, resetDuration),
          requireNonNullElse(tooManyErrorsBackoffDuration, resetDuration),
          requireNonNullElse(serverErrorBackOffDuration, ofSeconds(1)),
          requireNonNullElse(rateLimitedBackOffDuration, resetDuration)
      );
    }
  }
}
