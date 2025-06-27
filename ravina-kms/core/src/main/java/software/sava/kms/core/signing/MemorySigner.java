package software.sava.kms.core.signing;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.services.core.request_capacity.CapacityMonitor;

import java.util.concurrent.CompletableFuture;

public final class MemorySigner implements SigningService {

  private final Signer signer;
  private final CompletableFuture<PublicKey> publicKey;

  public MemorySigner(final Signer signer) {
    this.signer = signer;
    this.publicKey = CompletableFuture.completedFuture(signer.publicKey());
  }

  @Override
  public CompletableFuture<PublicKey> publicKey() {
    return publicKey;
  }

  @Override
  public CompletableFuture<PublicKey> publicKeyWithRetries() {
    return publicKey();
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] msg, final int offset, final int length) {
    return CompletableFuture.completedFuture(signer.sign(msg, offset, length));
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] msg) {
    return CompletableFuture.completedFuture(signer.sign(msg, 0, msg.length));
  }

  @Override
  public CapacityMonitor capacityMonitor() {
    return null;
  }

  @Override
  public void close() {

  }
}
