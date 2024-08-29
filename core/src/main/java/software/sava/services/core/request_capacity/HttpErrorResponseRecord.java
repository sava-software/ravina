package software.sava.services.core.request_capacity;

import java.net.http.HttpResponse;

public record HttpErrorResponseRecord(long timestamp, HttpResponse<?> httpResponse) implements ErrorResponseRecord {

  @Override
  public int errorCode() {
    return httpResponse.statusCode();
  }
}
