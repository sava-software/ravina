package software.sava.services.core.config;

import systems.comodal.jsoniter.JsonIterator;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

public interface NetConfig {

  static NetConfig parseConfig(JsonIterator ji) {
    final var parser = new NetConfigRecord.Builder();
    ji.testObject(parser);
    return parser.create();
  }

  void cleanPassword();

  KeyManagerFactory createKeyManagerFactory();

  TrustManagerFactory createTrustManagerFactory();

  SSLContext createSSLContext(final String sslContextProtocol);

  SSLContext createSSLContext();

  String host();

  int port();

  KeyStore keyStore();

  char[] keyStorePassword();
}
