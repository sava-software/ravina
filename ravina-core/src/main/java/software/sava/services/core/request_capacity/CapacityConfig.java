package software.sava.services.core.request_capacity;

import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

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
    final var builder = new Parser();
    builder.minCapacityDuration = minCapacityDuration;
    builder.maxCapacity = maxCapacity;
    builder.resetDuration = resetDuration;
    return builder.createConfig();
  }

  public static CapacityConfig parse(final Properties properties) {
    return parse("", properties);
  }

  public static CapacityConfig parse(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.createConfig();
  }

  public static CapacityConfig parse(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createConfig();
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

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private int minCapacity;
    private Duration minCapacityDuration;
    private int maxCapacity;
    private Duration resetDuration;
    private int maxGroupedErrorResponses;
    private Duration maxGroupedErrorExpiration;
    private Duration serverErrorBackOffDuration;
    private Duration tooManyErrorsBackoffDuration;
    private Duration rateLimitedBackOffDuration;

    private Parser() {
    }

    void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      parseInt(properties, p, "minCapacity").ifPresent(v -> this.minCapacity = v);
      this.minCapacityDuration = parseDuration(properties, p, "minCapacityDuration");
      parseInt(properties, p, "maxCapacity").ifPresent(v -> this.maxCapacity = v);
      this.resetDuration = parseDuration(properties, p, "resetDuration");
      parseInt(properties, p, "maxGroupedErrorResponses").ifPresent(v -> this.maxGroupedErrorResponses = v);
      this.maxGroupedErrorExpiration = parseDuration(properties, p, "maxGroupedErrorExpiration");
      this.tooManyErrorsBackoffDuration = parseDuration(properties, p, "tooManyErrorsBackoffDuration");
      this.serverErrorBackOffDuration = parseDuration(properties, p, "serverErrorBackOffDuration");
      this.rateLimitedBackOffDuration = parseDuration(properties, p, "rateLimitedBackOffDuration");
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

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("minCapacity", buf, offset, len)) {
        this.minCapacity = ji.readInt();
      } else if (fieldEquals("minCapacityDuration", buf, offset, len)) {
        this.minCapacityDuration = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("maxCapacity", buf, offset, len)) {
        this.maxCapacity = ji.readInt();
      } else if (fieldEquals("resetDuration", buf, offset, len)) {
        this.resetDuration = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("maxGroupedErrorResponses", buf, offset, len)) {
        this.maxGroupedErrorResponses = ji.readInt();
      } else if (fieldEquals("maxGroupedErrorExpiration", buf, offset, len)) {
        this.maxGroupedErrorExpiration = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("tooManyErrorsBackoffDuration", buf, offset, len)) {
        this.tooManyErrorsBackoffDuration = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("serverErrorBackOffDuration", buf, offset, len)) {
        this.serverErrorBackOffDuration = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("rateLimitedBackOffDuration", buf, offset, len)) {
        this.rateLimitedBackOffDuration = ServiceConfigUtil.parseDuration(ji);
      } else {
        throw new IllegalStateException("Unhandled CapacityConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
