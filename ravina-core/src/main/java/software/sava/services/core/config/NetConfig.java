package software.sava.services.core.config;

import systems.comodal.jsoniter.JsonIterator;
import systems.comodal.jsoniter.ValueType;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.util.Properties;

public interface NetConfig {

  static NetConfig parseConfig(final Properties properties) {
    return parseConfig("", properties);
  }

  static NetConfig parseConfig(final String prefix, final Properties properties) {
    final var parser = new NetConfigRecord.Parser();
    parser.parseProperties(prefix, properties);
    return parser.create();
  }

  static NetConfig parseConfig(final JsonIterator ji) {
    if (ji.whatIsNext() == ValueType.NULL) {
      ji.skip();
      return null;
    } else {
      final var parser = new NetConfigRecord.Parser();
      ji.testObject(parser);
      return parser.create();
    }
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
