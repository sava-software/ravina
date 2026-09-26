package software.sava.kms.core.signing;

import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;
import software.sava.services.core.request_capacity.CapacityMonitor;

import java.util.concurrent.CompletableFuture;

/// Signs in process with a key held in memory. Safe to call from any number of threads at
/// once: every thread signs on its own [Signer#createDedicatedSigner() dedicated signer].
///
/// The dedicated signer is not an optimisation. sava-core's key-pair signer wraps one
/// `java.security.Signature`, which accumulates the message across `update` calls and signs
/// whatever it has accumulated, so two threads signing at once through one instance get
/// signatures that are not over their own messages: over both, over none, at times identical
/// across the two threads, at times an exception. A validator drops such a transaction without
/// a status, or, when the signature happens to be valid for one of the two, lands that one
/// while the other caller is reported confirmed for instructions that never executed. The JFR
/// soak harness caught both on 2026-09-26 when a rate-limit dock released a convoy of workers
/// into `signAndSendTx` in the same millisecond; measured directly, a shared signer under two
/// threads returned an invalid signature for about half of 40,000 calls, and none under a
/// dedicated signer per thread. Each signing thread keeps its dedicated signer, a copy of the
/// key inside a `Signature` engine, until the thread exits.
public final class MemorySigner implements SigningService {

  private final CompletableFuture<PublicKey> publicKey;
  private final ThreadLocal<Signer> dedicatedSigner;

  public MemorySigner(final Signer signer) {
    this.publicKey = CompletableFuture.completedFuture(signer.publicKey());
    this.dedicatedSigner = ThreadLocal.withInitial(signer::createDedicatedSigner);
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
    return CompletableFuture.completedFuture(dedicatedSigner.get().sign(msg, offset, length));
  }

  @Override
  public CompletableFuture<byte[]> sign(final byte[] msg) {
    return sign(msg, 0, msg.length);
  }

  @Override
  public CapacityMonitor capacityMonitor() {
    return null;
  }

  @Override
  public void close() {

  }
}
