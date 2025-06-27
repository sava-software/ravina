package software.sava.kms.core.signing;

import software.sava.core.accounts.PublicKey;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.remote.call.Call;
import software.sava.services.core.request_capacity.CapacityMonitor;
import software.sava.services.core.request_capacity.CapacityState;
import software.sava.services.core.request_capacity.ErrorTrackedCapacityMonitor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;

public abstract class BaseKMSClient implements SigningService {

  protected final ExecutorService executorService;
  protected final Backoff backoff;
  protected final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor;
  protected final CapacityState capacityState;
  protected final Predicate<Throwable> errorTracker;

  protected BaseKMSClient(final ExecutorService executorService,
                          final Backoff backoff,
                          final ErrorTrackedCapacityMonitor<Throwable> capacityMonitor,
                          final Predicate<Throwable> errorTracker) {
    this.executorService = executorService;
    this.backoff = backoff;
    this.capacityMonitor = capacityMonitor;
    this.capacityState = capacityMonitor == null ? null : capacityMonitor.capacityState();
    this.errorTracker = errorTracker;
  }

  @Override
  public final CapacityMonitor capacityMonitor() {
    return capacityMonitor;
  }

  @Override
  public CompletableFuture<PublicKey> publicKeyWithRetries() {
    if (capacityState == null) {
      return Call.createComposedCall(
          this::publicKey,
          backoff,
          "SigningService::publicKey"
      ).async(executorService);
    } else {
      return Call.createCourteousCall(
          this::publicKey,
          capacityState,
          backoff,
          "SigningService::publicKey"
      ).async(executorService);
    }
  }
}
