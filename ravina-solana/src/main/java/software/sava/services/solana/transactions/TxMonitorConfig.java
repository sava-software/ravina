package software.sava.services.solana.transactions;

import software.sava.services.core.config.PropertiesParser;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import java.time.Duration;
import java.util.Properties;

import software.sava.services.core.config.ServiceConfigUtil;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record TxMonitorConfig(Duration minSleepBetweenSigStatusPolling,
                              Duration webSocketConfirmationTimeout,
                              Duration retrySendDelay,
                              int minBlocksRemainingToResend) {

  public static TxMonitorConfig parseConfig(final Properties properties) {
    return parseConfig("", properties);
  }

  public static TxMonitorConfig parseConfig(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  public static TxMonitorConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static TxMonitorConfig createDefault() {
    final var fiveSeconds = Duration.ofSeconds(5);
    return new TxMonitorConfig(
        Duration.ofSeconds(3),
        fiveSeconds,
        fiveSeconds,
        8
    );
  }

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private Duration minSleepBetweenSigStatusPolling;
    private Duration webSocketConfirmationTimeout;
    private Duration retrySendDelay;
    private int minBlocksRemainingToResend = 8;

    private Parser() {
    }

    private void parseProperties(final String prefix, final Properties properties) {
      final var p = propertyPrefix(prefix);
      final var minSleepBetweenSigStatusPolling = PropertiesParser.parseDuration(properties, p, "minSleepBetweenSigStatusPolling");
      if (minSleepBetweenSigStatusPolling != null) {
        this.minSleepBetweenSigStatusPolling = minSleepBetweenSigStatusPolling;
      }
      final var webSocketConfirmationTimeout = PropertiesParser.parseDuration(properties, p, "webSocketConfirmationTimeout");
      if (webSocketConfirmationTimeout != null) {
        this.webSocketConfirmationTimeout = webSocketConfirmationTimeout;
      }
      final var retrySendDelay = PropertiesParser.parseDuration(properties, p, "retrySendDelay");
      if (retrySendDelay != null) {
        this.retrySendDelay = retrySendDelay;
      }
      parseInt(properties, p, "minBlocksRemainingToResend").ifPresent(v -> this.minBlocksRemainingToResend = v);
    }

    private TxMonitorConfig create() {
      return new TxMonitorConfig(
          minSleepBetweenSigStatusPolling == null ? Duration.ofSeconds(3) : minSleepBetweenSigStatusPolling,
          webSocketConfirmationTimeout == null ? Duration.ofSeconds(5) : webSocketConfirmationTimeout,
          retrySendDelay == null ? Duration.ofSeconds(5) : retrySendDelay,
          minBlocksRemainingToResend
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("minSleepBetweenSigStatusPolling", buf, offset, len)) {
        minSleepBetweenSigStatusPolling = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("webSocketConfirmationTimeout", buf, offset, len)) {
        webSocketConfirmationTimeout = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("retrySendDelay", buf, offset, len)) {
        retrySendDelay = ServiceConfigUtil.parseDuration(ji);
      } else if (fieldEquals("minBlocksRemainingToResend", buf, offset, len)) {
        minBlocksRemainingToResend = ji.readInt();
      } else {
        throw new IllegalStateException("Unknown TxMonitorConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
