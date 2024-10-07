#!/usr/bin/env bash

set -e

#. /downloadOpenJDK.sh ---a=ga --v=23 --b=37 --id=3c5b90190c68498b986a97f276efd28a --c=08fea92724127c6fa0f2e5ea0b07ff4951ccb1e2f22db3c21eebbd7347152a67

availability="ga"
version="23"
build="37"
id="3c5b90190c68498b986a97f276efd28a"
checksum="08fea92724127c6fa0f2e5ea0b07ff4951ccb1e2f22db3c21eebbd7347152a67"

for arg in "$@"
do
  if [[ "$arg" =~ ^--.* ]]; then
    key="${arg%%=*}"
    key="${key##*--}"
    val="${arg#*=}"

    case "$key" in
      a | availability)
          case "$val" in
            ea|ga) availability="$val";;
            *)
              printf "'%key=[ea|ga]' not '%s'.\n" "--" "$arg";
              exit 2;
            ;;
          esac
        ;;
      b | build) build="$val";;
      c | checksum) checksum="$val";;
      i | id) id="$val";;
      v | version) version="$val";;
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

curlJDK() {
  curl -LfSo /tmp/openjdk.tar.gz "$1"
  echo "$2 */tmp/openjdk.tar.gz" | sha256sum -c -
  sudo mkdir -p "$3"
  cd "$3"
  sudo tar -xf /tmp/openjdk.tar.gz --strip-components=1
  rm -rf /tmp/openjdk.tar.gz
}

exportJavaHome() {
  if grep -q "export $1=" "$HOME/.profile"; then
    printf "\nexport %s=%s\n" "%$1" "$2" >> "$HOME/.profile"
  else
     sed -i "s|^export $1=.*|export $1=$2|" "$HOME/.profile"
     "$2/bin/java" --version
     echo "Run source ~/.profile"
  fi
}

downloadJDK() {
  majorVersion="$2"
  buildVersion="$3"
  if [[ "$1" == "ga" ]]; then
    urlHash="$5"
    version="$majorVersion+$buildVersion"
    url="https://download.java.net/java/GA/jdk$majorVersion/$urlHash/$buildVersion/GPL/openjdk-${majorVersion}_linux-x64_bin.tar.gz"
  elif [[ "$1" == "ea" ]]; then
    version="$majorVersion-ea+$buildVersion"
    url="https://download.java.net/java/early_access/jdk$majorVersion/$buildVersion/GPL/openjdk-${version}_linux-x64_bin.tar.gz"
  else
    echo "JDK type must be either 'ga' or 'ea'"
    exit 1
  fi

  currentVersion="$(java --version | head -n 2 | tail -n 1)"
  if ! echo "$currentVersion" | grep -q "$version"; then
    jdkHome="/opt/java/openjdk-$version"
    if [[ ! -d "$jdkHome" ]]; then
      checksum="$4"
      curlJDK "$url" "$checksum" "$jdkHome"
    fi
    exportJavaHome "JAVA_HOME" "$jdkHome"
  fi
}

# https://download.java.net/java/GA/jdk23/3c5b90190c68498b986a97f276efd28a/37/GPL/openjdk-23_linux-x64_bin.tar.gz
downloadJDK "$availability" "$version" "$build" "$checksum" "$id"

exit 0
