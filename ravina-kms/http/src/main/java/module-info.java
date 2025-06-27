import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.kms.http.HttpKMSClientFactory;

module software.sava.http_kms {
  requires java.net.http;

  requires transitive systems.comodal.json_iterator;

  requires software.sava.core;
  requires transitive software.sava.kms_core;
  requires transitive software.sava.ravina_core;


  uses SigningServiceFactory;

  provides SigningServiceFactory with HttpKMSClientFactory;
}
