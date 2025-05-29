module software.sava.kms_core {
  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.ravina_core;
  requires systems.comodal.json_iterator;

  exports software.sava.kms.core.signing;

  provides software.sava.kms.core.signing.SigningServiceFactory with
      software.sava.kms.core.signing.MemorySignerFactory,
      software.sava.kms.core.signing.MemorySignerFromFilePointerFactory;

  uses software.sava.kms.core.signing.SigningServiceFactory;
}
