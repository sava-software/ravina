module software.sava.solana_services {
  requires systems.comodal.json_iterator;

  requires org.bouncycastle.provider;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.core_services;

  exports software.sava.services.solana.accounts.lookup;
  exports software.sava.services.solana.remote.call;
}
