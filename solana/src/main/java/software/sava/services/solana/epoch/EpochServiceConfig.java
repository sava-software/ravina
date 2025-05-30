package software.sava.services.solana.epoch;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.time.Duration;

import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;
import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static software.sava.services.solana.epoch.SlotPerformanceStats.TARGET_MILLIS_PER_SLOT;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochServiceConfig(int defaultMillisPerSlot,
                                 int minMillisPerSlot,
                                 int maxMillisPerSlot,
                                 Duration slotSampleWindow,
                                 Duration fetchSlotSamplesDelay,
                                 Duration fetchEpochInfoAfterEndDelay) {

  public static EpochServiceConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.createConfig();
    }
  }

  public static EpochServiceConfig createDefault() {
    return new EpochServiceConfig(
        TARGET_MILLIS_PER_SLOT + 10,
        TARGET_MILLIS_PER_SLOT - 10,
        500,
        ofMinutes(21),
        ofMinutes(8),
        ofSeconds(1)
    );
  }

  private static final class Parser implements FieldBufferPredicate {

    private int defaultMillisPerSlot = TARGET_MILLIS_PER_SLOT + 10;
    private int minMillisPerSlot = TARGET_MILLIS_PER_SLOT - 10;
    private int maxMillisPerSlot = 500;
    private Duration slotSampleWindow;
    private Duration fetchSlotSamplesDelay;
    private Duration fetchEpochInfoAfterEndDelay;

    private Parser() {
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

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("defaultMillisPerSlot", buf, offset, len)) {
        defaultMillisPerSlot = ji.readInt();
      } else if (fieldEquals("minMillisPerSlot", buf, offset, len)) {
        minMillisPerSlot = ji.readInt();
      } else if (fieldEquals("maxMillisPerSlot", buf, offset, len)) {
        maxMillisPerSlot = ji.readInt();
      } else if (fieldEquals("slotSampleWindow", buf, offset, len)) {
        slotSampleWindow = parseDuration(ji);
      } else if (fieldEquals("fetchSlotSamplesDelay", buf, offset, len)) {
        fetchSlotSamplesDelay = parseDuration(ji);
      } else if (fieldEquals("fetchEpochInfoAfterEndDelay", buf, offset, len)) {
        fetchEpochInfoAfterEndDelay = parseDuration(ji);
      } else {
        throw new IllegalStateException("Unknown EpochServiceConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
