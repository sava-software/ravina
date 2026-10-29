package software.sava.services.solana.transactions;

import software.sava.services.core.config.PropertiesParser;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.FieldMatcher;
import systems.comodal.jsoniter.JsonIterator;

import java.time.Duration;
import java.util.Properties;

import software.sava.services.core.config.ServiceConfigUtil;

/// Transaction-monitor pacing policy. The three durations are wall-clock, but
/// two of them are budgets spent inside a block hash's validity window, which
/// is 150 blocks whatever the slot time: SIMD-0525 kept the block hash queue
/// its size while it cut the slot time, so at 200 ms slots the window is about
/// 30 s, half of what it was at 400 ms, and a slot-time change calls for
/// retuning these values rather than leaving them fixed. The websocket timeout
/// elapses before polling and client-side resending begin, and those resends
/// are the only retries after publication because transactions are sent with
/// RPC `maxRetries=0`, so a transaction that was not notified is first resent
/// once the websocket timeout has run out, provided the timeout is at least
/// `retrySendDelay`, as the defaults are, and otherwise at the first polling
/// pass after the delay, which is also when a transaction with no websocket at
/// all is first resent; later resends follow every `retrySendDelay` rounded up
/// to whole polling passes, each a floor of `minSleepBetweenSigStatusPolling`
/// plus the pass's own RPC time, or sooner when another transaction's due
/// arrival wakes the worker, since a pass polls and resends the whole batch.
/// Resends stop once no more than
/// `minBlocksRemainingToResend` blocks of the window remain, so the later of
/// the websocket timeout and `retrySendDelay`, plus one polling floor, must
/// stay well under the window less that floor of blocks, about 28 s at 200 ms
/// slots, or a lost transaction is never resent and recovers only through its
/// expiry. The defaults suit 200 ms slots and resend at agave's own
/// send-service retry rate; raise the websocket timeout only where resends are
/// not needed, such as a TowerBFT test validator whose finalized subscription
/// lags confirmation. `minBlocksRemainingToResend` is chain-progress based and
/// its wall-clock span shrinks with faster slots by itself.
public record TxMonitorConfig(Duration minSleepBetweenSigStatusPolling,
                              Duration webSocketConfirmationTimeout,
                              Duration retrySendDelay,
                              int minBlocksRemainingToResend) {

  /// The longest the polling worker waits between passes while nothing is
  /// due: a queued transaction whose resend is already due ends the wait early,
  /// so passes can follow timed-out websocket awaits at up to one per await.
  /// One second keeps the resend period within a pass of
  /// [#DEFAULT_RETRY_SEND_DELAY].
  public static final Duration DEFAULT_MIN_SLEEP_BETWEEN_SIG_STATUS_POLLING = Duration.ofSeconds(1);
  /// How long a signature subscription is awaited before polling takes over,
  /// and, since it covers [#DEFAULT_RETRY_SEND_DELAY], the earliest a
  /// transaction is resent: a tenth of the window at 200 ms slots, and margin
  /// over a notification latency measured only on a one-node `--alpenglow`
  /// test validator, where no block, vote or certificate crosses a network. A
  /// mainnet RPC node adds the block's propagation and the voting rounds and,
  /// unless a staked validator pushes certificates to it, waits for a later
  /// block's footer to carry the finalization certificate; none of that has
  /// been measured.
  public static final Duration DEFAULT_WEB_SOCKET_CONFIRMATION_TIMEOUT = Duration.ofSeconds(3);
  /// Agave's own send-service retry rate.
  public static final Duration DEFAULT_RETRY_SEND_DELAY = Duration.ofSeconds(2);
  /// About two leader windows of four slots each.
  public static final int DEFAULT_MIN_BLOCKS_REMAINING_TO_RESEND = 8;

  public static TxMonitorConfig parseConfig(final Properties properties) {
    return parseConfig("", properties);
  }

  public static TxMonitorConfig parseConfig(final String prefix, final Properties properties) {
    final var parser = new Parser();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  public static TxMonitorConfig parseConfig(final JsonIterator ji) {
    if (ji.readNull()) {
      return null;
    } else {
      final var parser = new Parser();
      ji.testObject(parser);
      return parser.create();
    }
  }

  public static TxMonitorConfig createDefault() {
    return new TxMonitorConfig(
        DEFAULT_MIN_SLEEP_BETWEEN_SIG_STATUS_POLLING,
        DEFAULT_WEB_SOCKET_CONFIRMATION_TIMEOUT,
        DEFAULT_RETRY_SEND_DELAY,
        DEFAULT_MIN_BLOCKS_REMAINING_TO_RESEND
    );
  }

  private static final class Parser extends PropertiesParser implements FieldBufferPredicate {

    private Duration minSleepBetweenSigStatusPolling;
    private Duration webSocketConfirmationTimeout;
    private Duration retrySendDelay;
    private int minBlocksRemainingToResend = DEFAULT_MIN_BLOCKS_REMAINING_TO_RESEND;

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
          minSleepBetweenSigStatusPolling == null ? DEFAULT_MIN_SLEEP_BETWEEN_SIG_STATUS_POLLING : minSleepBetweenSigStatusPolling,
          webSocketConfirmationTimeout == null ? DEFAULT_WEB_SOCKET_CONFIRMATION_TIMEOUT : webSocketConfirmationTimeout,
          retrySendDelay == null ? DEFAULT_RETRY_SEND_DELAY : retrySendDelay,
          minBlocksRemainingToResend
      );
    }

    private static final FieldMatcher FIELDS = FieldMatcher.of(
        "minSleepBetweenSigStatusPolling", "webSocketConfirmationTimeout",
        "retrySendDelay", "minBlocksRemainingToResend"
    );

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      switch (FIELDS.match(buf, offset, len)) {
        case 0 -> minSleepBetweenSigStatusPolling = ServiceConfigUtil.parseDuration(ji);
        case 1 -> webSocketConfirmationTimeout = ServiceConfigUtil.parseDuration(ji);
        case 2 -> retrySendDelay = ServiceConfigUtil.parseDuration(ji);
        case 3 -> minBlocksRemainingToResend = ji.readInt();
        default ->
            throw new IllegalStateException("Unknown TxMonitorConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
