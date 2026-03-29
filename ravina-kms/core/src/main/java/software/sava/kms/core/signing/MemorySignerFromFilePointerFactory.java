package software.sava.kms.core.signing;

import software.sava.rpc.json.PrivateKeyEncoding;
import software.sava.services.core.config.PropertiesParser;
import software.sava.services.core.remote.call.Backoff;
import software.sava.services.core.request_capacity.trackers.ErrorTrackerFactory;
import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ExecutorService;

import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public final class MemorySignerFromFilePointerFactory implements SigningServiceFactory, FieldBufferPredicate {

  private Path filePath;

  private static SigningService signerFromFile(final Path filePath) {
    final var fileName = filePath.getFileName().toString();
    try {
      final var fileBytes = Files.readAllBytes(filePath);
      if (fileName.endsWith(".properties")) {
        final var props = new Properties();
        try (final var is = new ByteArrayInputStream(fileBytes)) {
          props.load(is);
        }
        final var signer = PrivateKeyEncoding.fromProperties(props);
        return new MemorySigner(signer);
      } else if (fileName.endsWith(".json")) {
        try (final var privateKeyJI = JsonIterator.parse(fileBytes)) {
          final var signer = PrivateKeyEncoding.fromJsonPrivateKey(privateKeyJI);
          return new MemorySigner(signer);
        }
      } else {
        throw new IllegalArgumentException("Unsupported file extension for filePath: " + filePath + ". Expected .properties or .json");
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    ji.testObject(this);
    return signerFromFile(filePath);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final JsonIterator ji) {
    return createService(executorService, backoff, ji, null);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties,
                                      final ErrorTrackerFactory<Throwable> errorTrackerFactory) {
    final var p = PropertiesParser.propertyPrefix(prefix);
    final var filePathStr = PropertiesParser.getProperty(properties, p, "filePath");
    if (filePathStr == null) {
      throw new IllegalStateException("Must configure a filePath property.");
    }
    this.filePath = Path.of(filePathStr);
    return signerFromFile(filePath);
  }

  @Override
  public SigningService createService(final ExecutorService executorService,
                                      final Backoff backoff,
                                      final String prefix,
                                      final Properties properties) {
    return createService(executorService, backoff, prefix, properties, null);
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
