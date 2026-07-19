package software.sava.services.core.request_capacity.trackers;

import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

// TODO: support separation for response wrapper and data type
public interface ErrorTracker<R> extends BiPredicate<R, byte[]> {

  int maxGroupedErrorCount();

  boolean hasExceededMaxAllowedGroupedErrorResponses();

  Map<String, List<ErrorResponseRecord>> produceErrorResponseSnapshot();
}
