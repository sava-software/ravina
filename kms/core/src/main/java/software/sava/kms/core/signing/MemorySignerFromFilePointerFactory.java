package software.sava.kms.core.signing;

import software.sava.rpc.json.PrivateKeyEncoding;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class MemorySignerFromFilePointerFactory implements SigningServiceFactory, FieldBufferPredicate {

  private Path filePath;

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    ji.testObject(this);
    try (final var privateKeyJI = JsonIterator.parse(Files.readAllBytes(filePath))) {
      final var signer = PrivateKeyEncoding.fromJsonPrivateKey(privateKeyJI);
      return new MemorySigner(signer);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji) {
    return createService(executorService, backoff, ji, null);
  }

  @Override
  public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
    if (fieldEquals("filePath", buf, offset, len)) {
      filePath = Path.of(ji.readString());
    } else {
      ji.skip();
    }
    return true;
  }
}
