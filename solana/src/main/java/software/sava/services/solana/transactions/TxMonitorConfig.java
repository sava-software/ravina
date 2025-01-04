package software.sava.services.solana.transactions;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;

import static software.sava.services.core.config.ServiceConfigUtil.parseDuration;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxMonitorConfig(Duration minSleepBetweenSigStatusPolling, Duration webSocketConfirmationTimeout) {

  public static TxMonitorConfig parseConfig(final JsonIterator ji) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create();
  }

  public static TxMonitorConfig createDefault() {
    return new TxMonitorConfig(
        Duration.ofSeconds(2),
        Duration.ofSeconds(8)
    );
  }

  private static final class Parser implements FieldBufferPredicate {

    private Duration minSleepBetweenSigStatusPolling;
    private Duration webSocketConfirmationTimeout;

    private Parser() {
    }

    private TxMonitorConfig create() {
      return new TxMonitorConfig(
          minSleepBetweenSigStatusPolling == null ? Duration.ofSeconds(2) : minSleepBetweenSigStatusPolling,
          webSocketConfirmationTimeout == null ? Duration.ofSeconds(8) : webSocketConfirmationTimeout
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("minSleepBetweenSigStatusPolling", buf, offset, len)) {
        minSleepBetweenSigStatusPolling = parseDuration(ji);
      } else if (fieldEquals("webSocketConfirmationTimeout", buf, offset, len)) {
        webSocketConfirmationTimeout = parseDuration(ji);
      } else {
        throw new IllegalStateException("Unknown TxMonitorConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
