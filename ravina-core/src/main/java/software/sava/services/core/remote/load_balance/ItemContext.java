package software.sava.services.core.remote.load_balance;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.CapacityMonitor;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.IntUnaryOperator;
import java.util.function.LongUnaryOperator;

final class ItemContext<T> implements BalancedItem<T> {

  private static final int NUM_SAMPLES = 5;
  private static final int MEDIAN_INDEX = NUM_SAMPLES >> 1;
  private static final IntUnaryOperator ROTATE_INDEX = i -> i + 1 == NUM_SAMPLES ? 0 : i + 1;
  private static final LongUnaryOperator SUCCESS_UPDATE = c -> c > 0 ? --c : 0;

  private final T item;
  private final CapacityMonitor capacityMonitor;
  private final Backoff backoff;
  private final AtomicLong failureCount;
  private final AtomicLongArray samples;
  private final AtomicInteger sampleIndex;
  private final AtomicLong sampleMedian;
  private final long[] localSampleArray;
  private long skipped;

  ItemContext(final T item,
              final CapacityMonitor capacityMonitor,
              final Backoff backoff) {
    this.item = item;
    this.capacityMonitor = capacityMonitor;
    this.backoff = backoff;
    this.failureCount = new AtomicLong();
    this.samples = new AtomicLongArray(NUM_SAMPLES);
    this.sampleIndex = new AtomicInteger(-1);
    for (int i = 0; i < NUM_SAMPLES; ++i) {
      samples.set(i, Long.MIN_VALUE);
    }
    this.sampleMedian = new AtomicLong(Long.MAX_VALUE);
    this.localSampleArray = new long[NUM_SAMPLES];
  }

  @Override
  public Backoff backoff() {
    return backoff;
  }

  @Override
  public void sample(final long sample) {
    if (sample < 0) {
      throw new IllegalArgumentException("Statistic samples must always be positive.");
    }
    final int sampleIndex = this.sampleIndex.updateAndGet(ROTATE_INDEX);
    final long previousSample = this.samples.getAndSet(sampleIndex, sample);
    this.localSampleArray[sampleIndex] = sample;
    if (previousSample < 0) {
      this.sampleMedian.set(sample);
    } else {
      Arrays.sort(this.localSampleArray);
      this.sampleMedian.set(this.localSampleArray[MEDIAN_INDEX]);
    }
  }

  @Override
  public long sampleMedian() {
    return sampleMedian.get();
  }

  @Override
  public T item() {
    return item;
  }

  @Override
  public void failed() {
    failureCount.incrementAndGet();
  }

  @Override
  public void failed(final int weight) {
    failureCount.addAndGet(weight);
  }

  @Override
  public void success() {
    failureCount.getAndUpdate(SUCCESS_UPDATE);
  }

  @Override
  public long errorCount() {
    return failureCount.get();
  }

  @Override
  public void skip() {
    ++skipped;
  }

  @Override
  public long skipped() {
    return skipped;
  }

  @Override
  public void selected() {
    skipped = 0L;
  }

  @Override
  public CapacityMonitor capacityMonitor() {
    return capacityMonitor;
  }
}
