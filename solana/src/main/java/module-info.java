module software.sava.solana_services {
  requires jdk.httpserver;
  requires java.net.http;
  requires systems.comodal.json_iterator;
  requires software.sava.core_services;
  requires software.sava.anchor_programs;
  requires software.sava.solana_programs;
  requires software.sava.rpc;
  requires software.sava.core;

  exports software.sava.services.solana.accounts.lookup;
  exports software.sava.services.solana.accounts.lookup.http;
  exports software.sava.services.solana.load_balance;
  exports software.sava.services.solana.remote.call;
}
