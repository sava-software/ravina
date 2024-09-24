#!/usr/bin/env bash

set -e

readonly targetJavaVersion=23
readonly moduleName="software.sava.solana_services"
readonly package="software.sava.services.solana.accounts.lookup"
readonly mainClass="$package.http.LookupTableWebService"
projectDirectory="$(pwd)"
readonly projectDirectory

javaArgs=(
  '--enable-preview'
  '-XX:+UseZGC'
  '-Xms256M'
  '-Xmx1024M'
  '-server'
)

screen=0;
logLevel="INFO";
configFile="";

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      l | log)
          case "$val" in
            INFO|WARN|DEBUG) logLevel="$val";;
            *)
              printf "'%slog=[INFO|WARN|DEBUG]' not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
          javaArgs+=("-D$moduleName.logLevel=$logLevel")
        ;;

      screen)
        case "$val" in
          1|*screen) screen=1 ;;
          0) screen=0 ;;
          *)
            printf "'%sscreen=[0|1]' or '%sscreen' not '%s'.\n" "--" "--" "$arg";
            exit 2;
          ;;
        esac
        ;;

      tjv | targetJavaVersion) targetJavaVersion="$val";;

      cf | configFile) configFile="$val";;

      *)
          printf "Unsupported flag '%s' [key=%s] [val=%s].\n" "$arg" "$key" "$val";
          exit 1;
        ;;
    esac
  else
    printf "Unhandled argument '%s', all flags must begin with '%s'.\n" "$arg" "--";
    exit 1;
  fi
done

javaVersion=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | grep -oEi '^[0-9]+')
readonly javaVersion
if [[ "$javaVersion" -ne "$targetJavaVersion" ]]; then
  echo "Invalid Java version $javaVersion must be $targetJavaVersion."
  exit 3
fi

./gradlew --stacktrace "-PmainClassName=$mainClass" clean jlink

vcsRef="$(git rev-parse --short HEAD)"
readonly vcsRef
readonly javaExe="$projectDirectory/build/$vcsRef/bin/java"

javaArgs+=(
  "-D$package.LookupTableServiceConfig=$configFile"
  '-m' "$moduleName/$mainClass"
)

if [[ "$screen" == 0 ]]; then
  set -x
  "$javaExe" "${javaArgs[@]}"
else
  set -x
  screen -S "anchor-src-gen" "$javaExe" "${javaArgs[@]}"
fi