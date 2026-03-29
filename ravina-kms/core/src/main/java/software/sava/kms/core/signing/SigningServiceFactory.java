package software.sava.kms.core.signing;

import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.JsonIterator;

import java.util.Properties;
import java.util.concurrent.ExecutorService;

public interface SigningServiceFactory {

  SigningService createService(final ExecutorService executorService,
                               final Backoff backoff,
                               final JsonIterator ji,
                               final ErrorTrackerFactory<Throwable> errorTrackerFactory);

  SigningService createService(final ExecutorService executorService,
                               final Backoff backoff,
                               final JsonIterator ji);

  default SigningService createService(final JsonIterator ji) {
    return createService(null, null, ji, null);
  }

  SigningService createService(final ExecutorService executorService,
                               final Backoff backoff,
                               final String prefix,
                               final Properties properties,
                               final ErrorTrackerFactory<Throwable> errorTrackerFactory);

  SigningService createService(final ExecutorService executorService,
                               final Backoff backoff,
                               final String prefix,
                               final Properties properties);

  default SigningService createService(final Properties properties) {
    return createService(null, null, "", properties, null);
  }
}
