package software.sava.services.core.request_capacity.trackers;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// TODO: support separation for response wrapper and data type
public interface ErrorTracker<R> extends Predicate<R> {

  int maxGroupedErrorCount();

  boolean hasExceededMaxAllowedGroupedErrorResponses();

  Map<String, List<ErrorResponseRecord>> produceErrorResponseSnapshot();
}
