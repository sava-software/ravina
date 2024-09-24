module software.sava.solana_services {

  requires software.sava.core_services;
  requires software.sava.anchor_programs;
  requires software.sava.solana_programs;
  requires software.sava.rpc;
  requires systems.comodal.json_iterator;
  requires java.net.http;
  requires software.sava.core;
  requires jdk.httpserver;
  requires org.bouncycastle.provider;
  requires java.management;

  exports software.sava.services.solana.accounts.lookup;
  exports software.sava.services.solana.remote.call;
  exports software.sava.services.solana.accounts.lookup.http;
}
