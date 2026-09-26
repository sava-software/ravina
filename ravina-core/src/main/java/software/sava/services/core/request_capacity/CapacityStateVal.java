package software.sava.services.core.request_capacity;

import software.sava.services.core.NanoClock;
import software.sava.services.core.request_capacity.context.CallContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

// Non-final so tests can override the interleaving seams below.
class CapacityStateVal implements CapacityState {

  private static final IntBinaryOperator CLAIM_REQUEST = (numRemaining, weight) -> numRemaining - weight;
  private static final IntBinaryOperator PUT_CLAIM_BACK = Integer::sum;

  private final NanoClock clock;
  private final CapacityConfig capacityConfig;
  private final AtomicInteger capacity;
  private final double weightPerNanosecond;
  private final long nanosPerWeight;
  /// The configured floor: a refill raises any deeper overdraft to it and
  /// credits nothing beyond it, see [#updateCapacity].
  private final int floor;
  private final IntBinaryOperator updateCapacity;
  private final AtomicLong updatedAtSystemNanoTime;

  @Override
  public NanoClock clock() {
    return clock;
  }

  CapacityStateVal(final CapacityConfig capacityConfig, final NanoClock clock) {
    this.clock = clock;
    this.capacityConfig = capacityConfig;
    final int maxCapacity = capacityConfig.maxCapacity();
    this.weightPerNanosecond = maxCapacity / (double) capacityConfig.resetDuration().toNanos();
    this.nanosPerWeight = Math.round(1 / weightPerNanosecond);
    this.floor = capacityConfig.minCapacity();
    this.updateCapacity = (numRemaining, newCapacity) ->
        Math.clamp(numRemaining + newCapacity, capacityConfig.minCapacity(), maxCapacity);
    this.capacity = new AtomicInteger(maxCapacity);
    this.updatedAtSystemNanoTime = new AtomicLong(clock.nanoTime());
  }

  // Interleaving seams: tests override these to wedge a competing update
  // between a read and its compare-and-set, reproducing racy interleavings
  // deterministically on the test thread.
  int claimCapacity(final int callWeight) {
    return capacity.accumulateAndGet(callWeight, CLAIM_REQUEST);
  }

  boolean casUpdatedAt(final long expected, final long newValue) {
    return updatedAtSystemNanoTime.compareAndSet(expected, newValue);
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
      claimCapacity(runtimeCallWeight);
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
    final long nanoTime = clock.nanoTime();
    final long updatedAtSystemNanoTime = this.updatedAtSystemNanoTime.get();
    final long nanosSinceUpdated = nanoTime - updatedAtSystemNanoTime;
    if (nanosSinceUpdated < nanosPerWeight) {
      return Integer.MIN_VALUE; // Allow callers to break out instead of double-checking the same value.
    } else {
      return casUpdatedAt(updatedAtSystemNanoTime, nanoTime)
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
    if (claimCapacity(callWeight) < minCapacity) {
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
    int current = capacity.get();
    int excessCapacity = current - callWeight;
    int capacityNeeded = minCapacity - excessCapacity;
    if (capacityNeeded <= 0) {
      return 0;
    } else {
      final int updated = tryUpdateCapacity();
      if (updated != Integer.MIN_VALUE) {
        current = updated;
        excessCapacity = current - callWeight;
        capacityNeeded = minCapacity - excessCapacity;
        if (capacityNeeded <= 0) {
          return 0;
        }
      }
      long nanosOwed;
      if (current < floor) {
        // Deeper than the floor: the next update raises the reading to the
        // floor and credits nothing beyond it, so the wait until anything
        // changes is one refill period, and the call after that update
        // answers the rest, floor to target, exactly. Charging the whole
        // debt here would sleep out a dock the floor is about to forgive;
        // charging the rest here too would double it, because that first
        // update discards the elapsed credit beyond the floor.
        nanosOwed = nanosPerWeight;
      } else {
        // An exact long product; it must not pass through Math.round, whose
        // float overload an int * long resolves to (precision lost above
        // 2^24 ns and an int result that capped every wait at 2,147 ms). A
        // deep floor at a slow refill can overflow the product: saturate
        // rather than wrap to a negative wait, which a courteous caller
        // would read as "call now".
        try {
          nanosOwed = Math.multiplyExact((long) capacityNeeded, nanosPerWeight);
        } catch (final ArithmeticException overflow) {
          nanosOwed = Long.MAX_VALUE;
        }
      }
      // The next weight lands one refill period after the last update, not
      // one after this question: the time already accrued toward it (under a
      // period when the update above was declined, none when it ran) comes
      // off the wait, or a courteous caller sleeps past the refill by up to a
      // whole period.
      final long accrued = clock.nanoTime() - updatedAtSystemNanoTime.get();
      final long nanosUntil = Math.max(0, nanosOwed - accrued);
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
