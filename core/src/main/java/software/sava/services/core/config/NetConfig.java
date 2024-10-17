package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Arrays;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

public record NetConfig(String host,
                        int port,
                        KeyStore keyStore) {

  public static NetConfig parseConfig(final JsonIterator ji) {
    final var parser = new Builder();
    ji.testObject(parser);
    return parser.create();
  }

  private static final class Builder implements FieldBufferPredicate {

    private String host;
    private int port;
    private String keyStoreType;
    private Path keyStorePath;
    private char[] password;

    private Builder() {
    }

    private NetConfig create() {
      if (keyStorePath != null) {
        try (final var is = Files.newInputStream(keyStorePath)) {
          final var keyStore = KeyStore.getInstance(requireNonNullElse(keyStoreType, "JKS"));
          keyStore.load(is, password);
          Arrays.fill(password, (char) 0);
          return new NetConfig(host, port, keyStore);
        } catch (final KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
          throw new RuntimeException(e);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else {
        return new NetConfig(host, port, null);
      }
    }

    @Override
    public boolean test(final char[] buf, final int offset, final int len, final JsonIterator ji) {
      if (fieldEquals("host", buf, offset, len)) {
        host = ji.readString();
      } else if (fieldEquals("port", buf, offset, len)) {
        port = ji.readInt();
      } else if (fieldEquals("keyStoreType", buf, offset, len)) {
        keyStoreType = ji.readString();
      } else if (fieldEquals("keyStorePath", buf, offset, len)) {
        keyStorePath = Path.of(ji.readString()).toAbsolutePath();
      } else if (fieldEquals("password", buf, offset, len)) {
        password = ji.applyChars((b, o, l) -> Arrays.copyOfRange(b, o, o + l));
      } else {
        ji.skip();
      }
      return true;
    }
  }
}
