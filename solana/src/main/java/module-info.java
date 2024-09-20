module software.sava.solana_services {

  requires software.sava.core_services;
  requires software.sava.solana_programs;
  requires software.sava.rpc;
  requires software.sava.core;

  exports software.sava.services.solana.accounts.lookup;
  exports software.sava.services.solana.remote.call;
}
