import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.kms.http.HttpKMSClientFactory;

module software.sava.http_kms {
  requires java.net.http;

  requires systems.comodal.json_iterator;

  requires software.sava.core;
  requires software.sava.rpc;
  requires software.sava.ravina_core;
  requires software.sava.kms_core;

  uses SigningServiceFactory;

  provides SigningServiceFactory with HttpKMSClientFactory;
}
