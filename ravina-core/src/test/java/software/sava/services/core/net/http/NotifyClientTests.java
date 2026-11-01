package software.sava.services.core.net.http;

import org.junit.jupiter.api.Test;
import software.sava.services.core.LogSilencer;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.remote.call.ClientCaller;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.context.CallContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

final class NotifyClientTests {

  private static final URI ENDPOINT_A = URI.create("https://a.example.com/hook");
  private static final URI ENDPOINT_B = URI.create("https://b.example.com/hook");
  /// Shaped like a Slack incoming webhook, whose last path segment is the secret.
  private static final URI SECRET_ENDPOINT = URI.create("https://hooks.slack.com/services/T0000/B0000/not-a-real-secret");

  /// Records what was posted; never performs any I/O.
  private static final class RecordingWebHookClient implements WebHookClient {

    private final URI endpoint;
    private final boolean fail;
    private final List<String> received = new ArrayList<>();

    RecordingWebHookClient(final URI endpoint, final boolean fail) {
      this.endpoint = endpoint;
      this.fail = fail;
    }

    RecordingWebHookClient(final URI endpoint) {
      this(endpoint, false);
    }

    @Override
    public URI endpoint() {
      return endpoint;
    }

    @Override
    public HttpClient httpClient() {
      return null;
    }

    @Override
    public CompletableFuture<String> postMsg(final String msg) {
      received.add(msg);
      return fail
          ? CompletableFuture.failedFuture(new IllegalStateException("hook rejected"))
          : CompletableFuture.completedFuture(msg);
    }
  }

  /// Invokes the supplied function directly, so no capacity/backoff machinery is involved.
  private record DirectCaller(WebHookClient client) implements ClientCaller<WebHookClient> {

    @Override
    public CapacityState capacityState() {
      throw new UnsupportedOperationException("unused");
    }

    @Override
    public Backoff backoff() {
      throw new UnsupportedOperationException("unused");
    }

    @Override
    public <R> Call<R> createCourteousCall(final Function<WebHookClient, CompletableFuture<R>> call,
                                           final CallContext callContext,
                                           final String retryLogContext) {
      return () -> {
        final var future = call.apply(client);
        return future == null ? null : future.join();
      };
    }
  }

  private static String postAndCapture(final RecordingWebHookClient hook, final String msg) {
    final var executorService = Executors.newSingleThreadExecutor();
    try {
      final var notifyClient = NotifyClient.createClient(
          executorService,
          List.of(new DirectCaller(hook)),
          CallContext.DEFAULT_CALL_CONTEXT
      );
      final var futures = notifyClient.postMsg(msg);
      assertEquals(1, futures.size());
      futures.getFirst().join();
      assertEquals(1, hook.received.size());
      return hook.received.getFirst();
    } finally {
      executorService.shutdownNow();
    }
  }

  @Test
  void emptyHookListYieldsSharedNoopClient() {
    final var executorService = Executors.newSingleThreadExecutor();
    try {
      final var notifyClient = NotifyClient.createClient(
          executorService,
          List.of(),
          CallContext.DEFAULT_CALL_CONTEXT
      );
      assertNotNull(notifyClient);
      final var futures = notifyClient.postMsg("ignored");
      assertNotNull(futures);
      assertTrue(futures.isEmpty());
      // The no-op returns the canonical immutable empty list, not a fresh/other empty list.
      assertSame(List.<CompletableFuture<String>>of(), futures);
      assertSame(futures, notifyClient.postMsg("ignored again"));
    } finally {
      executorService.shutdownNow();
    }
  }

  @Test
  void nonEmptyHookListPostsToEveryHook() {
    final var executorService = Executors.newSingleThreadExecutor();
    try {
      final var hookA = new RecordingWebHookClient(ENDPOINT_A);
      final var hookB = new RecordingWebHookClient(ENDPOINT_B);
      final var notifyClient = NotifyClient.createClient(
          executorService,
          List.of(new DirectCaller(hookA), new DirectCaller(hookB)),
          CallContext.DEFAULT_CALL_CONTEXT
      );
      assertNotNull(notifyClient);

      final var futures = notifyClient.postMsg("hello");
      assertNotNull(futures);
      assertEquals(2, futures.size());
      // A fresh list per invocation, never the shared no-op list.
      assertNotSame(List.<CompletableFuture<String>>of(), futures);

      assertEquals("hello", futures.getFirst().join());
      assertEquals("hello", futures.getLast().join());
      assertEquals(List.of("hello"), hookA.received);
      assertEquals(List.of("hello"), hookB.received);
    } finally {
      executorService.shutdownNow();
    }
  }

  @Test
  void messageWithoutSpecialCharactersIsForwardedUnchanged() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "plain message with no escapes";
    final var forwarded = postAndCapture(hook, msg);
    assertEquals(msg, forwarded);
    // No copy is made when nothing needed escaping.
    assertSame(msg, forwarded);
  }

  @Test
  void quotesAreBackslashEscaped() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "say \"hi\"";
    final var forwarded = postAndCapture(hook, msg);
    assertEquals("say \\\"hi\\\"", forwarded);
    assertNotSame(msg, forwarded);
  }

  @Test
  void newLinesAreEscapedAsBackslashN() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "line1\nline2\n";
    final var forwarded = postAndCapture(hook, msg);
    assertEquals("line1\\nline2\\n", forwarded);
    assertNotSame(msg, forwarded);
  }

  @Test
  void quotesAndNewLinesAreEscapedTogether() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "a\"b\nc\"d";
    final var forwarded = postAndCapture(hook, msg);
    assertEquals("a\\\"b\\nc\\\"d", forwarded);
    assertEquals(msg.length() + 3, forwarded.length());
  }

  @Test
  void everyCharacterIsEscapedWhenAllNeedEscaping() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "\"\n\"\n";
    final var forwarded = postAndCapture(hook, msg);
    assertEquals("\\\"\\n\\\"\\n", forwarded);
    assertEquals(msg.length() << 1, forwarded.length());
  }

  @Test
  void emptyMessageIsForwardedUnchanged() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "";
    assertSame(msg, postAndCapture(hook, msg));
  }

  /// The expected hook failure is logged at WARNING **with the throwable**, by
  /// an `exceptionally` stage that runs on whichever thread completes the post.
  /// `join()` can therefore return before the log is written, which is why the
  /// stack trace used to surface against whichever test class happened to be
  /// running next. Silencing has to stay in force until the executor has
  /// drained, so the shutdown and the wait sit inside the silenced scope.
  @Test
  void failedHookStillYieldsAFutureInTheReturnedList() throws InterruptedException {
    final var executorService = Executors.newSingleThreadExecutor();
    try (var ignored = LogSilencer.silenced(NotifyClientImpl.class)) {
      try {
        final var hook = new RecordingWebHookClient(ENDPOINT_A, true);
        final var notifyClient = NotifyClient.createClient(
            executorService,
            List.of(new DirectCaller(hook)),
            CallContext.DEFAULT_CALL_CONTEXT
        );
        final var futures = notifyClient.postMsg("boom \"quoted\"");
        assertEquals(1, futures.size());
        final var future = futures.getFirst();
        assertThrows(RuntimeException.class, future::join);
        assertTrue(future.isCompletedExceptionally());
        assertEquals(List.of("boom \\\"quoted\\\""), hook.received);
      } finally {
        executorService.shutdownNow();
        assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
      }
    }
  }

  /// Failures are never silent: the hook failure must reach the log, with the
  /// throwable and the failing endpoint's host, but never the endpoint's path,
  /// which for a Slack webhook is its secret. A recording handler stands in for
  /// the console so the contract is asserted rather than suppressed; the
  /// executor is drained before asserting because the `exceptionally` stage
  /// runs on whichever thread completes the post.
  @Test
  void failedHookLogsAWarningWithTheThrowable() throws InterruptedException {
    final var logger = Logger.getLogger(NotifyClientImpl.class.getName());
    final var records = new CopyOnWriteArrayList<LogRecord>();
    final var handler = new Handler() {
      @Override
      public void publish(final LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    final boolean useParentHandlers = logger.getUseParentHandlers();
    logger.setUseParentHandlers(false);
    logger.addHandler(handler);
    final var executorService = Executors.newSingleThreadExecutor();
    try (var ignored = LogSilencer.forceLevel(NotifyClientImpl.class, Level.WARNING)) {
      final var hook = new RecordingWebHookClient(SECRET_ENDPOINT, true);
      final var notifyClient = NotifyClient.createClient(
          executorService,
          List.of(new DirectCaller(hook)),
          CallContext.DEFAULT_CALL_CONTEXT
      );
      final var futures = notifyClient.postMsg("boom");
      assertThrows(RuntimeException.class, futures.getFirst()::join);
      executorService.shutdownNow();
      assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));

      assertEquals(1, records.size());
      final var record = records.getFirst();
      assertEquals(Level.WARNING, record.getLevel());
      final var message = record.getMessage();
      assertTrue(message.contains("Failed to POST event to https://hooks.slack.com/(redacted):"), message);
      assertFalse(message.contains("not-a-real-secret"), message);
      assertFalse(message.contains("/services/"), message);
      assertTrue(message.contains("boom"), message);
      final var thrown = record.getThrown();
      assertNotNull(thrown);
      assertInstanceOf(IllegalStateException.class, thrown.getCause());
    } finally {
      logger.removeHandler(handler);
      logger.setUseParentHandlers(useParentHandlers);
      executorService.shutdownNow();
    }
  }

  /// Decodes the body of a JSON string literal by RFC 8259 section 7, the oracle for the escaping
  /// tests below, independent of any parser: it accepts nothing the grammar forbids (a raw control
  /// character, an unescaped quote, an escape the grammar does not define, a truncated one) and
  /// returns the text a conforming receiver reads.
  private static String decodeJsonStringBody(final String body) {
    final var decoded = new StringBuilder(body.length());
    for (int i = 0; i < body.length(); ++i) {
      final char c = body.charAt(i);
      if (c < 0x20) {
        throw new AssertionError(String.format("raw control character U+%04X at %d in [%s]", (int) c, i, body));
      } else if (c == '"') {
        throw new AssertionError("unescaped quote at " + i + " ends the string early: [" + body + ']');
      } else if (c != '\\') {
        decoded.append(c);
      } else if (++i == body.length()) {
        throw new AssertionError("trailing backslash: [" + body + ']');
      } else {
        final char e = body.charAt(i);
        switch (e) {
          case '"', '\\', '/' -> decoded.append(e);
          case 'b' -> decoded.append('\b');
          case 'f' -> decoded.append('\f');
          case 'n' -> decoded.append('\n');
          case 'r' -> decoded.append('\r');
          case 't' -> decoded.append('\t');
          case 'u' -> {
            if (i + 4 >= body.length()) {
              throw new AssertionError("truncated \\u escape in [" + body + ']');
            }
            final var hex = body.substring(i + 1, i + 5);
            for (int k = 0; k < 4; ++k) {
              // HEXDIG is ASCII: Character.digit would also take fullwidth and other digits.
              final char h = hex.charAt(k);
              if (!(h >= '0' && h <= '9' || h >= 'a' && h <= 'f' || h >= 'A' && h <= 'F')) {
                throw new AssertionError("invalid escape \\u" + hex + " in [" + body + ']');
              }
            }
            decoded.append((char) Integer.parseInt(hex, 16));
            i += 4;
          }
          default -> throw new AssertionError(String.format("invalid escape \\%c at %d in [%s]", e, i, body));
        }
      }
    }
    return decoded.toString();
  }

  /// The template puts the message inside a JSON string literal, so every character RFC 8259
  /// section 7 requires escaped there must be: the quote, the backslash and every control
  /// character below U+0020, each read back as itself by a conforming receiver. Escaping only
  /// the quote and LF sends a tab, a CR or a bare backslash raw, which a strict receiver
  /// rejects (Slack answers 400, and the page is lost), and a backslash before an escape letter
  /// as valid JSON that decodes to other text, which nothing rejects.
  @Test
  void everyCharacterJsonRequiresEscapedIsEscapedAndReadsBackAsItself() {
    final var messages = List.of(
        "C:\\tmp\\new\\u0041 backslashes before escape letters",
        "C:\\Users\\quux backslashes before letters JSON defines no escape for",
        "trailing backslash \\",
        "backslash before a quote \\\" and two of them \\\\\"",
        "tab\tseparated",
        "line1\r\nline2",
        "back\bspace and form\ffeed",
        "nul\0byte",
        "bell\u0007 escape\u001B unit\u001F separator",
        "several classes at once: \"\\\n\r\t\u0001"
    );
    // Every message is checked, so a failure names each class that is still wrong.
    assertAll(messages.stream().map(msg -> () -> {
      final var forwarded = postAndCapture(new RecordingWebHookClient(ENDPOINT_A), msg);
      assertEquals(msg, decodeJsonStringBody(forwarded), () -> "a conforming receiver must read back [" + msg + ']');
    }));
  }

  /// RFC 8259 leaves DEL, the C1 controls, the slash and every non-ASCII character unescaped, so
  /// a message of nothing else is forwarded as the same instance, like the plain one above.
  @Test
  void charactersJsonAllowsRawAreForwardedUnchanged() {
    final var hook = new RecordingWebHookClient(ENDPOINT_A);
    final var msg = "del\u007F c1\u0085\u009B slash / percent %s non-ASCII \u00e9 \u6f22\u5b57 \ud83d\ude00";
    assertSame(msg, postAndCapture(hook, msg));
  }

}
