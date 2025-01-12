module software.sava.solana_services {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.solana_programs;
  requires software.sava.solana_web2;
  requires software.sava.anchor_programs;
  requires software.sava.core_services;
  requires software.sava.kms_core;
  requires org.bouncycastle.provider;

  exports software.sava.services.net.http;
  exports software.sava.services.solana.alt;
  exports software.sava.services.solana.config;
  exports software.sava.services.solana.epoch;
  exports software.sava.services.solana.load_balance;
  exports software.sava.services.solana.remote.call;
  exports software.sava.services.solana.transactions;
  exports software.sava.services.solana.websocket;
}
