package software.sava.kms.core.signing;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.BackoffConfig;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record SigningServiceConfig(Backoff backoff, SigningService signingService) {

  public static SigningServiceConfig parseConfig(final ExecutorService executorService, final JsonIterator ji) {
    return parseConfig(executorService, null, ji);
  }

  public static SigningServiceConfig parseConfig(final ExecutorService executorService,
                                                 final Backoff defaultBackOff,
                                                 final JsonIterator ji) {
    final var parser = new Parser(executorService);
    ji.testObject(parser);
    return parser.createConfig(defaultBackOff, ji);
  }

  public static SigningServiceConfig parseConfig(final JsonIterator ji) {
    return parseConfig(Executors.newVirtualThreadPerTaskExecutor(), ji);
  }

  private static final class Parser implements FieldBufferPredicate {

    private final ExecutorService executorService;

    private Backoff backoff;
    private String factoryClass;
    private SigningService signingService;
    private int configMark = -1;

    private Parser(final ExecutorService executorService) {
      this.executorService = executorService;
    }

    SigningServiceConfig createConfig(final Backoff defaultBackOff, final JsonIterator ji) {
      if (backoff == null) {
        backoff = defaultBackOff == null
            ? Backoff.exponential(1, 32)
            : defaultBackOff;
      }
      if (signingService == null) {
        if (configMark < 0) {
          throw new IllegalStateException("Must configure a signing service");
        } else if (factoryClass == null) {
          throw new IllegalStateException("Must configure a signing service factory class");
        } else {
          createService(executorService, ji.reset(configMark));
          ji.skipRestOfObject();
        }
      }

      return new SigningServiceConfig(backoff, signingService);
    }

    private void createService(final ExecutorService executorService, final JsonIterator ji) {
      final var serviceFactory = ServiceLoader.load(SigningServiceFactory.class).stream()
          .filter(service -> service.type().getName().equals(factoryClass))
          .findFirst().orElseThrow().get();
      signingService = serviceFactory.createService(executorService, backoff, ji);
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("backoff", buf, offset, len)) {
        final var backoffConfig = BackoffConfig.parseConfig(ji);
        if (backoffConfig != null) {
          backoff = backoffConfig.createBackoff();
        }
      } else if (fieldEquals("factoryClass", buf, offset, len)) {
        factoryClass = ji.readString();
      } else if (fieldEquals("config", buf, offset, len)) {
        if (factoryClass == null || backoff == null) {
          configMark = ji.mark();
          ji.skip();
        } else {
          createService(executorService, ji);
        }
      } else {
        throw new IllegalStateException("Unknown SigningServiceConfig field " + new String(buf, offset, len));
      }
      return true;
    }
  }
}
