package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.request_capacity.CapacityState;

import java.net.http.HttpResponse;
import java.util.stream.Collectors;

import static java.lang.System.Logger.Level.DEBUG;

public class HttpErrorTracker extends RootErrorTracker<HttpResponse<byte[]>> {

  private static final System.Logger logger = System.getLogger(HttpErrorTracker.class.getName());

  protected HttpErrorTracker(final CapacityState capacityState) {
    super(capacityState);
  }

  @Override
  protected boolean isServerError(final HttpResponse<byte[]> response) {
    return response.statusCode() > 500;
  }

  @Override
  protected boolean isRequestError(final HttpResponse<byte[]> response) {
    return response.statusCode() >= 400 && response.statusCode() < 500;
  }

  @Override
  protected boolean isRateLimited(final HttpResponse<byte[]> response) {
    final int statusCode = response.statusCode();
    return statusCode == 429 || statusCode == 403;
  }

  @Override
  protected final boolean updateGroupedErrorResponseCount(final long now, final HttpResponse<byte[]> response) {
    return updateGroupedErrorResponseCount(
        now,
        response.request().uri().getPath(),
        new HttpErrorResponseRecord(now, response)
    );
  }

  @Override
  protected void logResponse(final HttpResponse<byte[]> response) {
    if (logger.isLoggable(DEBUG)) {
      final var body = response.body();
      if (body != null) {
        logger.log(DEBUG, String.format("""
                %d %s:
                  * Headers:
                    - %s
                  * Body:
                    - %s
                """,
            response.statusCode(),
            response.uri(),
            response.headers().map().entrySet().stream()
                .map(e -> String.format("%s: %s", e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n    - ")),
            new String(body)
        ));
      }
    }
  }
}
