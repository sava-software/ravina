package software.sava.services.core.config;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.net.URI;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record RemoteResourceConfig(URI endpoint, Backoff backoff) {

  public static RemoteResourceConfig parseConfig(final JsonIterator ji,
                                                 final String defaultEndpoint,
                                                 final Backoff defaultBackoff) {
    final var parser = new Parser();
    ji.testObject(parser);
    return parser.create(defaultEndpoint, defaultBackoff);
  }

  private static final class Parser implements FieldBufferPredicate {

    private String endpoint;
    private Backoff backoff;

    private RemoteResourceConfig create(final String defaultEndpoint, final Backoff defaultBackoff) {
      return new RemoteResourceConfig(
          URI.create(requireNonNullElse(endpoint, defaultEndpoint)),
          requireNonNullElse(backoff, defaultBackoff)
      );
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("endpoint", buf, offset, len)) {
        endpoint = ji.readString();
      } else if (fieldEquals("backoff", buf, offset, len)) {
        backoff = BackoffConfig.parseConfig(ji).createHandler();
      } else {
        throw new IllegalStateException(String.format(
            "Unknown RemoteResourceConfig field [%s] [endpoint=%s]",
            new String(buf, offset, len), endpoint
        ));
      }
      return true;
    }
  }
}
