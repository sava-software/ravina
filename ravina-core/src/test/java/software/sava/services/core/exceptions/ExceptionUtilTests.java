package software.sava.services.core.exceptions;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

final class ExceptionUtilTests {

  @Test
  void nullThrowableContainsNothing() {
    assertFalse(ExceptionUtil.containsIOException(null));
    assertFalse(ExceptionUtil.containsException(null, IllegalStateException.class));
    assertNull(ExceptionUtil.getException(null, IllegalStateException.class));
  }

  @Test
  void directIOExceptionIsFound() {
    assertTrue(ExceptionUtil.containsIOException(new IOException("boom")));
  }

  /// An `UncheckedIOException` whose cause chain is empty. The JDK type always
  /// wraps a non-null `IOException`, so only a null-cause subclass can show that
  /// the wrapper type itself — and not its cause — is what is being recognized.
  private static final class CauselessUncheckedIOException extends UncheckedIOException {

    CauselessUncheckedIOException() {
      super(new IOException("hidden"));
    }

    @Override
    public synchronized IOException getCause() {
      return null;
    }
  }

  @Test
  void directUncheckedIOExceptionIsFound() {
    assertTrue(ExceptionUtil.containsIOException(new UncheckedIOException(new IOException("boom"))));
  }

  @Test
  void uncheckedIOExceptionCountsIndependentlyOfItsCause() {
    // UncheckedIOException is not itself an IOException; recognition must come
    // from the wrapper type, not from walking into the cause.
    assertNull(new CauselessUncheckedIOException().getCause());
    assertTrue(ExceptionUtil.containsIOException(new CauselessUncheckedIOException()));
    assertTrue(ExceptionUtil.containsIOException(
        new IllegalStateException("outer", new CauselessUncheckedIOException())));
  }

  @Test
  void ioExceptionSubclassIsFound() {
    assertTrue(ExceptionUtil.containsIOException(new SocketTimeoutException("slow")));
  }

  @Test
  void chainWithoutIOExceptionIsNotFound() {
    final var chain = new IllegalStateException("outer", new TimeoutException("inner"));
    assertFalse(ExceptionUtil.containsIOException(chain));
  }

  @Test
  void ioExceptionIsFoundDeepInTheCauseChain() {
    // Depth 3: a loop that stops after the first iteration must miss this.
    final var chain = new IllegalStateException("a",
        new RuntimeException("b",
            new IllegalArgumentException("c", new IOException("d"))));
    assertTrue(ExceptionUtil.containsIOException(chain));
  }

  @Test
  void uncheckedIOExceptionIsFoundDeepInTheCauseChain() {
    final var chain = new IllegalStateException("a",
        new RuntimeException("b", new UncheckedIOException(new IOException("c"))));
    assertTrue(ExceptionUtil.containsIOException(chain));
  }

  @Test
  void selfReferentialChainTerminates() {
    // getCause() of a cause-less throwable is null, not itself: the loop must exit.
    assertFalse(ExceptionUtil.containsIOException(new IllegalStateException("only")));
  }

  @Test
  void containsExceptionMatchesExactType() {
    assertTrue(ExceptionUtil.containsException(new IllegalStateException("x"), IllegalStateException.class));
    assertFalse(ExceptionUtil.containsException(new IllegalStateException("x"), IllegalArgumentException.class));
  }

  @Test
  void containsExceptionMatchesBySubtypeAndInterface() {
    assertTrue(ExceptionUtil.containsException(new SocketTimeoutException("slow"), IOException.class));
    assertTrue(ExceptionUtil.containsException(new IllegalStateException("x"), RuntimeException.class));
    assertFalse(ExceptionUtil.containsException(new IOException("x"), RuntimeException.class));
  }

  @Test
  void containsExceptionWalksTheWholeCauseChain() {
    final var chain = new IllegalStateException("a",
        new RuntimeException("b",
            new IllegalArgumentException("c", new TimeoutException("d"))));
    assertTrue(ExceptionUtil.containsException(chain, TimeoutException.class));
    assertFalse(ExceptionUtil.containsException(chain, IOException.class));
  }

  @Test
  void containsExceptionSearchesTheOutermostThrowableFirst() {
    final var chain = new TimeoutException("outer");
    assertTrue(ExceptionUtil.containsException(chain, TimeoutException.class));
  }

  @Test
  void getExceptionReturnsTheMatchingThrowableItself() {
    final var target = new TimeoutException("target");
    final var chain = new IllegalStateException("a", new RuntimeException("b", target));
    assertSame(target, ExceptionUtil.getException(chain, TimeoutException.class));
  }

  @Test
  void getExceptionReturnsTheOutermostMatch() {
    final var inner = new IllegalStateException("inner");
    final var outer = new IllegalStateException("outer", new RuntimeException("mid", inner));
    final var found = ExceptionUtil.getException(outer, IllegalStateException.class);
    assertSame(outer, found);
    assertNotSame(inner, found);
  }

  @Test
  void getExceptionReturnsTheThrowableAtTheHeadOfTheChain() {
    final var head = new IOException("head");
    assertSame(head, ExceptionUtil.getException(head, IOException.class));
  }

  @Test
  void getExceptionReturnsNullWhenNoLinkMatches() {
    final var chain = new IllegalStateException("a",
        new RuntimeException("b", new IllegalArgumentException("c")));
    assertNull(ExceptionUtil.getException(chain, IOException.class));
  }

  @Test
  void getExceptionFindsAMatchOnlyReachableByWalkingPastTheHead() {
    final var target = new IOException("deep");
    final var chain = new IllegalStateException("a", target);
    assertSame(target, ExceptionUtil.getException(chain, IOException.class));
  }
}
