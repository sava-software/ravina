plugins {
  id("software.sava.build.feature.publish-maven-central")
}

dependencies {
  centralPortalAggregation(project(":ravina-core"))
  centralPortalAggregation(project(":ravina-solana"))
  centralPortalAggregation(project(":ravina-kms-core"))
  centralPortalAggregation(project(":ravina-kms-http"))
  centralPortalAggregation(project(":ravina-kms-google"))
}
