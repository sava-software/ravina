package software.sava.kms.core.signing;

import org.junit.jupiter.api.Test;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.Signer;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/// `MemorySigner` is called from every thread that sends a transaction, and sava-core's key-pair
/// signer must not be shared between threads (one `java.security.Signature` per signer). The
/// property is that every thread signs on its own dedicated signer; the fake below fails the
/// test outright if the shared signer is ever asked to sign, so the outcome does not depend on
/// two threads actually colliding.
final class MemorySignerTests {

  private static final PublicKey PUBLIC_KEY;

  static {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) ((i * 7) + 3);
    }
    PUBLIC_KEY = Signer.createFromPrivateKey(privateKey).publicKey();
  }

  /// The shared signer refuses to sign; each dedicated signer it hands out records who signed
  /// on it and answers with bytes only it produces (64 bytes of its own ordinal).
  static final class RecordingSigner implements Signer {

    final int ordinal;
    final List<RecordingSigner> dedicated = new CopyOnWriteArrayList<>();
    final List<Thread> signedBy = new CopyOnWriteArrayList<>();
    /// Two threads' first signs ask for their dedicated signers at once; the ordinal must not
    /// be read off the list's size in between.
    private final AtomicInteger ordinals = new AtomicInteger();

    RecordingSigner(final int ordinal) {
      this.ordinal = ordinal;
    }

    byte[] expectedSignature() {
      final byte[] signature = new byte[64];
      Arrays.fill(signature, (byte) ordinal);
      return signature;
    }

    @Override
    public PublicKey publicKey() {
      return PUBLIC_KEY;
    }

    @Override
    public PrivateKey privateKey() {
      return null;
    }

    @Override
    public Signer createDedicatedSigner() {
      final var signer = new RecordingSigner(ordinals.incrementAndGet());
      dedicated.add(signer);
      return signer;
    }

    @Override
    public int sign(final byte[] message, final int msgOffset, final int msgLen, final int outPos) {
      final byte[] signature = sign(message, msgOffset, msgLen);
      System.arraycopy(signature, 0, message, outPos, signature.length);
      return outPos + signature.length;
    }

    @Override
    public byte[] sign(final byte[] message, final int msgOffset, final int msgLen) {
      if (ordinal == 0) {
        throw new AssertionError("the shared signer must never sign: its Signature engine is not thread-safe");
      }
      signedBy.add(Thread.currentThread());
      return expectedSignature();
    }

    @Override
    public byte[] sign(final byte[] message) {
      return sign(message, 0, message.length);
    }
  }

  private static byte[] message(final String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  @Test
  void eachThreadSignsOnItsOwnDedicatedSigner() throws InterruptedException {
    final var shared = new RecordingSigner(0);
    final var memorySigner = new MemorySigner(shared);
    final var fromOtherThread = new ArrayList<byte[]>();

    final var other = Thread.ofPlatform().name("memory-signer-test").start(
        () -> fromOtherThread.add(memorySigner.sign(message("other"), 0, 5).join())
    );
    final byte[] fromThisThread = memorySigner.sign(message("this"), 0, 4).join();
    other.join();

    assertEquals(2, shared.dedicated.size(), "one dedicated signer per signing thread");
    final var mine = shared.dedicated.stream().filter(s -> s.signedBy.contains(Thread.currentThread())).findFirst().orElseThrow();
    final var theirs = shared.dedicated.stream().filter(s -> s.signedBy.contains(other)).findFirst().orElseThrow();
    assertNotSame(mine, theirs);
    assertEquals(List.of(Thread.currentThread()), mine.signedBy);
    assertEquals(List.of(other), theirs.signedBy);
    assertArrayEquals(mine.expectedSignature(), fromThisThread);
    assertArrayEquals(theirs.expectedSignature(), fromOtherThread.getFirst());
  }

  @Test
  void aThreadKeepsItsDedicatedSignerAcrossCallsAndOverloads() {
    final var shared = new RecordingSigner(0);
    final var memorySigner = new MemorySigner(shared);

    final byte[] first = memorySigner.sign(message("first")).join();
    final byte[] second = memorySigner.sign(message("second"), 0, 6).join();

    assertEquals(1, shared.dedicated.size(), "a thread signs on one dedicated signer, not one per call");
    final var dedicated = shared.dedicated.getFirst();
    assertEquals(2, dedicated.signedBy.size());
    assertArrayEquals(dedicated.expectedSignature(), first);
    assertArrayEquals(dedicated.expectedSignature(), second);
  }

  @Test
  void thePublicKeyIsTheSharedSignersAndNeedsNoDedicatedSigner() {
    final var shared = new RecordingSigner(0);
    final var memorySigner = new MemorySigner(shared);

    assertEquals(PUBLIC_KEY, memorySigner.publicKey().join());
    assertEquals(PUBLIC_KEY, memorySigner.publicKeyWithRetries().join());
    assertTrue(shared.dedicated.isEmpty(), "the public key comes from the shared signer");
    assertNull(memorySigner.capacityMonitor());
  }

  /// The implementation-independent oracle: every signature verifies against its own message,
  /// across threads, on a real key. Deterministic on the fixed code; on a shared signer it
  /// fails whenever two threads collide, which is likely but not certain, so the tests above
  /// carry the kill.
  @Test
  void concurrentSignaturesOnARealKeyAllVerify() throws InterruptedException {
    final byte[] privateKey = new byte[Signer.KEY_LENGTH];
    for (int i = 0; i < privateKey.length; ++i) {
      privateKey[i] = (byte) ((i * 11) + 5);
    }
    final var signer = Signer.createFromPrivateKey(privateKey);
    final var javaPublicKey = PublicKey.toJavaPublicKey(signer.publicKey().toByteArray());
    final var memorySigner = new MemorySigner(signer);
    final int threads = 4;
    final int perThread = 500;
    final var invalid = new CopyOnWriteArrayList<String>();
    final var failures = new CopyOnWriteArrayList<Throwable>();
    final var verified = new AtomicInteger();

    final var workers = new ArrayList<Thread>(threads);
    for (int t = 0; t < threads; ++t) {
      final int thread = t;
      workers.add(Thread.ofPlatform().name("memory-signer-" + t).start(() -> {
        try {
          // One verifier per thread: a java.security.Signature is exactly as unshareable
          // as the signer under test.
          final var verifier = Signature.getInstance("Ed25519");
          verifier.initVerify(javaPublicKey);
          for (int i = 0; i < perThread; ++i) {
            final var text = "memo " + thread + " " + i;
            final byte[] message = message(text);
            final byte[] signature = memorySigner.sign(message).join();
            verifier.update(message);
            if (verifier.verify(signature)) {
              verified.incrementAndGet();
            } else {
              invalid.add(text);
            }
          }
        } catch (final Throwable failure) {
          // A shared signer can also throw from sign(); a worker that dies must fail the
          // test, not silently shorten it.
          failures.add(failure);
        }
      }));
    }
    for (final var worker : workers) {
      worker.join();
    }

    assertTrue(failures.isEmpty(), () -> "worker failures: " + failures);
    assertEquals(List.of(), invalid, "every signature must verify against its own message");
    assertEquals(threads * perThread, verified.get(), "every signature must have been checked");
  }
}
