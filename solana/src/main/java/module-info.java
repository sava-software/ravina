module software.sava.solana_services {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.solana_programs;
  requires software.sava.solana_web2;
  requires software.sava.anchor_programs;
  requires software.sava.core_services;
  requires software.sava.jetty_services;

  requires org.eclipse.jetty.server;
  requires org.eclipse.jetty.http2.server;
  requires org.eclipse.jetty.alpn.server;
  requires org.eclipse.jetty.http3.server;

  exports software.sava.services.solana.accounts.lookup;
  exports software.sava.services.solana.accounts.lookup.http;
  exports software.sava.services.solana.config;
  exports software.sava.services.solana.load_balance;
  exports software.sava.services.solana.remote.call;
}
