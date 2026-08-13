package software.sava.services.solana.epoch;

import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.config.ServiceConfigUtil;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static software.sava.services.solana.epoch.SlotPerformanceStats.DEFAULT_MAX_MILLIS_PER_SLOT;
import static software.sava.services.solana.epoch.SlotPerformanceStats.DEFAULT_MIN_MILLIS_PER_SLOT;
import static software.sava.services.solana.epoch.SlotPerformanceStats.MIN_TARGET_MILLIS_PER_SLOT;

/// Slot duration is normally derived from recent performance samples. The
/// default duration is only a no-sample fallback near the fastest rollout
/// target; on slower rollout stages it intentionally prompts earlier checks.
/// The minimum and maximum bound observations before they feed epoch estimates
/// and monitor pacing.
public record EpochServiceConfig(int defaultMillisPerSlot,
                                 int minMillisPerSlot,
                                 int maxMillisPerSlot,
                                 Duration slotSampleWindow,
                                 Duration fetchSlotSamplesDelay,
                                 Duration fetchEpochInfoAfterEndDelay) {

  private static final int DEFAULT_MILLIS_PER_SLOT = MIN_TARGET_MILLIS_PER_SLOT + 10;

  public EpochServiceConfig {
    if (minMillisPerSlot > maxMillisPerSlot) {
      throw new IllegalArgumentException(String.format(
          "Minimum millis per slot (%d) cannot exceed maximum millis per slot (%d).",
          minMillisPerSlot, maxMillisPerSlot
      ));
    }
  }

  public static EpochServiceConfig parseConfig(final JsonIterator ji) {
    if (ji.readNull()) {
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createConfig();
    }
  }

  public static EpochServiceConfig parseConfig(final Properties properties) {
    return parseConfig("", properties);
  }

  public static EpochServiceConfig parseConfig(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.createConfig();
  }

  public static EpochServiceConfig createDefault() {
    return new EpochServiceConfig(
        DEFAULT_MILLIS_PER_SLOT,
        DEFAULT_MIN_MILLIS_PER_SLOT,
        DEFAULT_MAX_MILLIS_PER_SLOT,
        ofMinutes(21),
        ofMinutes(8),
        ofSeconds(1)
    );
  }

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private int defaultMillisPerSlot = DEFAULT_MILLIS_PER_SLOT;
    private int minMillisPerSlot = DEFAULT_MIN_MILLIS_PER_SLOT;
    private int maxMillisPerSlot = DEFAULT_MAX_MILLIS_PER_SLOT;
    private Duration slotSampleWindow;
    private Duration fetchSlotSamplesDelay;
    private Duration fetchEpochInfoAfterEndDelay;

    private Parser() {
    }

    void parseProperties(final String prefix, final Properties properties) {
      final var _prefix = propertyPrefix(prefix);
      parseInt(properties, _prefix, "defaultMillisPerSlot").ifPresent(v -> this.defaultMillisPerSlot = v);
      parseInt(properties, _prefix, "minMillisPerSlot").ifPresent(v -> this.minMillisPerSlot = v);
      parseInt(properties, _prefix, "maxMillisPerSlot").ifPresent(v -> this.maxMillisPerSlot = v);
      final var slotSampleWindow = parseDuration(properties, _prefix, "slotSampleWindow");
      if (slotSampleWindow != null) {
        this.slotSampleWindow = slotSampleWindow;
      }
      final var fetchSlotSamplesDelay = parseDuration(properties, _prefix, "fetchSlotSamplesDelay");
      if (fetchSlotSamplesDelay != null) {
        this.fetchSlotSamplesDelay = fetchSlotSamplesDelay;
      }
      final var fetchEpochInfoAfterEndDelay = parseDuration(properties, _prefix, "fetchEpochInfoAfterEndDelay");
      if (fetchEpochInfoAfterEndDelay != null) {
        this.fetchEpochInfoAfterEndDelay = fetchEpochInfoAfterEndDelay;
      }
    }

    private EpochServiceConfig createConfig() {
      if (slotSampleWindow == null) {
        slotSampleWindow = ofMinutes(21);
      }
      if (fetchSlotSamplesDelay == null) {
        fetchSlotSamplesDelay = ofMinutes(8);
      }
      return new EpochServiceConfig(
          defaultMillisPerSlot,
          minMillisPerSlot,
          maxMillisPerSlot,
          slotSampleWindow,
          fetchSlotSamplesDelay,
          fetchEpochInfoAfterEndDelay == null
              ? ofSeconds(1)
              : fetchEpochInfoAfterEndDelay
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "defaultMillisPerSlot", "minMillisPerSlot", "maxMillisPerSlot",
        "slotSampleWindow", "fetchSlotSamplesDelay", "fetchEpochInfoAfterEndDelay"
    );

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      switch (FIELDS.match(buf, offset, len)) {
        case 0 -> defaultMillisPerSlot = ji.readInt();
        case 1 -> minMillisPerSlot = ji.readInt();
        case 2 -> maxMillisPerSlot = ji.readInt();
        case 3 -> slotSampleWindow = ServiceConfigUtil.parseDuration(ji);
        case 4 -> fetchSlotSamplesDelay = ServiceConfigUtil.parseDuration(ji);
        case 5 -> fetchEpochInfoAfterEndDelay = ServiceConfigUtil.parseDuration(ji);
        default ->
            throw new IllegalStateException("Unknown EpochServiceConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
