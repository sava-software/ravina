package software.sava.services.core.request_capacity;

import software.sava.services.core.NanoClock;
import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import software.sava.services.core.request_capacity.trackers.HttpErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

import static java.time.Duration.ofSeconds;
import static java.util.Objects.requireNonNullElse;

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
    return createMonitor(serviceName, errorTrackerFactory, NanoClock.SYSTEM);
  }

  public <R> ErrorTrackedCapacityMonitor<R> createMonitor(final String serviceName,
                                                          final ErrorTrackerFactory<R> errorTrackerFactory,
                                                          final NanoClock clock) {
    final var capacityState = new CapacityStateVal(this, clock);
    return new CapacityMonitorRecord<>(
        serviceName,
        capacityState,
        errorTrackerFactory.createTracker(capacityState)
    );
  }

  public ErrorTrackedCapacityMonitor<HttpResponse<?>> createHttpResponseMonitor(final String serviceName) {
    final var capacityState = new CapacityStateVal(this, NanoClock.SYSTEM);
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

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "minCapacity", "minCapacityDuration", "maxCapacity", "resetDuration",
        "maxGroupedErrorResponses", "maxGroupedErrorExpiration",
        "tooManyErrorsBackoffDuration", "serverErrorBackOffDuration", "rateLimitedBackOffDuration"
    );

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      switch (FIELDS.match(buf, offset, len)) {
        case 0 -> this.minCapacity = ji.readInt();
        case 1 -> this.minCapacityDuration = ServiceConfigUtil.parseDuration(ji);
        case 2 -> this.maxCapacity = ji.readInt();
        case 3 -> this.resetDuration = ServiceConfigUtil.parseDuration(ji);
        case 4 -> this.maxGroupedErrorResponses = ji.readInt();
        case 5 -> this.maxGroupedErrorExpiration = ServiceConfigUtil.parseDuration(ji);
        case 6 -> this.tooManyErrorsBackoffDuration = ServiceConfigUtil.parseDuration(ji);
        case 7 -> this.serverErrorBackOffDuration = ServiceConfigUtil.parseDuration(ji);
        case 8 -> this.rateLimitedBackOffDuration = ServiceConfigUtil.parseDuration(ji);
        default ->
            throw new IllegalStateException("Unhandled CapacityConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
