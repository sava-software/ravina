package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

final class CapacityStateVal implements CapacityState {

  private static final IntBinaryOperator CLAIM_REQUEST = (numRemaining, weight) -> numRemaining - weight;
  private static final IntBinaryOperator PUT_CLAIM_BACK = Integer::sum;

  private final CapacityConfig capacityConfig;
  private final AtomicInteger capacity;
  private final double weightPerNanosecond;
  private final long nanosPerWeight;
  private final IntBinaryOperator updateCapacity;
  private final AtomicLong updatedAtSystemNanoTime;

  CapacityStateVal(final CapacityConfig capacityConfig) {
    this.capacityConfig = capacityConfig;
    final int maxCapacity = capacityConfig.maxCapacity();
    this.weightPerNanosecond = maxCapacity / (double) capacityConfig.resetDuration().toNanos();
    this.nanosPerWeight = Math.round(1 / weightPerNanosecond);
    this.updateCapacity = (numRemaining, newCapacity) -> Math.min(maxCapacity, Math.max(capacityConfig.minCapacity(), numRemaining + newCapacity));
    this.capacity = new AtomicInteger(maxCapacity);
    this.updatedAtSystemNanoTime = new AtomicLong(System.nanoTime());
  }

  @Override
  public CapacityConfig capacityConfig() {
    return capacityConfig;
  }

  @Override
  public int capacity() {
    return capacity.get();
  }

  @Override
  public void addCapacity(final int delta) {
    capacity.addAndGet(delta);
  }

  private double capacityFor(final long nanos) {
    return nanos * weightPerNanosecond;
  }

  private void reduceCapacityFor(final long nanos) {
    final int capacityFor = -(int) Math.ceil(capacityFor(nanos));
    addCapacity(capacityFor);
  }

  @Override
  public double capacityFor(final Duration duration) {
    return capacityFor(duration.toNanos());
  }

  @Override
  public void reduceCapacityFor(final Duration duration) {
    reduceCapacityFor(duration.toNanos());
  }

  @Override
  public double capacityFor(final long duration, final TimeUnit timeUnit) {
    return capacityFor(timeUnit.toNanos(duration));
  }

  @Override
  public void reduceCapacityFor(final long duration, final TimeUnit timeUnit) {
    reduceCapacityFor(timeUnit.toNanos(duration));
  }

  private int getCallWeight(final CallContext callContext, final int runtimeCallWeight) {
    return callContext == null ? runtimeCallWeight : callContext.callWeight(runtimeCallWeight);
  }

  @Override
  public void claimRequest(final int runtimeCallWeight) {
    if (runtimeCallWeight > 0) {
      this.capacity.accumulateAndGet(runtimeCallWeight, CLAIM_REQUEST);
    }
  }

  @Override
  public void claimRequest(final CallContext callContext, final int runtimeCallWeight) {
    claimRequest(getCallWeight(callContext, runtimeCallWeight));
  }

  private int getMinCapacity(final CallContext callContext) {
    return callContext == null ? CallContext.DEFAULT_CALL_CONTEXT.minCapacity() : callContext.minCapacity();
  }

  private int tryUpdateCapacity() {
    final long nanoTime = System.nanoTime();
    final long updatedAtSystemNanoTime = this.updatedAtSystemNanoTime.get();
    final long nanosSinceUpdated = nanoTime - updatedAtSystemNanoTime;
    if (nanosSinceUpdated < nanosPerWeight) {
      return Integer.MIN_VALUE; // Allow callers to break out instead of double-checking the same value.
    } else {
      return this.updatedAtSystemNanoTime.compareAndSet(updatedAtSystemNanoTime, nanoTime)
          ? this.capacity.accumulateAndGet((int) Math.round(nanosSinceUpdated * weightPerNanosecond), updateCapacity)
          : this.capacity.get();
    }
  }

  @Override
  public boolean tryClaimRequest(final int callWeight, final int minCapacity) {
    final int excessCapacity = capacity.get() - callWeight;
    if (excessCapacity < minCapacity) {
      final int capacity = tryUpdateCapacity();
      if (capacity == Integer.MIN_VALUE || (capacity - callWeight) < minCapacity) {
        return false;
      }
    }
    if (this.capacity.accumulateAndGet(callWeight, CLAIM_REQUEST) < minCapacity) {
      this.capacity.accumulateAndGet(callWeight, PUT_CLAIM_BACK);
      return false;
    } else {
      return true;
    }
  }


  private boolean tryClaimRequest(final int callWeight, final CallContext callContext) {
    final int minCapacity = getMinCapacity(callContext);
    return callWeight > 0
        ? tryClaimRequest(callWeight, minCapacity)
        : hasCapacity(callWeight, minCapacity); // check for rate limited exceeded state (<0).
  }

  @Override
  public boolean tryClaimRequest(final CallContext callContext, final int runtimeCallWeight) {
    final int callWeight = callContext.callWeight(runtimeCallWeight);
    return tryClaimRequest(callWeight, callContext);
  }

  @Override
  public boolean tryClaimRequest(final CallContext callContext) {
    final int callWeight = callContext.callWeight();
    return tryClaimRequest(callWeight, callContext);
  }

  @Override
  public boolean hasCapacity(final int callWeight, final int minCapacity) {
    final int excessCapacity = capacity.get() - callWeight;
    if (excessCapacity < minCapacity) {
      final int capacity = tryUpdateCapacity();
      return capacity != Integer.MIN_VALUE && (capacity - callWeight) >= minCapacity;
    } else {
      return true;
    }
  }

  @Override
  public boolean hasCapacity(final CallContext callContext, final int runtimeCallWeight) {
    final int callWeight = getCallWeight(callContext, runtimeCallWeight);
    final int minCapacity = getMinCapacity(callContext);
    return hasCapacity(callWeight, minCapacity);
  }

  @Override
  public boolean hasCapacity(final CallContext callContext) {
    final int callWeight = callContext.callWeight();
    final int minCapacity = getMinCapacity(callContext);
    return hasCapacity(callWeight, minCapacity);
  }

  @Override
  public long durationUntil(final CallContext callContext, final int runtimeCallWeight, final TimeUnit timeUnit) {
    final int callWeight = getCallWeight(callContext, runtimeCallWeight);
    final int minCapacity = getMinCapacity(callContext);
    int excessCapacity = capacity.get() - callWeight;
    int capacityNeeded = minCapacity - excessCapacity;
    if (capacityNeeded <= 0) {
      return 0;
    } else {
      final int capacity = tryUpdateCapacity();
      if (capacity != Integer.MIN_VALUE) {
        excessCapacity = capacity - callWeight;
        capacityNeeded = minCapacity - excessCapacity;
        if (capacityNeeded <= 0) {
          return 0;
        }
      }
      final long nanosUntil = Math.round(capacityNeeded * nanosPerWeight);
      return timeUnit.convert(nanosUntil, NANOSECONDS);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "CapacityStateVal{capacity=%d, weightPerMicrosecond=%d, nanosPerWeight=%d, updatedAtSystemNanoTime=%d}",
        capacity.get(), (long) (weightPerNanosecond * 1_000_000_000), nanosPerWeight, updatedAtSystemNanoTime.get()
    );
  }
}
