plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":ravina-core"))
  nmcpAggregation(project(":ravina-solana"))
  nmcpAggregation(project(":ravina-jetty"))
  nmcpAggregation(project(":ravina-kms-core"))
  nmcpAggregation(project(":ravina-kms-http"))
  // nmcpAggregation(project(":ravina-kms-google"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    ":ravina-core:publishMavenJavaPublicationToSavaGithubPackagesRepository",
    ":ravina-solana:publishMavenJavaPublicationToSavaGithubPackagesRepository",
    ":ravina-jetty:publishMavenJavaPublicationToSavaGithubPackagesRepository",
    ":ravina-kms-core:publishMavenJavaPublicationToSavaGithubPackagesRepository",
    ":ravina-kms-http:publishMavenJavaPublicationToSavaGithubPackagesRepository"
    // ":ravina-kms-google:publishMavenJavaPublicationToSavaGithubPackagesRepository"
  )
}
