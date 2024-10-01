#!/usr/bin/env bash

set -e

readonly targetJavaVersion=23
simpleProjectName="solana"
moduleName="software.sava.solana_services"
mainClass="software.sava.services.solana.accounts.lookup.http.LookupTableWebService"

jvmArgs="-server -XX:+DisableExplicitGC -XX:+UseZGC -Xms8G -Xmx13G"
logLevel="INFO";
configFile="";

screen=0;

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
        ;;

      mc | mainClass) mainClass="$val";;
      mn | moduleName) moduleName="$val";;
      spn | simpleProjectName) simpleProjectName="$val";;

      jvm | jvmArgs) jvmArgs="$val";;
      tjv | targetJavaVersion) targetJavaVersion="$val";;

      cf | configFile) configFile="$val";;

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

./gradlew --exclude-task=test :"$simpleProjectName":jlink -PnoVersionTag=true

javaExe="$(pwd)/$simpleProjectName/build/$simpleProjectName/bin/java"
readonly javaExe

jvmArgs="$jvmArgs -D$moduleName.logLevel=$logLevel -D$moduleName.config=$configFile -m $moduleName/$mainClass"
IFS=' ' read -r -a jvmArgsArray <<< "$jvmArgs"

if [[ "$screen" == 0 ]]; then
  set -x
  "$javaExe" "${jvmArgsArray[@]}"
else
  set -x
  screen -S "$simpleProjectName" "$javaExe" "${jvmArgsArray[@]}"
fi