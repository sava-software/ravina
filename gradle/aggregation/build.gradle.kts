plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  nmcpAggregation(project(":ravina-core"))
  nmcpAggregation(project(":ravina-solana"))
  nmcpAggregation(project(":ravina-kms-core"))
  nmcpAggregation(project(":ravina-kms-http"))
  nmcpAggregation(project(":ravina-kms-google"))
}

tasks.register("publishToGitHubPackages") {
  group = "publishing"
  dependsOn(
    ":ravina-core:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository",
    ":ravina-solana:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository",
    ":ravina-kms-core:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository",
    ":ravina-kms-http:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository",
    ":ravina-kms-google:publishMavenJavaPublicationToSavaGithubPackagesPublishRepository"
  )
}
