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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

final class NotifyClientTests {

  private static final URI ENDPOINT_A = URI.create("https://a.example.com/hook");
  private static final URI ENDPOINT_B = URI.create("https://b.example.com/hook");

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
}
