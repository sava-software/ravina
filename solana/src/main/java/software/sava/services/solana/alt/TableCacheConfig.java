package software.sava.services.solana.alt;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.time.Duration;

import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TableCacheConfig(int initialCapacity,
                               Duration refreshStaleItemsDelay,
                               Duration consideredStale) {

  private static final int DEFAULT_INITIAL_CAPACITY = 1_024;
  private static final Duration DEFAULT_CONSIDERED_STALE = Duration.ofHours(8);

  public static TableCacheConfig parse(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Builder();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static TableCacheConfig createDefault() {
    return new TableCacheConfig(
        DEFAULT_INITIAL_CAPACITY,
        DEFAULT_CONSIDERED_STALE.dividedBy(2),
        DEFAULT_CONSIDERED_STALE
    );
  }

  private static final class Builder implements FieldBufferPredicate {

    private int initialCapacity = DEFAULT_INITIAL_CAPACITY;
    private Duration refreshStaleItemsDelay;
    private Duration consideredStale = DEFAULT_CONSIDERED_STALE;

    private Builder() {
    }

    private TableCacheConfig create() {
      return new TableCacheConfig(
          initialCapacity,
          refreshStaleItemsDelay == null
              ? consideredStale.dividedBy(2)
              : refreshStaleItemsDelay,
          consideredStale
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("initialCapacity", buf, offset, len)) {
        initialCapacity = ji.readInt();
      } else if (fieldEquals("refreshStaleItemsDelay", buf, offset, len)) {
        refreshStaleItemsDelay = parseDuration(ji);
      } else if (fieldEquals("consideredStale", buf, offset, len)) {
        consideredStale = parseDuration(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
