package software.sava.services.solana.websocket;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.ws.SolanaRpcWebsocket;
import software.sava.services.core.NanoClock;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.solana.LogSilencer;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.*;

/// Subscribe batching, which sava-rpc 25.13.4 added to its Builder, through the manager. The
/// batch cap and the single-subscribe budget are the caller's settings: the manager writes
/// neither, so they must reach every websocket it creates, the first and each replacement.
///
/// The wire tests show that where it counts, with sava-rpc's real engine behind the manager and
/// a scripted JDK transport under the engine: what the transport is asked to send is what a
/// server would receive. Every step whose timing a test depends on happens on the test thread.
/// The transport opens a connection from inside `buildAsync`, as the JDK does before it completes
/// the handshake's future, the engine's adoption pass sends from inside that call, and an answer
/// fed to the listener is handled before the call returns. The engine's own loop is a real
/// thread, so its ping, check and resend windows are set far beyond any test, and every test
/// closes its manager, which stops that thread. The one thing that loop sends here is a refused
/// batch's requests again, which the engine hands to it. No test waits for that or depends on
/// when it happens: the test of a refusal asserts what holds whether the loop has run or not.
///
/// The engine paces an ordinary replay on its own clock: a subscription sent on a connection
/// that was then lost keeps its last attempt's stamp and is re-sent once the resend window has
/// passed, and the public Builder gives a test no way to drive that clock. So no test here
/// asserts such a replay. The reconnect tests register new subscriptions while the manager backs
/// off instead, which are due the moment the next connection opens. The test of a refusal also
/// meets the one replay that needs no clock, since the engine resets the stamps of a refused
/// batch's requests.
final class WebSocketManagerBatchingTests {

  private static final URI WS_URI = URI.create("wss://ws.example.com");
  /// Agave's inbound message limit, the largest cap the Builder takes.
  private static final int BATCH_BYTES = 4_096;
  /// Ten minutes: no ping, check or resend falls due inside a test.
  private static final long FAR_MILLIS = 600_000L;
  private static final long RETRY_MILLIS = 25L;
  /// sava-rpc's engine logs under its own class, which this module cannot name.
  private static final String ENGINE_LOGGER = "software.sava.rpc.json.http.ws.SolanaJsonRpcWebsocket";

  private static final PublicKey KEY_A = PublicKey.fromBase58Encoded("7ubS3GccjhQY99AYNKXjNJqnXjaokEdfdV915xnCb96r");
  private static final PublicKey KEY_B = PublicKey.fromBase58Encoded("So11111111111111111111111111111111111111112");
  private static final PublicKey KEY_C = PublicKey.fromBase58Encoded("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA");
  private static final PublicKey KEY_D = PublicKey.fromBase58Encoded("H4vnBqifaSACnKa7acsxstsY1iV1bvJNxsCY7enrd1hq");
  private static final PublicKey KEY_E = PublicKey.fromBase58Encoded("83astBRguLMdt2h5U1Tpdq5tjFoJ6noeGwaY3mDLVcri");
  private static final PublicKey KEY_F = PublicKey.fromBase58Encoded("GLAMbTqav9N9witRjswJ8enwp9vv5G8bsSJ2kPJ4rcyc");
  private static final PublicKey KEY_G = PublicKey.fromBase58Encoded("11111111111111111111111111111111");

  private static final String SUBSCRIBE = "\"method\":\"accountSubscribe\"";
  private static final Pattern REQUEST_ID = Pattern.compile("\"id\":(\\d+)");
  /// What a server that cannot read a JSON-RPC array answers one with: a parse error naming no
  /// request.
  private static final String ARRAY_REFUSED = """
      {"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"},"id":null}""";

  /// The registrations a consumer makes on each websocket the manager creates.
  private static final Consumer<SolanaRpcWebsocket> THREE_ACCOUNTS = ws -> subscribe(ws, KEY_A, KEY_B, KEY_C);

  /// Advances only when told to, from a non-zero origin.
  private static final class TestClock implements NanoClock {

    private long nanos = 7_000_000_000L;

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(final long millis) {
      advanceMillis(millis);
    }

    private void advanceMillis(final long millis) {
      nanos += millis * 1_000_000L;
    }
  }

  /// Records what the engine writes. Thread-safe, because the engine's loop thread sends too:
  /// with the windows these tests set, only a refused batch's requests.
  private static final class ScriptedSocket implements WebSocket {

    private final List<String> sentText = new CopyOnWriteArrayList<>();
    private volatile boolean outputClosed;

    @Override
    public CompletableFuture<WebSocket> sendText(final CharSequence data, final boolean last) {
      sentText.add(data.toString());
      return CompletableFuture.completedFuture(this);
    }

    private List<String> sent() {
      return List.copyOf(sentText);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(final ByteBuffer data, final boolean last) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(final ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(final ByteBuffer message) {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(final int statusCode, final String reason) {
      outputClosed = true;
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(final long n) {
    }

    @Override
    public String getSubprotocol() {
      return "";
    }

    @Override
    public boolean isOutputClosed() {
      return outputClosed;
    }

    @Override
    public boolean isInputClosed() {
      return false;
    }

    @Override
    public void abort() {
      outputClosed = true;
    }
  }

  /// One opened connection: the socket the engine writes to and the listener it reads through.
  private record Connection(ScriptedSocket socket, WebSocket.Listener listener) {

    private List<String> sent() {
      return socket.sent();
    }

    /// Delivers one whole message from the server.
    private void receive(final String json) {
      listener.onText(socket, json, true);
    }

    /// Grants every subscribe request in `frames` a subscription id, in one message, as a server
    /// answers a batch.
    private void grant(final List<String> frames) {
      final var grants = new ArrayList<String>();
      for (final var frame : frames) {
        final var ids = REQUEST_ID.matcher(frame);
        while (ids.find()) {
          grants.add("{\"jsonrpc\":\"2.0\",\"result\":" + (9_000 + grants.size()) + ",\"id\":" + ids.group(1) + '}');
        }
      }
      receive('[' + String.join(",", grants) + ']');
    }

    /// The peer closes the connection.
    private void lose() {
      listener.onClose(socket, 1006, "gone");
    }
  }

  /// The JDK builder the engine connects through. A handshake either fails, while
  /// `refusedHandshakes` lasts, or opens at once: `onOpen` is delivered from inside `buildAsync`.
  private static final class ScriptedTransport implements WebSocket.Builder {

    private final List<Connection> opened = new CopyOnWriteArrayList<>();
    private int refusedHandshakes;
    private int handshakes;

    @Override
    public WebSocket.Builder header(final String name, final String value) {
      return this;
    }

    @Override
    public WebSocket.Builder connectTimeout(final Duration timeout) {
      return this;
    }

    @Override
    public WebSocket.Builder subprotocols(final String mostPreferred, final String... lesserPreferred) {
      return this;
    }

    @Override
    public CompletableFuture<WebSocket> buildAsync(final URI uri, final WebSocket.Listener listener) {
      ++handshakes;
      if (refusedHandshakes > 0) {
        --refusedHandshakes;
        return CompletableFuture.failedFuture(new IOException("handshake refused"));
      }
      final var socket = new ScriptedSocket();
      opened.add(new Connection(socket, listener));
      listener.onOpen(socket);
      return CompletableFuture.completedFuture(socket);
    }

    /// The `n`th connection opened, from 1, which must also be the latest.
    private Connection connection(final int n) {
      assertEquals(n, opened.size(), "connections opened");
      return opened.get(n - 1);
    }
  }

  private static SolanaRpcWebsocket.Builder prototype(final ScriptedTransport transport) {
    return SolanaRpcWebsocket.build()
        .uri(WS_URI)
        .webSocketBuilder(transport)
        .commitment(Commitment.FINALIZED)
        .pingDelay(FAR_MILLIS)
        .subscriptionAndPingCheckDelay(FAR_MILLIS)
        .subscriptionResendDelay(FAR_MILLIS);
  }

  /// A polling manager on `clock`: nothing reconnects until the test advances it and polls.
  private static WebSocketManagerImpl manager(final SolanaRpcWebsocket.Builder prototype,
                                              final Consumer<SolanaRpcWebsocket> onNewWebSocket,
                                              final TestClock clock) {
    return new WebSocketManagerImpl(
        Backoff.single(MILLISECONDS, RETRY_MILLIS),
        prototype,
        onNewWebSocket,
        clock,
        WebSocketManagerImpl.POLLING_RETRY_SCHEDULER
    );
  }

  private static void subscribe(final SolanaRpcWebsocket webSocket, final PublicKey... accounts) {
    for (final var account : accounts) {
      assertTrue(webSocket.accountSubscribe(account, _ -> {
      }), "a first subscription to an account is accepted");
    }
  }

  private static int count(final String text, final String part) {
    int count = 0;
    for (int from = text.indexOf(part); from >= 0; from = text.indexOf(part, from + part.length())) {
      ++count;
    }
    return count;
  }

  /// `frame` is one JSON-RPC array holding a subscribe for each of `accounts`, in that order, and
  /// nothing else.
  private static void assertBatchOf(final String frame, final PublicKey... accounts) {
    assertTrue(frame.startsWith("[{") && frame.endsWith("}]"), "a JSON-RPC array: " + frame);
    assertEquals(accounts.length, count(frame, SUBSCRIBE), "requests in the array: " + frame);
    int from = 0;
    for (final var account : accounts) {
      final int at = frame.indexOf(account.toBase58(), from);
      assertTrue(at >= 0, account.toBase58() + " in order in: " + frame);
      from = at + 1;
    }
  }

  /// `frames` are plain subscribe messages, one for each of `accounts`, in that order.
  private static void assertOnePerMessage(final List<String> frames, final PublicKey... accounts) {
    assertEquals(accounts.length, frames.size(), "one message per request: " + frames);
    for (int i = 0; i < accounts.length; ++i) {
      final var frame = frames.get(i);
      assertTrue(frame.startsWith("{") && frame.endsWith("}"), "a plain request, not an array: " + frame);
      assertEquals(1, count(frame, SUBSCRIBE), "one request: " + frame);
      assertTrue(frame.contains(accounts[i].toBase58()), accounts[i].toBase58() + " in: " + frame);
    }
  }

  /// The manager normalizes the prototype it is given, the reconnect throttle and the resend
  /// delay, and installs its lifecycle handlers. The batch settings are not part of that: the
  /// builder it creates from still carries the caller's.
  @Test
  void theManagerKeepsTheCallersBatchSettingsOnItsPrototype() {
    final var prototype = SolanaRpcWebsocket.build()
        .uri(WS_URI)
        .subscribeBatchBytes(2_048)
        .singleSubscribeBudget(100);

    try (final var manager = manager(prototype, null, new TestClock())) {
      assertEquals(0L, manager.builderPrototype.reConnectDelay(), "the prototype was normalized");
      assertEquals(2_048, manager.builderPrototype.subscribeBatchBytes());
      assertEquals(100, manager.builderPrototype.singleSubscribeBudget());
    }
  }

  /// The registrations an `onNewWebSocket` consumer makes wait for the wrapper's first open. With
  /// a batch cap on the caller's builder they reach the server as one message.
  @Test
  void registrationsMadeAtCreationShareOneBatchOnTheFirstOpen() {
    final var transport = new ScriptedTransport();
    final var prototype = prototype(transport).subscribeBatchBytes(BATCH_BYTES);

    try (final var manager = manager(prototype, THREE_ACCOUNTS, new TestClock())) {
      assertNotNull(manager.webSocket());

      final var sent = transport.connection(1).sent();
      assertEquals(1, sent.size(), "one message for the three registrations: " + sent);
      assertBatchOf(sent.getFirst(), KEY_A, KEY_B, KEY_C);
    }
  }

  /// The control: the same manager, consumer and transport with no batch cap, which is how the
  /// Builder starts and how the URI factories leave it, send each registration alone.
  @Test
  void withoutABatchCapEachRegistrationGoesAlone() {
    final var transport = new ScriptedTransport();

    try (final var manager = manager(prototype(transport), THREE_ACCOUNTS, new TestClock())) {
      assertNotNull(manager.webSocket());

      assertOnePerMessage(transport.connection(1).sent(), KEY_A, KEY_B, KEY_C);
    }
  }

  /// A connection attempt that fails leaves the wrapper and its registrations in place. The
  /// manager's retry reconnects that wrapper, and the registrations, still unsent, share one
  /// batch on the connection that opens.
  @Test
  void aRetryOfTheSameWrapperSendsTheWaitingRegistrationsAsOneBatch() {
    final var transport = new ScriptedTransport();
    transport.refusedHandshakes = 1;
    final var clock = new TestClock();

    try (final var manager = manager(prototype(transport).subscribeBatchBytes(BATCH_BYTES), THREE_ACCOUNTS, clock)) {
      final SolanaRpcWebsocket webSocket;
      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        webSocket = manager.webSocket();
      }
      assertNotNull(webSocket, "a failed attempt keeps its wrapper");
      assertEquals(1, transport.handshakes);
      assertEquals(0, transport.opened.size(), "the first handshake was refused");

      clock.advanceMillis(RETRY_MILLIS);
      assertSame(webSocket, manager.webSocket(), "the retry reconnects the same wrapper");

      assertEquals(2, transport.handshakes);
      final var sent = transport.connection(1).sent();
      assertEquals(1, sent.size(), "one message for the three registrations: " + sent);
      assertBatchOf(sent.getFirst(), KEY_A, KEY_B, KEY_C);
    }
  }

  /// A lost connection is reconnected on the same wrapper, which goes on batching: two
  /// subscriptions registered while the manager backs off share one message when the next
  /// connection opens. The first connection's batch is answered before it is lost, because one
  /// left unanswered changes the next connection, which is the next test's subject. The three
  /// granted on the lost connection are not in the message, since their replay waits out the
  /// resend window, which no test here outlasts.
  @Test
  void registrationsMadeWhileBackingOffShareABatchOnTheReconnect() {
    final var transport = new ScriptedTransport();
    final var clock = new TestClock();

    try (final var manager = manager(prototype(transport).subscribeBatchBytes(BATCH_BYTES), THREE_ACCOUNTS, clock)) {
      final var webSocket = manager.webSocket();
      assertNotNull(webSocket);
      final var first = transport.connection(1);
      first.grant(first.sent());

      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        first.lose();
      }
      assertSame(webSocket, manager.webSocket(), "a lost connection keeps its wrapper while the manager backs off");
      assertEquals(1, transport.handshakes, "nothing reconnects before the backoff has passed");
      subscribe(webSocket, KEY_D, KEY_E);

      clock.advanceMillis(RETRY_MILLIS);
      assertSame(webSocket, manager.webSocket(), "the retry reconnects the same wrapper");

      final var sent = transport.connection(2).sent();
      assertEquals(1, sent.size(), "one message for the two new registrations: " + sent);
      assertBatchOf(sent.getFirst(), KEY_D, KEY_E);
    }
  }

  /// A connection lost while the batch it sent is still unanswered may have been lost to that
  /// batch, so sava-rpc sends the next connection's requests one per message. Under the manager
  /// the next connection is the reconnect of the same wrapper, and the registrations made while
  /// it backed off go out that way too. It lasts one connection: that one, lost in turn with no
  /// batch outstanding, leaves the one after it batching again.
  @Test
  void aConnectionLostWithItsBatchUnansweredMakesTheReconnectSendOnePerMessageOnce() {
    final var transport = new ScriptedTransport();
    final var clock = new TestClock();

    try (final var manager = manager(prototype(transport).subscribeBatchBytes(BATCH_BYTES), THREE_ACCOUNTS, clock)) {
      final var webSocket = manager.webSocket();
      assertNotNull(webSocket);
      final var first = transport.connection(1);
      assertBatchOf(first.sent().getFirst(), KEY_A, KEY_B, KEY_C);

      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        first.lose();
      }
      subscribe(webSocket, KEY_D, KEY_E);
      clock.advanceMillis(RETRY_MILLIS);
      try (var ignored = LogSilencer.silenced(ENGINE_LOGGER)) {
        assertSame(webSocket, manager.webSocket(), "the retry reconnects the same wrapper");
      }
      final var second = transport.connection(2);
      assertOnePerMessage(second.sent(), KEY_D, KEY_E);

      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        second.lose();
      }
      subscribe(webSocket, KEY_F, KEY_G);
      clock.advanceMillis(RETRY_MILLIS);
      assertSame(webSocket, manager.webSocket(), "the retry reconnects the same wrapper");
      final var sent = transport.connection(3).sent();
      assertEquals(1, sent.size(), "one message for the two new registrations: " + sent);
      assertBatchOf(sent.getFirst(), KEY_F, KEY_G);
    }
  }

  /// What sava-rpc learns about an endpoint's batches it keeps on the websocket instance, so
  /// under the manager it lasts exactly as long as the wrapper does. An endpoint that answers
  /// an array with a parse error naming no request cannot read one: sava-rpc sends that batch's
  /// requests again one per message and stops batching on that instance. The manager's
  /// reconnects reuse the instance, so every later connection sends one request per message,
  /// the second as well as the first, which is what tells a refusal from the one-connection
  /// suspension of the test above. Only a replacement wrapper, built from the same prototype,
  /// starts batching again.
  ///
  /// sava-rpc re-sends the refused requests from its loop thread, and this test does not wait
  /// for that. Either the loop has sent them before the connection is lost, or it has not, and
  /// they are due when the reconnect opens, ahead of the two registrations made meanwhile, whose
  /// requests are newer. A pass sends under the lock that retiring a connection takes, so it is
  /// one or the other. Once the reconnect has opened, the two connections together have carried
  /// each refused request once, alone, and then the two new ones, in both orders: that is what
  /// is asserted, and without a refusal the three would not have been sent again at all.
  @Test
  void anEndpointsRefusalOfBatchesHoldsAcrossReconnectsAndEndsWithTheWrapper() {
    final var transport = new ScriptedTransport();
    final var clock = new TestClock();

    try (final var manager = manager(prototype(transport).subscribeBatchBytes(BATCH_BYTES), THREE_ACCOUNTS, clock)) {
      final var webSocket = manager.webSocket();
      assertNotNull(webSocket);
      final var first = transport.connection(1);
      assertBatchOf(first.sent().getFirst(), KEY_A, KEY_B, KEY_C);

      try (var ignored = LogSilencer.silenced(ENGINE_LOGGER)) {
        first.receive(ARRAY_REFUSED);
      }
      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        first.lose();
      }
      subscribe(webSocket, KEY_D, KEY_E);
      clock.advanceMillis(RETRY_MILLIS);
      assertSame(webSocket, manager.webSocket(), "the retry reconnects the same wrapper");

      final var second = transport.connection(2);
      final var onFirst = first.sent();
      final var sinceTheRefusal = new ArrayList<>(onFirst.subList(1, onFirst.size()));
      sinceTheRefusal.addAll(second.sent());
      assertOnePerMessage(sinceTheRefusal, KEY_A, KEY_B, KEY_C, KEY_D, KEY_E);

      try (var ignored = LogSilencer.silenced(WebSocketManagerImpl.class)) {
        second.lose();
      }
      subscribe(webSocket, KEY_F, KEY_G);
      clock.advanceMillis(RETRY_MILLIS);
      assertSame(webSocket, manager.webSocket(), "the second retry reconnects the same wrapper");
      assertOnePerMessage(transport.connection(3).sent(), KEY_F, KEY_G);

      webSocket.close();
      final var replacement = manager.webSocket();
      assertNotNull(replacement, "a wrapper closed directly is replaced on the next call");
      assertNotSame(webSocket, replacement);
      final var sent = transport.connection(4).sent();
      assertEquals(1, sent.size(), "the replacement batches the consumer's registrations: " + sent);
      assertBatchOf(sent.getFirst(), KEY_A, KEY_B, KEY_C);
    }
  }
}
