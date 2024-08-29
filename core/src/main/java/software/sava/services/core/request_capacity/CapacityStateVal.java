package software.sava.services.core.request_capacity;

import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;

final class CapacityStateVal implements CapacityState {

  private static final IntBinaryOperator CLAIM_REQUEST = (numRemaining, weight) -> numRemaining - weight;
  private static final IntBinaryOperator PUT_CLAIM_BACK = Integer::sum;

  private final CapacityConfig capacityConfig;
  private final AtomicInteger capacity;
  private final double weightPerMillisecond;
  private final long millisPerWeight;
  private final IntBinaryOperator updateCapacity;
  private final AtomicLong updatedAt;

  CapacityStateVal(final CapacityConfig capacityConfig) {
    this.capacityConfig = capacityConfig;
    final int maxCapacity = capacityConfig.maxCapacity();
    this.weightPerMillisecond = maxCapacity / (double) capacityConfig.resetDuration().toMillis();
    this.millisPerWeight = Math.round(1 / weightPerMillisecond);
    this.updateCapacity = (numRemaining, newCapacity) -> Math.min(maxCapacity, Math.max(capacityConfig.minCapacity(), numRemaining + newCapacity));
    this.capacity = new AtomicInteger(maxCapacity);
    this.updatedAt = new AtomicLong(System.currentTimeMillis());
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
  public double capacityFor(final Duration duration) {
    return duration.toMillis() * weightPerMillisecond;
  }

  @Override
  public void subtractCapacityFor(final Duration duration) {
    final double capacityFor = capacityFor(duration);
    capacity.getAndAdd(-((int) Math.ceil(capacityFor)));
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
    final long now = System.currentTimeMillis();
    final long updatedAt = this.updatedAt.get();
    final long millisSinceUpdated = now - updatedAt;
    if (millisSinceUpdated < millisPerWeight) {
      return Integer.MIN_VALUE; // Allow callers to break out instead of double-checking the same value.
    } else {
      return this.updatedAt.compareAndSet(updatedAt, now)
          ? this.capacity.accumulateAndGet((int) Math.round(millisSinceUpdated * weightPerMillisecond), updateCapacity)
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

  @Override
  public boolean tryClaimRequest(final CallContext callContext, final int runtimeCallWeight) {
    final int callWeight = getCallWeight(callContext, runtimeCallWeight);
    return callWeight > 0
        ? tryClaimRequest(callWeight, getMinCapacity(callContext))
        : hasCapacity(callContext, callWeight); // check for rate limited exceeded state (<0).
  }

  @Override
  public boolean tryClaimRequest(final CallContext callContext) {
    final int callWeight = callContext.callWeight();
    final int minCapacity = getMinCapacity(callContext);
    return callWeight > 0
        ? tryClaimRequest(callWeight, minCapacity)
        : hasCapacity(callWeight, minCapacity); // check for rate limited exceeded state (<0).
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
      final long millisUntil = Math.round(capacityNeeded * weightPerMillisecond);
      return timeUnit.convert(millisUntil, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public String toString() {
    return String.format(
        "CapacityStateVal{capacity=%d, weightPerMillisecond=%.4f, millisPerWeight=%d, updatedAt=%d}",
        capacity.get(), weightPerMillisecond, millisPerWeight, updatedAt.get()
    );
  }
}
