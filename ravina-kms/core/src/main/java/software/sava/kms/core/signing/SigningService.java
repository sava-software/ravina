package software.sava.kms.core.signing;

import software.sava.core.accounts.PublicKey;
import software.sava.services.core.request_capacity.CapacityMonitor;

import java.util.concurrent.CompletableFuture;

public interface SigningService extends AutoCloseable {

  CompletableFuture<PublicKey> publicKey();

  CompletableFuture<PublicKey> publicKeyWithRetries();

  CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length);

  CompletableFuture<byte[]> sign(final byte[] msg);

  CapacityMonitor capacityMonitor();
}
