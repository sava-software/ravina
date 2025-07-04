plugins {
  id("org.gradlex.extra-java-module-info")
}

extraJavaModuleInfo {
  automaticModule("com.google.cloud:google-cloud-kms", "google.cloud.kms") {
    mergeJar("com.google.api.grpc:proto-google-cloud-kms-v1")
  }
  automaticModule("com.google.code.findbugs:jsr305", "com.google.code.findbugs.jsr305") {
    mergeJar("javax.annotation:javax.annotation-api")
  }
  automaticModule("com.google.api:gax", "com.google.api.gax")
  automaticModule("com.google.api:gax-grpc", "com.google.api.gax_grpc") {
    mergeJar("com.google.api.grpc:proto-google-common-protos")
  }
  automaticModule("com.google.api:gax-httpjson", "com.google.api.gax_httpjson")
  automaticModule("com.google.api.grpc:proto-google-iam-v1", "com.google.api.grpc.proto_google_iam_v1")
  automaticModule("com.google.auto.value:auto-value-annotations", "com.google.auto_value_annotations")
  automaticModule("org.codehaus.mojo:animal-sniffer-annotations", "org.codehaus.mojo.animal_sniffer_annotations")
  automaticModule("io.grpc:grpc-context", "io.grpc.context")
  automaticModule("io.opencensus:opencensus-api", "io.opencensus.api")
  automaticModule("io.opencensus:opencensus-contrib-http-util", "io.opencensus.contrib_http_util")
}
