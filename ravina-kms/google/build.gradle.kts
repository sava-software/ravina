dependencies.constraints {
  implementation("io.grpc:grpc-netty-shaded:1.71.0!!")
}

testModuleInfo {
  requires("org.junit.jupiter.api")
  runtimeOnly("org.junit.jupiter.engine")
}
