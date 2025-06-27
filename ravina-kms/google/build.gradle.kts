dependencies {
  implementation(platform("software.sava:solana-version-catalog:${solanaBOMVersion()}"))
  implementation(project(":ravina-core"))
  implementation(project(":ravina-kms-core"))
  implementation("software.sava:json-iterator")
  implementation("software.sava:sava-core")
  implementation("software.sava:sava-rpc")
  implementation("com.google.cloud:google-cloud-kms")
}
