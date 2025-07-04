import software.sava.kms.core.signing.SigningServiceFactory;
import software.sava.kms.google.GoogleKMSClientFactory;

module software.sava.google_kms {
  requires transitive systems.comodal.json_iterator;

  requires software.sava.core;
  requires transitive google.cloud.kms;
  requires transitive software.sava.kms_core;
  requires transitive software.sava.ravina_core;

  requires com.google.protobuf;

  exports software.sava.kms.google;

  uses SigningServiceFactory;
  provides SigningServiceFactory with GoogleKMSClientFactory;
}
