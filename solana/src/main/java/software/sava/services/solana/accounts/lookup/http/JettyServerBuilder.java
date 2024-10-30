package software.sava.services.solana.accounts.lookup.http;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import software.sava.services.jetty.handlers.JettyHandler;
import software.sava.services.jetty.handlers.RootJettyHandler;
import software.sava.services.solana.accounts.lookup.LookupTableCache;
import software.sava.services.solana.accounts.lookup.LookupTableDiscoveryService;
import software.sava.services.solana.accounts.lookup.LookupTableServiceConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Map;

public final class JettyServerBuilder {

  private static void addHandler(final Map<String, JettyHandler> handlers,
                                 final String path,
                                 final JettyHandler handler) {
    handlers.put(path, handler);
    final int to = path.length() - 1;
    if (path.charAt(to) == '/') {
      handlers.put(path.substring(0, to), handler);
    } else {
      handlers.put(path + '/', handler);
    }
  }

  private final LookupTableServiceConfig serviceConfig;
  private final LookupTableServiceConfig.WebServerConfig webServerConfig;

  private final Server server;
  private final HttpConfiguration jettyHttpConfig;
  private final Map<String, JettyHandler> handlers;

  public JettyServerBuilder(final LookupTableServiceConfig serviceConfig,
                            final Server server,
                            final HttpConfiguration jettyHttpConfig,
                            final Map<String, JettyHandler> handlers) {
    this.serviceConfig = serviceConfig;
    this.webServerConfig = serviceConfig.webServerConfig();
    this.server = server;
    this.jettyHttpConfig = jettyHttpConfig;
    this.handlers = handlers;
  }

  Server server() {
    return server;
  }

  private void addSecureConnector(final AbstractNetworkConnector connector) {
    final var httpsConfig = webServerConfig.httpsConfig();
    connector.setHost(httpsConfig.host());
    connector.setPort(httpsConfig.port());
    server.addConnector(connector);
  }

  void initHttp() {
    final var httpConfig = webServerConfig.httpConfig();
    if (httpConfig == null) {
      return;
    }
    final var h11 = new HttpConnectionFactory(jettyHttpConfig);
    final var h2 = new HTTP2CServerConnectionFactory(jettyHttpConfig);
    final var serverConnector = new ServerConnector(server, h11, h2);
    serverConnector.setHost(httpConfig.host());
    serverConnector.setPort(httpConfig.port());
    server.addConnector(serverConnector);
  }

  void initHttps() {
    final var httpsConfig = webServerConfig.httpsConfig();
    if (httpsConfig == null) {
      return;
    }

    final var secureConfig = new HttpConfiguration(jettyHttpConfig);
    final var src = new SecureRequestCustomizer();
    src.setSniHostCheck(false);
    secureConfig.addCustomizer(src);
    secureConfig.setSecurePort(httpsConfig.port());

    final var sslContextFactory = new SslContextFactory.Server();
    sslContextFactory.setKeyStore(httpsConfig.keyStore());
    sslContextFactory.setKeyStorePassword(new String(httpsConfig.keyStorePassword()));
    sslContextFactory.setTrustStore(httpsConfig.keyStore());

//        final var arch = System.getProperty("os.arch");
//        if (!arch.equalsIgnoreCase("aarch64")) {
//          Security.addProvider(new OpenSSLProvider());
//          sslContextFactory.setProvider("Conscrypt");
//        }

    final var h11 = new HttpConnectionFactory(secureConfig);
    final var h2 = new HTTP2ServerConnectionFactory(secureConfig);
    final var alpn = new ALPNServerConnectionFactory();
    alpn.setDefaultProtocol(h11.getProtocol());
    final var tls = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
    final var tlsConnector = new ServerConnector(server, tls, alpn, h2, h11);
    addSecureConnector(tlsConnector);

    final var pemWorkDir = serviceConfig.workDir().resolve("quic");
    if (!Files.exists(pemWorkDir)) {
      try {
        Files.createDirectories(pemWorkDir);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    final var serverQuicConfig = new ServerQuicConfiguration(sslContextFactory, pemWorkDir);
    final var h3 = new HTTP3ServerConnectionFactory(serverQuicConfig, secureConfig);
    final var h3Connector = new QuicServerConnector(server, serverQuicConfig, h3);
    addSecureConnector(h3Connector);
  }

  void addHandlers(final LookupTableDiscoveryService tableService, final LookupTableCache tableCache) {
    addHandler(handlers, "/v0/alt/discover/tx/sig", new FromTxSigHandler(
        tableService, tableCache));
    addHandler(handlers, "/v0/alt/discover/tx/raw", new FromRawTxHandler(tableService, tableCache));
    addHandler(handlers, "/v0/alt/discover/accounts", new FromAccountsHandler(tableService, tableCache));

    final var rootHandler = new RootJettyHandler(
        Map.copyOf(handlers),
        webServerConfig.allowedOrigins(),
        serviceConfig.localDev()
    );
    server.setHandler(rootHandler);
  }
}
