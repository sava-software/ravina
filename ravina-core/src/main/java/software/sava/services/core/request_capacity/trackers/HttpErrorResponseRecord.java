package software.sava.services.core.request_capacity.trackers;

import software.sava.services.core.config.ServiceConfigUtil;

import java.net.http.HttpResponse;

public record HttpErrorResponseRecord(long timestamp, HttpResponse<?> httpResponse) implements ErrorResponseRecord {

  @Override
  public int errorCode() {
    return httpResponse.statusCode();
  }

  /// The generated form would print the JDK response's own `toString`, which names the full
  /// request URI, and a hosted endpoint's key sits in its path or query.
  @Override
  public String toString() {
    return "HttpErrorResponseRecord[timestamp=" + timestamp
        + ", statusCode=" + httpResponse.statusCode()
        + ", uri=" + ServiceConfigUtil.redactEndpoint(httpResponse.uri()) + ']';
  }
}
