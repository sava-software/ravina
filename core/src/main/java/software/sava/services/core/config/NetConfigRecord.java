package software.sava.services.core.config;

import systems.comodal.jsoniter.FieldBufferPredicate;
import systems.comodal.jsoniter.JsonIterator;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Arrays;

import static java.util.Objects.requireNonNullElse;
import static systems.comodal.jsoniter.JsonIterator.fieldEquals;

record NetConfigRecord(String host,
                       int port,
                       KeyStore keyStore,
                       char[] keyStorePassword) implements NetConfig {

  @Override
  public void cleanPassword() {
    Arrays.fill(keyStorePassword, (char) 0);
  }

  @Override
  public KeyManagerFactory createKeyManagerFactory() {
    try {
      final var keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyManagerFactory.init(keyStore, keyStorePassword);
      return keyManagerFactory;
    } catch (final NoSuchAlgorithmException | KeyStoreException | UnrecoverableKeyException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public TrustManagerFactory createTrustManagerFactory() {
    try {
      final var trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init(keyStore);
      return trustManagerFactory;
    } catch (final NoSuchAlgorithmException | KeyStoreException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SSLContext createSSLContext(final String sslContextProtocol) {
    final var keyManagerFactory = createKeyManagerFactory();
    final var trustManagerFactory = createTrustManagerFactory();
    try {
      final var sslContext = SSLContext.getInstance(sslContextProtocol);
      sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), new SecureRandom());
      return sslContext;
    } catch (final NoSuchAlgorithmException | KeyManagementException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public SSLContext createSSLContext() {
    return createSSLContext("TLS");
  }

  static final class Builder implements FieldBufferPredicate {

    private String host;
    private int port;
    private String keyStoreType;
    private Path keyStorePath;
    private char[] password;

    Builder() {
    }

    NetConfig create() {
      if (keyStorePath != null) {
        try (final var is = Files.newInputStream(keyStorePath)) {
          final var keyStore = KeyStore.getInstance(requireNonNullElse(keyStoreType, "JKS"));
          keyStore.load(is, password);
          return new NetConfigRecord(host, port, keyStore, password);
        } catch (final KeyStoreException | NoSuchAlgorithmException | CertificateException e) {
          throw new RuntimeException(e);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else {
        return new NetConfigRecord(host, port, null, null);
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
