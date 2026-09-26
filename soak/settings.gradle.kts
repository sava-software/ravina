// Standalone JFR soak build for ravina. Mirrors sava's soak/: an independent Gradle build
// that includes the library build from '..', so the composite substitutes every
// 'software.sava:ravina-*' coordinate with the local project and a soak always runs
// against the working tree. sava-core and sava-rpc resolve from Maven Central at the
// version the solana-version-catalog platform pins; everything else sava publishes
// resolves from GitHub Packages.
//
// Deliberately applies NO sava-build plugin: only the built-in 'java' and 'application'
// plugins are used, and 'includeBuild("..")' configures the root build's own
// pluginManagement (including its -PsavaBuildLocalRepo toggle) for the whole composite.
// A '-PsavaBuildLocalRepo=<abs path>' passed on this build's command line therefore still
// reaches the root build; prefer an absolute value, since a relative one resolves against
// each build's own settings dir.
rootProject.name = "ravina-soak"

includeBuild("..")
