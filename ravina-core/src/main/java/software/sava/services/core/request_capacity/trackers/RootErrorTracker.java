package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public abstract class RootErrorTracker<R> implements ErrorTracker<R> {

  protected static final Function<String, ConcurrentLinkedQueue<ErrorResponseRecord>> INIT_ERROR_RESPONSE_GROUP = _ -> new ConcurrentLinkedQueue<>();

  protected final CapacityState capacityState;
  private final long groupedErrorExpirationMillis;
  private final int serverErrorBackOffCapacity;
  private final int tooManyErrorsBackoffCapacity;
  private final int rateLimitedBackOffCapacity;
  private final AtomicInteger maxGroupedErrorCount;
  private final Map<String, ConcurrentLinkedQueue<ErrorResponseRecord>> errorResponses;
  private final int maxGroupedErrorResponses;

  protected RootErrorTracker(final CapacityState capacityState) {
    this.capacityState = capacityState;
    final var config = capacityState.capacityConfig();
    this.groupedErrorExpirationMillis = config.maxGroupedErrorExpiration().toMillis();
    this.serverErrorBackOffCapacity = -(int) Math.ceil(capacityState.capacityFor(config.serverErrorBackOffDuration()));
    this.tooManyErrorsBackoffCapacity = -(int) Math.ceil(capacityState.capacityFor(config.tooManyErrorsBackoffDuration()));
    this.rateLimitedBackOffCapacity = -(int) Math.ceil(capacityState.capacityFor(config.rateLimitedBackOffDuration()));
    this.maxGroupedErrorCount = new AtomicInteger(0);
    this.errorResponses = new ConcurrentHashMap<>();
    this.maxGroupedErrorResponses = config.maxGroupedErrorResponses();
  }

  protected abstract boolean isServerError(final R response);

  protected abstract boolean isRequestError(final R response);

  protected abstract boolean isRateLimited(final R response);

  protected boolean unableToHandleResponse(final R response) {
    return true;
  }

  protected abstract boolean updateGroupedErrorResponseCount(final long now, final R response);

  protected final boolean updateGroupedErrorResponseCount(final R response) {
    return updateGroupedErrorResponseCount(System.currentTimeMillis(), response);
  }

  protected abstract void logResponse(final R response);

  public boolean test(final R response) {
    if (isServerError(response)) {
      capacityState.addCapacity(serverErrorBackOffCapacity);
      logResponse(response);
    } else if (isRequestError(response)) {
      if (unableToHandleResponse(response)) {
        if (isRateLimited(response)) {
          capacityState.addCapacity(rateLimitedBackOffCapacity);
        } else if (updateGroupedErrorResponseCount(response)) {
          capacityState.addCapacity(tooManyErrorsBackoffCapacity);
        }
      }
      logResponse(response);
    }
    return true;
  }

  @Override
  public final int maxGroupedErrorCount() {
    return maxGroupedErrorCount.get();
  }

  @Override
  public final boolean hasExceededMaxAllowedGroupedErrorResponses() {
    return maxGroupedErrorCount.get() > maxGroupedErrorResponses;
  }

  protected final int expireOldFailures(final long expireBefore) {
    int maxCount = 0, size;
    ErrorResponseRecord response;
    for (final var errorResponses : this.errorResponses.values()) {
      response = errorResponses.peek();
      if (response != null) {
        do {
          errorResponses.poll();
        } while ((response = errorResponses.peek()) != null && response.timestamp() <= expireBefore);
        size = errorResponses.size();
        if (size > maxCount) {
          maxCount = size;
        }
      }
    }
    return maxCount;
  }

  protected final boolean updateGroupedErrorResponseCount(final long now,
                                                          final String groupKey,
                                                          final ErrorResponseRecord errorResponseRecord) {
    final var errorResponses = this.errorResponses
        .computeIfAbsent(groupKey, INIT_ERROR_RESPONSE_GROUP);
    errorResponses.add(errorResponseRecord);
    final int maxCount = expireOldFailures(now - groupedErrorExpirationMillis);
    maxGroupedErrorCount.set(maxCount);
    return Math.max(maxCount, errorResponses.size()) >= maxGroupedErrorResponses;
  }

  @Override
  public final Map<String, List<ErrorResponseRecord>> produceErrorResponseSnapshot() {
    final int numGroups = errorResponses.size();
    if (numGroups == 0) {
      return Map.of();
    }

    final var snapshot = HashMap.<String, List<ErrorResponseRecord>>newHashMap(numGroups);
    final long expireBefore = System.currentTimeMillis() - groupedErrorExpirationMillis;
    for (final var errorResponseEntry : errorResponses.entrySet()) {
      final var errorResponses = errorResponseEntry.getValue();
      var response = errorResponses.peek();
      if (response != null) {
        do {
          errorResponses.poll();
        } while ((response = errorResponses.peek()) != null && response.timestamp() <= expireBefore);
        final var copy = List.copyOf(errorResponses);
        if (!copy.isEmpty()) {
          snapshot.put(errorResponseEntry.getKey(), copy);
        }
      }
    }
    return snapshot.isEmpty() ? Map.of() : snapshot;
  }
}
