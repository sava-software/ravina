module software.sava.ravina_solana {
  requires java.net.http;

  requires transitive systems.comodal.json_iterator;

  requires transitive software.sava.core;
  requires transitive software.sava.rpc;
  requires transitive software.sava.idl.clients.spl;
  requires transitive software.sava.ravina_core;
  requires transitive software.sava.kms_core;

  exports software.sava.services.solana.alt;
  exports software.sava.services.solana.config;
  exports software.sava.services.solana.epoch;
  exports software.sava.services.solana.helius.client.http;
  exports software.sava.services.solana.helius.client.http.request;
  exports software.sava.services.solana.helius.client.http.response;
  exports software.sava.services.solana.load_balance;
  exports software.sava.services.solana.remote.call;
  exports software.sava.services.solana.transactions;
  exports software.sava.services.solana.websocket;
}
