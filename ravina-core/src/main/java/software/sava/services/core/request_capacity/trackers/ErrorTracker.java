package software.sava.services.core.request_capacity.trackers;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/// Docks capacity for an errored response. `R` is the response wrapper and `D`
/// the payload the wrapper does not carry — for HTTP that is the body, read
/// separately because the client no longer materialises it on the response;
/// for a tracker keyed on a `Throwable` there is no payload at all and `D` is
/// [Void].
public interface ErrorTracker<R, D> extends BiPredicate<R, D> {

  int maxGroupedErrorCount();

  boolean hasExceededMaxAllowedGroupedErrorResponses();

  Map<String, List<ErrorResponseRecord>> produceErrorResponseSnapshot();
}
