package software.sava.services.solana.transactions;

/// The SIMD-0385 v1 transaction configuration a caller chooses for one call, applied alike to the
/// transaction ravina simulates and to every transaction it builds from that simulation: the
/// fee-bearing transaction, every rebuild of the call, and every batch of a batch call. A program
/// that needs a larger heap is therefore simulated with it, so the simulation measures what is
/// sent, including the 8 compute units the runtime charges at every program invocation for each
/// 32KiB page of heap, or part of one, past the first, and the simulated transaction's size still
/// bounds the one sent.
///
/// It holds what only the caller can decide, the heap size for now. The other v1 ConfigValues
/// are not here because ravina derives them for each attempt: the compute unit limit from the
/// simulated units and `cuBudgetMultiplier`, the loaded accounts data size limit from the
/// simulated load, and the priority fee from the fee estimate and the caller's cap. Build a
/// configuration from [#DEFAULT] with its withers, `TxConfig.DEFAULT.withHeapSize(64 * 1_024)`,
/// which keep compiling as fields join.
///
/// `heapSize` is the requested heap frame in bytes. 0, as in [#DEFAULT], writes no heap
/// ConfigValue, and the runtime then gives the default heap of 32KiB. Any other value is written
/// as the ConfigValue, and must be a multiple of 1KiB from 32KiB to 256KiB inclusive, the bounds
/// `RequestHeapFrame` had: SIMD-0385 makes any other value a sanitization failure, so the
/// transaction never executes, never lands and shows no program error, and a simulation of it is
/// answered with a JSON-RPC invalid-params error instead of a result, which ravina's simulation
/// call would retry as it retries any failed request, marking each RPC peer failed. Ravina builds
/// with sava's `TxBuilder` in non-strict mode, so that a batch over a v1 limit is reported rather
/// than thrown, and in that mode the builder writes a heap size as it is given, so the canonical
/// constructor checks it instead.
///
/// A request of exactly 32KiB gives the default heap, but not the same transaction: it sets the
/// heap bit and adds the 4-byte ConfigValue, which a `beforeSend` hook can then retune with
/// `Transaction.setHeapSize`. A hook that raises the heap size must also raise the compute unit
/// limit, with `setComputeUnitLimit`: the limit sent is derived from a simulation that paid the
/// per-page charge above only at the requested size. A transaction built with 0 has no heap slot,
/// and `setHeapSize` on it throws `IllegalStateException`.
///
/// @param heapSize the requested heap frame in bytes, or 0 for the runtime default
public record TxConfig(int heapSize) {

  /// The smallest heap size a configuration can request, 32KiB, which is also the runtime default.
  public static final int MIN_HEAP_SIZE = 32 * 1_024;
  /// The largest heap size a configuration can request, 256KiB.
  public static final int MAX_HEAP_SIZE = 256 * 1_024;
  /// A requested heap size is a whole number of these.
  private static final int HEAP_SIZE_UNIT = 1_024;

  /// No override: the runtime default heap, with no heap ConfigValue written.
  public static final TxConfig DEFAULT = new TxConfig(0);

  /// @throws IllegalArgumentException if `heapSize` is neither 0 nor a multiple of 1KiB from
  ///                                  32KiB to 256KiB inclusive
  public TxConfig {
    if (heapSize != 0
        && (heapSize < MIN_HEAP_SIZE || heapSize > MAX_HEAP_SIZE || heapSize % HEAP_SIZE_UNIT != 0)) {
      throw new IllegalArgumentException(
          "A requested heap size must be 0, for the runtime default of 32KiB, or a multiple of 1KiB "
              + "from 32KiB to 256KiB inclusive: " + heapSize
      );
    }
  }

  /// This configuration with `heapSize` as its requested heap size.
  ///
  /// @throws IllegalArgumentException as the canonical constructor does
  public TxConfig withHeapSize(final int heapSize) {
    return new TxConfig(heapSize);
  }
}
