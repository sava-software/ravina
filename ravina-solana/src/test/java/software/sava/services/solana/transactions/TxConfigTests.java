package software.sava.services.solana.transactions;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// The heap size rule a [TxConfig] enforces, against an oracle written from SIMD-0385's wording
/// rather than from the check: a requested heap size is a whole number of KiB from 32 to 256
/// inclusive, and 0 requests none. Agave refuses anything else as a sanitization failure, and
/// ravina's non-strict builder would write it as given.
final class TxConfigTests {

  private static final int KIB = 1_024;

  /// Every value SIMD-0385 accepts, built by multiplication: 0, and k KiB for k from 32 to 256.
  private static Set<Integer> accepted() {
    final var accepted = new HashSet<Integer>();
    accepted.add(0);
    for (int k = 32; k <= 256; ++k) {
      accepted.add(k * KIB);
    }
    return accepted;
  }

  private static boolean constructs(final int heapSize) {
    try {
      return new TxConfig(heapSize).heapSize() == heapSize;
    } catch (final IllegalArgumentException refused) {
      return false;
    }
  }

  @Test
  void theDefaultRequestsNoHeap() {
    assertEquals(0, TxConfig.DEFAULT.heapSize());
    assertEquals(new TxConfig(0), TxConfig.DEFAULT);
  }

  @Test
  void theBoundsAreSimd0385s() {
    assertEquals(32 * KIB, TxConfig.MIN_HEAP_SIZE);
    assertEquals(256 * KIB, TxConfig.MAX_HEAP_SIZE);
  }

  @Test
  void everyWholeKibibyteFrom32To256IsAccepted() {
    for (int k = 32; k <= 256; ++k) {
      assertEquals(k * KIB, new TxConfig(k * KIB).heapSize(), k + " KiB");
    }
    // A 64KiB request, and a 33KiB one: whole KiB, though not whole 32KiB pages, is all the
    // rule asks.
    assertEquals(64 * KIB, new TxConfig(64 * KIB).heapSize());
    assertEquals(33 * KIB, new TxConfig(33 * KIB).heapSize());
  }

  @Test
  void aHeapSizeIsAcceptedExactlyWhenSimd0385AcceptsIt() {
    final var accepted = accepted();
    // Every value around 0 and both bounds, and around a request in between.
    final int[][] windows = {
        {-3 * KIB, 3 * KIB},
        {31 * KIB, 34 * KIB},
        {63 * KIB, 65 * KIB},
        {255 * KIB, 258 * KIB}
    };
    for (final int[] window : windows) {
      for (int heapSize = window[0]; heapSize <= window[1]; ++heapSize) {
        assertEquals(accepted.contains(heapSize), constructs(heapSize), Integer.toString(heapSize));
      }
    }
    // Negative whole KiB, and the extremes: Integer.MIN_VALUE is a multiple of 1KiB.
    for (final int heapSize : new int[]{
        300 * KIB, 512 * KIB, Integer.MAX_VALUE, -KIB, -32 * KIB, -64 * KIB, -256 * KIB, Integer.MIN_VALUE}) {
      assertFalse(constructs(heapSize), Integer.toString(heapSize));
    }
  }

  @Test
  void aRefusalNamesTheRuleAndTheValue() {
    for (final int heapSize : new int[]{31 * KIB, 33 * KIB + 1, 257 * KIB, 300 * KIB, -KIB, Integer.MIN_VALUE}) {
      final var thrown = assertThrows(IllegalArgumentException.class, () -> new TxConfig(heapSize));
      final var message = thrown.getMessage();
      assertTrue(message.contains("multiple of 1KiB"), message);
      assertTrue(message.contains("from 32KiB to 256KiB inclusive"), message);
      assertTrue(message.endsWith(": " + heapSize), message);
    }
  }

  @Test
  void theWitherReplacesTheHeapSizeUnderTheSameRule() {
    final var config = TxConfig.DEFAULT.withHeapSize(64 * KIB);
    assertEquals(new TxConfig(64 * KIB), config);
    assertEquals(64 * KIB, config.heapSize());
    assertEquals(0, TxConfig.DEFAULT.heapSize(), "the receiver is left as it was");
    assertEquals(TxConfig.DEFAULT, config.withHeapSize(0));
    assertThrows(IllegalArgumentException.class, () -> config.withHeapSize(64 * KIB + 1));
  }
}
