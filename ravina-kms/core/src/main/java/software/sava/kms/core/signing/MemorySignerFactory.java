package software.sava.kms.core.signing;

import software.sava.rpc.json.PrivateKeyEncoding;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.JsonIterator;

import java.util.concurrent.ExecutorService;

public final class MemorySignerFactory implements SigningServiceFactory {

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    final var signer = PrivateKeyEncoding.fromJsonPrivateKey(ji);
    return new MemorySigner(signer);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji) {
    return createService(executorService, backoff,ji, null);
  }
}
