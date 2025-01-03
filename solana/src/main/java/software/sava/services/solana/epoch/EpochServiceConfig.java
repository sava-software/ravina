package software.sava.services.solana.epoch;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;

import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record EpochServiceConfig(int defaultMillisPerSlot,
                                 Duration slotSampleWindow,
                                 Duration fetchSlotSamplesDelay,
                                 Duration fetchEpochInfoAfterEndDelay) {

  public static EpochServiceConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.createConfig();
  }

  public static EpochServiceConfig createDefault() {
    return new EpochServiceConfig(
        420,
        Duration.ofMinutes(60),
        Duration.ofMinutes(15),
        Duration.ofSeconds(1)
    );
  }

  private static final class Parser implements FieldBufferPredicate {

    private int defaultMillisPerSlot;
    private Duration slotSampleWindow;
    private Duration fetchSlotSamplesDelay;
    private Duration fetchEpochInfoAfterEndDelay;

    private Parser() {
    }

    private EpochServiceConfig createConfig() {
      if (defaultMillisPerSlot <= 0) {
        defaultMillisPerSlot = 420;
      }
      if (slotSampleWindow == null) {
        slotSampleWindow = Duration.ofMinutes(60);
      }
      if (fetchSlotSamplesDelay == null) {
        slotSampleWindow.dividedBy(4);
      }
      return new EpochServiceConfig(
          defaultMillisPerSlot,
          slotSampleWindow,
          fetchSlotSamplesDelay,
          fetchEpochInfoAfterEndDelay == null
              ? Duration.ofSeconds(1)
              : fetchEpochInfoAfterEndDelay
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("defaultMillisPerSlot", buf, offset, len)) {
        defaultMillisPerSlot = ji.readInt();
      } else if (fieldEquals("slotSampleWindow", buf, offset, len)) {
        slotSampleWindow = parseDuration(ji);
      } else if (fieldEquals("fetchSlotSamplesDelay", buf, offset, len)) {
        fetchSlotSamplesDelay = parseDuration(ji);
      } else if (fieldEquals("fetchEpochInfoAfterEndDelay", buf, offset, len)) {
        fetchEpochInfoAfterEndDelay = parseDuration(ji);
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
