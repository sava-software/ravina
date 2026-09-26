#!/usr/bin/env bash
# Runs one ravina soak: builds the harness, starts a local Agave test validator (unless --rpc
# names an endpoint), launches the client JVM under a continuous flight recording with JEP 520
# method timing, waits for it, checks the gates below, and runs the report. Everything the run
# produced, and everything needed to say what produced it, lands in runs/<profile>-<UTC stamp>/.
#
#   ./soak.sh <smoke|hour|control> [--duration SECONDS] [--rate PER_SECOND] [--drain SECONDS]
#             [--rpc URL --ws URL] [--validator PATH] [--out DIR] [--help]
#
# Exit codes: 0 every gate passed, 1 a gate failed, 4 the run could not start.
#
# Written for macOS's /bin/bash 3.2: no associative arrays, no ${x,,}, and no expansion of an
# array that can be empty, which 3.2 reports as unbound under 'set -u'.
set -euo pipefail

# ---------------------------------------------------------------------------- constants

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
MODULE=software.sava.ravina.soak
CLIENT_MAIN=$MODULE/$MODULE.Main
REPORT_MAIN=$MODULE/$MODULE.Report
REPORT_CLASS=software/sava/ravina/soak/Report.class
# The client's settings and their defaults are read from here, the only place they are defined,
# so run.env cannot drift from what the client would have used on its own.
MAIN_SOURCE=$SCRIPT_DIR/src-main/java/software/sava/ravina/soak/Main.java

# Bounds, in seconds. Every one is a stage that can hang.
HEALTH_BOUND=60       # getHealth answers "ok"
FILTER_BOUND=60       # every method-timing entry resolves; entries appear as classes load
WATCHDOG_SLACK=180    # the client is killed after duration + drain + this
STOP_BOUND=60         # SIGTERM, then SIGKILL; TERM is what runs the recording's dumponexit
JFR_TOOL_BOUND=300    # one jfr summary or jfr print
REPORT_BOUND=600
PROGRESS_INTERVAL=60  # a gauge line on the terminal, so an hour run is visibly alive

# The local validator's ports. The websocket is the RPC port + 1.
VALIDATOR_RPC=http://127.0.0.1:8899
VALIDATOR_WS=ws://127.0.0.1:8900
VALIDATOR_RPC_PORT=8899
VALIDATOR_FAUCET_PORT=9900
# The Agave release this harness was verified against: SIMD-0385 v1 transactions are active at
# genesis on its test validator, and ravina builds nothing else.
PINNED_VALIDATOR=$HOME/.local/share/solana/install/releases/4.2.2/solana-release/bin/solana-test-validator

# JEP 520 method timing. The grammar has no wildcards and silently ignores a name that matches
# nothing, which is why gate 2 reads the -Xlog:jfr+methodtrace log instead of trusting the
# filter. An entry resolves only when its class loads, so the filter holds only classes the run
# loads: CourteousCall::call is absent because the RPC path uses only the balanced variant, and
# the websocket entry is added only when the websocket is on, because with it off Main never
# constructs a WebSocketManagerImpl (measured: a control run resolved the other five and not it).
METHOD_TIMING_ENTRIES=(
  software.sava.services.core.remote.call.CourteousBalancedCall::call
  software.sava.services.core.remote.call.UncheckedBalancedCall::get
  software.sava.services.solana.transactions.TxCommitmentMonitorService::processTransactions
  software.sava.services.solana.transactions.TxCommitmentMonitorService::validateResponse
  software.sava.services.solana.epoch.EpochInfoServiceImpl::getAndSetEpochInfo
)
WEBSOCKET_TIMING_ENTRY=software.sava.services.solana.websocket.WebSocketManagerImpl::ensureWebSocket

# SOAK_* names this runner reads that the client does not. SOAK_JAVA_HOME belongs to
# config/generate-jfc.sh and is tolerated so a shell set up for that script can run this one.
RUNNER_KEYS="SOAK_VALIDATOR SOAK_JAVA_HOME"

CLIENT_PID=""
VALIDATOR_PID=""
RUN_DIR=""
GATES=""
SCRATCH=""

# ---------------------------------------------------------------------------- helpers

log() { printf '%s %s\n' "$(date -u '+%H:%M:%SZ')" "$*" >&2; }

cannot_start() {
  printf 'soak.sh: %s\n' "$*" >&2
  [ -z "$GATES" ] || printf 'RESULT NOT STARTED: %s\n' "$*" >> "$GATES"
  exit 4
}

usage() {
  cat <<'USAGE'
usage: ./soak.sh <smoke|hour|control> [options]

profiles
  smoke     600 s at 2 tx/s, websocket on
  hour      3600 s at 2 tx/s, websocket on
  control   600 s at 2 tx/s, websocket off (SOAK_WEBSOCKET=false): every confirmation by polling

options
  --duration SECONDS   submission time; the drain comes after it
  --rate PER_SECOND    transactions submitted per second (decimal)
  --drain SECONDS      how long the client waits for outstanding transactions (default 60)
  --rpc URL --ws URL   use a running endpoint instead of starting a local validator
  --validator PATH     solana-test-validator to start (default: $SOAK_VALIDATOR, then Agave
                       4.2.2 under ~/.local/share/solana, then solana-test-validator on PATH)
  --out DIR            run directory (default runs/<profile>-<UTC yyyymmddThhmmssZ>)
  --help

Any SOAK_* environment variable overrides the profile; a flag overrides both. An unknown SOAK_*
name is refused, because the client ignores it and the run would silently use the default.
Every resolved value is written to <run>/run.env.

exit codes
  0 every gate passed   1 a gate failed   4 the run could not start
USAGE
}

usage_error() {
  printf 'soak.sh: %s\n\n' "$*" >&2
  usage >&2
  exit 4
}

# stop_pid <pid> <label> <bound>: SIGTERM, wait up to the bound, then SIGKILL.
stop_pid() {
  local pid=$1 label=$2 bound=$3 waited=0
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  kill -TERM "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$bound" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "$label (pid $pid) ignored SIGTERM for $bound s, killing it"
    kill -KILL "$pid" 2>/dev/null || true
  fi
}

# run_bounded <bound> <label> <command...>: the command's status, or 124 when it was killed.
# macOS has no timeout(1). Call it from an if, || or $? capture, never bare under set -e.
run_bounded() {
  local bound=$1 label=$2 waited=0 pid
  shift 2
  "$@" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt "$bound" ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "$label did not finish within $bound s, abandoning it"
    kill -TERM "$pid" 2>/dev/null || true
    sleep 1
    kill -KILL "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    return 124
  fi
  wait "$pid"
}

rpc_request() { # <url> <method>
  curl -s -m 2 "$1" -H 'content-type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$2\"}" 2>/dev/null
}

healthy() { # <url>
  local answer
  answer=$(rpc_request "$1" getHealth) || return 1
  case $answer in *'"result":"ok"'*) return 0 ;; *) return 1 ;; esac
}

join_by() { # <separator> <item...>
  local IFS=$1
  shift
  printf '%s' "$*"
}

contains_word() { # <word> <space-separated list>
  case " $2 " in *" $1 "*) return 0 ;; *) return 1 ;; esac
}

# Invoked by the EXIT trap, not by name. Every command tolerates failure: a failing kill here must not
# replace the exit status the run already decided.
# shellcheck disable=SC2329
cleanup() {
  local status=$?
  # A run directory whose gates.txt ends without a RESULT line would read as still running.
  if [ -n "$GATES" ] && ! grep -q '^RESULT ' "$GATES" 2>/dev/null; then
    printf 'RESULT INTERRUPTED: soak.sh exited %s before the gates were checked\n' "$status" >> "$GATES" 2>/dev/null || true
  fi
  stop_pid "$CLIENT_PID" "client" "$STOP_BOUND" || true
  if [ -n "$VALIDATOR_PID" ]; then
    stop_pid "$VALIDATOR_PID" "validator" 30 || true
    wait "$VALIDATOR_PID" 2>/dev/null || true
  fi
  [ -z "$SCRATCH" ] || rm -rf "$SCRATCH" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------- arguments

[ $# -gt 0 ] || usage_error "a profile is required"
case $1 in -h | --help) usage; exit 0 ;; esac
PROFILE=$1
shift
case $PROFILE in
  smoke) profile_duration=600; profile_rate=2; profile_websocket=true ;;
  hour) profile_duration=3600; profile_rate=2; profile_websocket=true ;;
  control) profile_duration=600; profile_rate=2; profile_websocket=false ;;
  *) usage_error "unknown profile '$PROFILE'" ;;
esac

flag_duration=""; flag_rate=""; flag_drain=""; flag_rpc=""; flag_ws=""; flag_validator=""; flag_out=""
while [ $# -gt 0 ]; do
  case $1 in
    -h | --help) usage; exit 0 ;;
    --duration | --rate | --drain | --rpc | --ws | --validator | --out)
      [ $# -ge 2 ] && [ -n "$2" ] || usage_error "$1 needs a value"
      case $1 in
        --duration) flag_duration=$2 ;;
        --rate) flag_rate=$2 ;;
        --drain) flag_drain=$2 ;;
        --rpc) flag_rpc=$2 ;;
        --ws) flag_ws=$2 ;;
        --validator) flag_validator=$2 ;;
        --out) flag_out=$2 ;;
      esac
      shift 2
      ;;
    *) usage_error "unknown argument '$1'" ;;
  esac
done
if [ -n "$flag_rpc" ] && [ -z "$flag_ws" ]; then usage_error "--rpc needs --ws"; fi
if [ -n "$flag_ws" ] && [ -z "$flag_rpc" ]; then usage_error "--ws needs --rpc"; fi

# ---------------------------------------------------------------------------- settings
# Everything that can refuse the run is checked here, before the build spends a minute.

[ -f "$MAIN_SOURCE" ] || cannot_start "no client source at $MAIN_SOURCE"
MAIN_DEFAULTS=$(grep -o 'setting("SOAK_[A-Z0-9_]*", "[^"]*")' "$MAIN_SOURCE" \
  | sed -E 's/^setting\("([A-Z0-9_]+)", "([^"]*)"\)$/\1=\2/') || true
[ -n "$MAIN_DEFAULTS" ] || cannot_start "found no setting(\"SOAK_*\", \"<default>\") call in $MAIN_SOURCE"
CLIENT_KEYS=$(printf '%s\n' "$MAIN_DEFAULTS" | cut -d= -f1 | tr '\n' ' ')

main_default() { # <key>: Main's default, or status 1 when Main does not read the key
  printf '%s\n' "$MAIN_DEFAULTS" \
    | awk -F= -v k="$1" '$1 == k { sub(/^[^=]*=/, ""); print; found = 1 } END { exit !found }'
}

# The client silently ignores a name it does not read, so a typo such as SOAK_DURATION=60 would
# run the profile's length without a word.
unknown=""
for key in $(compgen -e | grep '^SOAK_' || true); do
  contains_word "$key" "$CLIENT_KEYS" || contains_word "$key" "$RUNNER_KEYS" || unknown="$unknown $key"
done
[ -z "$unknown" ] || cannot_start "unknown SOAK_* environment variable(s):$unknown (the client reads: ${CLIENT_KEYS% })"

# resolve <key> <flag value> <profile value>: flag, then environment, then profile, then Main's
# default. The result is exported, because the client reads nothing but its environment.
resolve() {
  local key=$1 flag=$2 fallback=$3 value
  main_default "$key" > /dev/null || cannot_start "Main.java does not read $key; soak.sh and Main disagree"
  if [ -n "$flag" ]; then
    value=$flag
  elif [ -n "${!key:-}" ]; then
    value=${!key}
  elif [ -n "$fallback" ]; then
    value=$fallback
  else
    value=$(main_default "$key")
  fi
  printf -v "$key" '%s' "$value"
  # shellcheck disable=SC2163 # the variable named by $key, not 'key'
  export "$key"
}

# A caller-named endpoint, by flag or by environment, means no local validator: starting one
# would measure a validator the client never talks to.
EXTERNAL=0
if [ -n "$flag_rpc" ] || [ -n "${SOAK_RPC:-}" ]; then
  EXTERNAL=1
  [ -n "$flag_rpc" ] || [ -n "${SOAK_WS:-}" ] || cannot_start "SOAK_RPC names an endpoint; set SOAK_WS as well"
  [ -z "$flag_validator" ] || usage_error "--validator starts a local validator; it cannot be combined with --rpc"
  resolve SOAK_RPC "$flag_rpc" ""
  resolve SOAK_WS "$flag_ws" ""
else
  # A websocket URL without its RPC would point the client at two different validators.
  [ -z "${SOAK_WS:-}" ] || cannot_start "SOAK_WS is set without SOAK_RPC; set both to use a running endpoint"
  resolve SOAK_RPC "" "$VALIDATOR_RPC"
  resolve SOAK_WS "" "$VALIDATOR_WS"
fi
resolve SOAK_DURATION_SECONDS "$flag_duration" "$profile_duration"
resolve SOAK_RATE_PER_SECOND "$flag_rate" "$profile_rate"
resolve SOAK_DRAIN_SECONDS "$flag_drain" ""
resolve SOAK_WEBSOCKET "" "$profile_websocket"
for key in $CLIENT_KEYS; do
  case $key in
    SOAK_RPC | SOAK_WS | SOAK_DURATION_SECONDS | SOAK_RATE_PER_SECOND | SOAK_DRAIN_SECONDS | SOAK_WEBSOCKET) ;;
    SOAK_OUT) ;; # resolved below, once the stamp is known
    *) resolve "$key" "" "" ;;
  esac
done

[[ $SOAK_DURATION_SECONDS =~ ^[0-9]+$ ]] && [ "$SOAK_DURATION_SECONDS" -gt 0 ] \
  || cannot_start "SOAK_DURATION_SECONDS must be a positive whole number, not '$SOAK_DURATION_SECONDS'"
[[ $SOAK_DRAIN_SECONDS =~ ^[0-9]+$ ]] \
  || cannot_start "SOAK_DRAIN_SECONDS must be a whole number, not '$SOAK_DRAIN_SECONDS'"
# Main computes the submit period as 1e9 / rate; zero would schedule nothing at all.
if ! [[ $SOAK_RATE_PER_SECOND =~ ^[0-9]+([.][0-9]+)?$ ]] || [[ $SOAK_RATE_PER_SECOND =~ ^0*([.]0*)?$ ]]; then
  cannot_start "SOAK_RATE_PER_SECOND must be a positive decimal, not '$SOAK_RATE_PER_SECOND'"
fi
# Main's Boolean.parseBoolean reads anything but "true" as false, so a typo would turn the
# websocket off; only the two words are accepted, in any case, and gate 5 keys on the result.
SOAK_WEBSOCKET=$(printf '%s' "$SOAK_WEBSOCKET" | tr '[:upper:]' '[:lower:]')
case $SOAK_WEBSOCKET in true | false) ;; *) cannot_start "SOAK_WEBSOCKET must be true or false, not '$SOAK_WEBSOCKET'" ;; esac
[ "$SOAK_WEBSOCKET" = false ] || METHOD_TIMING_ENTRIES+=("$WEBSOCKET_TIMING_ENTRY")
case $SOAK_RPC in http://* | https://*) ;; *) cannot_start "SOAK_RPC must be an http(s) URL, not '$SOAK_RPC'" ;; esac
case $SOAK_WS in ws://* | wss://*) ;; *) cannot_start "SOAK_WS must be a ws(s) URL, not '$SOAK_WS'" ;; esac

STAMP=$(date -u +%Y%m%dT%H%M%SZ)
resolve SOAK_OUT "$flag_out" "$SCRIPT_DIR/runs/$PROFILE-$STAMP"
case $SOAK_OUT in /*) ;; *) SOAK_OUT=$PWD/$SOAK_OUT ;; esac
# The run directory is spliced into -XX and -Xlog option strings, whose parsers split on these.
case $SOAK_OUT in *[,:\;=\ ]*) cannot_start "the run directory must not contain ',', ':', ';', '=' or a space: $SOAK_OUT" ;; esac
if [ -e "$SOAK_OUT" ] && [ -n "$(ls -A "$SOAK_OUT" 2>/dev/null)" ]; then
  cannot_start "the run directory already exists and is not empty: $SOAK_OUT"
fi

if [ "$EXTERNAL" = 0 ]; then
  if [ -n "$flag_validator" ]; then
    SOAK_VALIDATOR=$flag_validator
  elif [ -n "${SOAK_VALIDATOR:-}" ]; then
    :
  elif [ -x "$PINNED_VALIDATOR" ]; then
    SOAK_VALIDATOR=$PINNED_VALIDATOR
  else
    SOAK_VALIDATOR=$(command -v solana-test-validator || true)
  fi
  [ -n "${SOAK_VALIDATOR:-}" ] && [ -x "$SOAK_VALIDATOR" ] \
    || cannot_start "no solana-test-validator (tried --validator, SOAK_VALIDATOR, $PINNED_VALIDATOR and PATH)"
  export SOAK_VALIDATOR
  # A second validator cannot bind the port, and the health check would then pass against the
  # first one: the run would measure a ledger it did not start.
  if healthy "$VALIDATOR_RPC"; then
    cannot_start "a validator already answers on $VALIDATOR_RPC; reuse it with --rpc $VALIDATOR_RPC --ws $VALIDATOR_WS, or stop it"
  fi
else
  SOAK_VALIDATOR=""
fi

# ---------------------------------------------------------------------------- build

log "building the module path (../gradlew --no-daemon -q soakModulePath)"
(cd "$SCRIPT_DIR" && ../gradlew --no-daemon -q soakModulePath) || cannot_start "the harness build failed"
JAVA=$(head -n 1 "$SCRIPT_DIR/build/soak/java.txt" 2>/dev/null || true)
MP=$(head -n 1 "$SCRIPT_DIR/build/soak/module-path.txt" 2>/dev/null || true)
[ -n "$JAVA" ] && [ -x "$JAVA" ] || cannot_start "no launcher in build/soak/java.txt ('$JAVA')"
[ -n "$MP" ] || cannot_start "build/soak/module-path.txt is empty"
# The JDK that runs the client also reads its recording: jfr's parser follows the JVM's format.
JAVA_HOME_USED=$(cd "$(dirname "$JAVA")/.." && pwd)
JFR=$(dirname "$JAVA")/jfr
[ -x "$JFR" ] || cannot_start "no jfr tool next to the launcher: $JFR"
SOAK_JAR=""
IFS=: read -r -a mp_entries <<< "$MP"
for entry in "${mp_entries[@]}"; do
  case $entry in */ravina-soak*.jar) SOAK_JAR=$entry ;; esac
done

# ---------------------------------------------------------------------------- run directory

RUN_DIR=$SOAK_OUT
mkdir -p "$RUN_DIR/config" "$RUN_DIR/logs" "$RUN_DIR/jfr"
cp "$SCRIPT_DIR/config/ravina-soak.jfc" "$SCRIPT_DIR/config/soak-logging.properties" "$RUN_DIR/config/"
SCRATCH=$(mktemp -d "$RUN_DIR/.scratch.XXXXXX")
GATES=$RUN_DIR/gates.txt
: > "$GATES"

{
  printf '# Resolved by soak.sh, profile %s, %s. Flag > environment > profile > Main.java default.\n' "$PROFILE" "$STAMP"
  # shellcheck disable=SC2086 # CLIENT_KEYS is a space-separated list, split on purpose
  for key in $( (printf '%s\n' $CLIENT_KEYS; printf '%s\n' SOAK_VALIDATOR) | sort -u); do
    printf '%s=%q\n' "$key" "${!key:-}"
  done
} > "$RUN_DIR/run.env"

METHOD_TIMING=$(join_by ';' "${METHOD_TIMING_ENTRIES[@]}")
CLIENT_CMD=(
  "$JAVA"
  -XX:+UseG1GC -Xms512m -Xmx512m
  # stackdepth 96: the monitor's and the websocket's stacks pass through CompletableFuture and
  # JDK HTTP client frames, which the default 64 truncates.
  "-XX:FlightRecorderOptions:repository=$RUN_DIR/jfr-repo,stackdepth=96"
  # maxsize must be explicit or the JVM silently takes 250 MB; filename requires dumponexit.
  "-XX:StartFlightRecording:name=soak,settings=$RUN_DIR/config/ravina-soak.jfc,disk=true,maxsize=256m,maxage=2h,dumponexit=true,filename=$RUN_DIR/jfr/soak.jfr,method-timing=$METHOD_TIMING"
  # To a file: it is startup chatter, and the only evidence that each timing entry took.
  "-Xlog:jfr+methodtrace=debug:file=$RUN_DIR/logs/jfr-methodtrace.log"
  "-Djava.util.logging.config.file=$RUN_DIR/config/soak-logging.properties"
  -p "$MP"
  -m "$CLIENT_MAIN"
)

# ---------------------------------------------------------------------------- validator

if [ "$EXTERNAL" = 0 ]; then
  # The ledger lives under build/, not the run directory: a validator writes tens of megabytes
  # a minute of ledger and account files that no report reads, and --reset clears it on the
  # next start. The run keeps the validator's log.
  LEDGER_DIR=$SCRIPT_DIR/build/ledger
  mkdir -p "$LEDGER_DIR"
  log "starting $SOAK_VALIDATOR (ledger $LEDGER_DIR, reset)"
  (cd "$RUN_DIR" && exec "$SOAK_VALIDATOR" --ledger "$LEDGER_DIR" --reset --quiet \
    --rpc-port "$VALIDATOR_RPC_PORT" --faucet-port "$VALIDATOR_FAUCET_PORT") \
    > "$RUN_DIR/logs/validator.log" 2>&1 < /dev/null &
  VALIDATOR_PID=$!
fi
waited=0
until healthy "$SOAK_RPC"; do
  if [ -n "$VALIDATOR_PID" ] && ! kill -0 "$VALIDATOR_PID" 2>/dev/null; then
    tail -n 20 "$RUN_DIR/logs/validator.log" "$LEDGER_DIR/validator.log" 2>/dev/null >&2 || true
    cannot_start "the validator exited before it answered getHealth; see $RUN_DIR/logs/validator.log"
  fi
  [ "$waited" -lt "$HEALTH_BOUND" ] || cannot_start "$SOAK_RPC did not answer getHealth \"ok\" within $HEALTH_BOUND s"
  sleep 1
  waited=$((waited + 1))
done
log "$SOAK_RPC is healthy"

# ---------------------------------------------------------------------------- run.txt

git_rev=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
git_changes=$(git -C "$ROOT" status --porcelain 2>/dev/null || true)
if [ -n "$git_changes" ]; then git_dirty=true; else git_dirty=false; fi
{
  echo "profile: $PROFILE"
  echo "started: $STAMP"
  echo "git: $git_rev dirty=$git_dirty"
  if [ -n "$git_changes" ]; then
    printf '%s\n' "$git_changes" | sed 's/^/  /'
  fi
  echo "jdk: $JAVA_HOME_USED"
  echo "java -version:"
  "$JAVA" -version 2>&1 | sed 's/^/  /'
  if [ "$EXTERNAL" = 0 ]; then
    echo "validator: $SOAK_VALIDATOR"
    echo "validator --version: $("$SOAK_VALIDATOR" --version 2>&1 || true)"
  else
    echo "validator: external, not started by this run"
  fi
  echo "rpc: $SOAK_RPC"
  echo "rpc getVersion: $(rpc_request "$SOAK_RPC" getVersion || true)"
  echo "ws: $SOAK_WS"
  echo "method-timing entries:"
  printf '  %s\n' "${METHOD_TIMING_ENTRIES[@]}"
  echo "launch (cwd $RUN_DIR, environment as run.env):"
  printf '  '
  printf '%q ' "${CLIENT_CMD[@]}"
  printf '\n'
} > "$RUN_DIR/run.txt"

# ---------------------------------------------------------------------------- launch

log "launching the client: $PROFILE, ${SOAK_DURATION_SECONDS} s at ${SOAK_RATE_PER_SECOND} tx/s + ${SOAK_DRAIN_SECONDS} s drain, websocket=$SOAK_WEBSOCKET"
log "run directory: $RUN_DIR"
(cd "$RUN_DIR" && exec "${CLIENT_CMD[@]}") > "$RUN_DIR/logs/client.log" 2>&1 < /dev/null &
CLIENT_PID=$!
LAUNCHED_AT=$SECONDS

TRACE_LOG=$RUN_DIR/logs/jfr-methodtrace.log
FILTER_INSTALLED=0
FILTER_RESOLVED=0
FILTER_MISSING=""
check_filter() {
  local entry
  FILTER_INSTALLED=0
  FILTER_RESOLVED=0
  FILTER_MISSING=""
  if grep -q 'New filter installed' "$TRACE_LOG" 2>/dev/null; then
    FILTER_INSTALLED=1
  fi
  for entry in "${METHOD_TIMING_ENTRIES[@]}"; do
    # The trailing space keeps one entry from matching a longer name it prefixes. Entries are
    # counted, not lines: an overloaded method writes one line per overload.
    if grep -qF "Timing entry added for $entry " "$TRACE_LOG" 2>/dev/null; then
      FILTER_RESOLVED=$((FILTER_RESOLVED + 1))
    else
      FILTER_MISSING="$FILTER_MISSING $entry"
    fi
  done
}

LAUNCH_WARNING=""
check_launch_warnings() {
  local first
  first=$(head -n 200 "$RUN_DIR/logs/client.log" 2>/dev/null || true)
  # StartFlightRecording only warns about an option it does not apply; the JVM starts anyway.
  LAUNCH_WARNING=$(grep -F -m 1 '[warning][jfr,' <<< "$first" || true)
}

# Launch gates 1 and 2 are checked while the client runs: a filter that did not take or a
# recording option the JVM ignored makes the run a FAIL whatever follows, so the client is
# stopped rather than left to spend an hour. The recorded verdicts are taken again at the end.
waited=0
while :; do
  check_filter
  [ "$FILTER_INSTALLED" = 0 ] || [ "$FILTER_RESOLVED" -lt "${#METHOD_TIMING_ENTRIES[@]}" ] || break
  [ "$waited" -lt "$FILTER_BOUND" ] || break
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
  waited=$((waited + 1))
done
check_launch_warnings
EARLY_STOP=""
if [ "$FILTER_INSTALLED" = 0 ] || [ "$FILTER_RESOLVED" -lt "${#METHOD_TIMING_ENTRIES[@]}" ]; then
  EARLY_STOP="method timing resolved $FILTER_RESOLVED of ${#METHOD_TIMING_ENTRIES[@]} entries within $FILTER_BOUND s"
elif [ -n "$LAUNCH_WARNING" ]; then
  EARLY_STOP="the JVM warned about the recording options: $LAUNCH_WARNING"
fi
if [ -n "$EARLY_STOP" ] && kill -0 "$CLIENT_PID" 2>/dev/null; then
  log "stopping the client: $EARLY_STOP"
  stop_pid "$CLIENT_PID" "client" "$STOP_BOUND"
elif [ -n "$EARLY_STOP" ]; then
  EARLY_STOP=""
fi

# ---------------------------------------------------------------------------- wait

WATCHDOG_BOUND=$((SOAK_DURATION_SECONDS + SOAK_DRAIN_SECONDS + WATCHDOG_SLACK))
WATCHDOG_FIRED=0
VALIDATOR_DIED=0
next_progress=$((LAUNCHED_AT + PROGRESS_INTERVAL))
while kill -0 "$CLIENT_PID" 2>/dev/null; do
  if [ $((SECONDS - LAUNCHED_AT)) -ge "$WATCHDOG_BOUND" ]; then
    WATCHDOG_FIRED=1
    log "watchdog: the client outlived duration + drain + $WATCHDOG_SLACK s ($WATCHDOG_BOUND s), stopping it"
    stop_pid "$CLIENT_PID" "client" "$STOP_BOUND"
    break
  fi
  if [ -n "$VALIDATOR_PID" ] && [ "$VALIDATOR_DIED" = 0 ] && ! kill -0 "$VALIDATOR_PID" 2>/dev/null; then
    VALIDATOR_DIED=1
    log "the validator exited during the run; see $RUN_DIR/logs/validator.log"
  fi
  if [ "$SECONDS" -ge "$next_progress" ]; then
    next_progress=$((next_progress + PROGRESS_INTERVAL))
    # gauge.csv columns: epochMillis,submitted,settled,pending,dropped,inFlightRpc,rpcCapacity,webSocket,...
    row=$(tail -n 1 "$RUN_DIR/gauge.csv" 2>/dev/null || true)
    case $row in
      '' | epochMillis*) log "running $((SECONDS - LAUNCHED_AT)) s, no gauge row yet" ;;
      *) log "running $((SECONDS - LAUNCHED_AT)) s: $(awk -F, '{ printf "submitted=%s settled=%s pending=%s dropped=%s rpcCapacity=%s webSocket=%s", $2, $3, $4, $5, $7, $8 }' <<< "$row")" ;;
    esac
  fi
  sleep 1
done
set +e
wait "$CLIENT_PID"
CLIENT_EXIT=$?
set -e
CLIENT_PID=""
log "client exited with status $CLIENT_EXIT after $((SECONDS - LAUNCHED_AT)) s"

# The validator is stopped before the gates: the jfr tool and the report need neither it nor
# the CPU it takes. The EXIT trap covers every earlier way out.
if [ -n "$VALIDATOR_PID" ]; then
  stop_pid "$VALIDATOR_PID" "validator" 30
  wait "$VALIDATOR_PID" 2>/dev/null || true
  VALIDATOR_PID=""
fi

# ---------------------------------------------------------------------------- gates

FAILED=""
gate() { # <number> <name> <PASS|FAIL> <detail>
  local line
  line=$(printf '%s gate %s %-20s %s' "$3" "$1" "$2" "$4")
  printf '%s\n' "$line" >> "$GATES"
  printf '%s\n' "$line"
  [ "$3" = PASS ] || FAILED="$FAILED $1"
}
note() {
  printf 'note %s\n' "$*" >> "$GATES"
  printf 'note %s\n' "$*"
}

CLIENT_LOG=$RUN_DIR/logs/client.log
JFR_FILE=$RUN_DIR/jfr/soak.jfr

# 1. The JVM applied every recording option it was given.
check_launch_warnings
if [ -z "$LAUNCH_WARNING" ]; then
  gate 1 jfr-launch-warnings PASS "no '[warning][jfr,' in the first 200 lines of logs/client.log"
else
  gate 1 jfr-launch-warnings FAIL "$LAUNCH_WARNING"
fi

# 2. The method tracer initialised and every timing entry resolved.
check_filter
if [ "$FILTER_INSTALLED" = 1 ] && [ "$FILTER_RESOLVED" -eq "${#METHOD_TIMING_ENTRIES[@]}" ]; then
  gate 2 method-timing PASS "filter installed; $FILTER_RESOLVED/${#METHOD_TIMING_ENTRIES[@]} entries resolved"
elif [ "$FILTER_INSTALLED" = 0 ]; then
  gate 2 method-timing FAIL "no 'New filter installed' in logs/jfr-methodtrace.log: the method tracer never initialised"
else
  gate 2 method-timing FAIL "$FILTER_RESOLVED/${#METHOD_TIMING_ENTRIES[@]} entries resolved; missing:$FILTER_MISSING"
fi

# 3. The privacy and per-chunk-noise events the .jfc turns off recorded nothing: environment
# variables, -D properties and the machine's process list must never reach a file people attach.
if [ ! -s "$JFR_FILE" ]; then
  gate 3 privacy-events FAIL "no recording at jfr/soak.jfr"
else
  summary_status=0
  run_bounded "$JFR_TOOL_BOUND" "jfr summary" "$JFR" summary "$JFR_FILE" > "$RUN_DIR/jfr-summary.txt" 2>&1 || summary_status=$?
  if [ "$summary_status" != 0 ]; then
    gate 3 privacy-events FAIL "jfr summary exited $summary_status; see jfr-summary.txt"
  else
    counts=""
    leaked=""
    for event in jdk.InitialEnvironmentVariable jdk.InitialSystemProperty jdk.SystemProcess jdk.NativeLibrary; do
      count=$(awk -v n="$event" '$1 == n { print $2; exit }' "$RUN_DIR/jfr-summary.txt")
      counts="$counts $event=${count:-absent}"
      case ${count:-0} in 0) ;; *) leaked="$leaked $event" ;; esac
    done
    if [ -z "$leaked" ]; then
      gate 3 privacy-events PASS "${counts# }"
    else
      gate 3 privacy-events FAIL "recorded:$leaked (${counts# })"
    fi
  fi
fi

# 4. One Transaction event per submission, and at least one submission: the workload commits
# exactly one per transaction whatever happened, so a shortfall is a lost event or a lost ring.
finished=$(grep -o 'Run finished: .*' "$CLIENT_LOG" 2>/dev/null | tail -n 1 || true)
submitted=$(sed -n 's/^Run finished: submitted=\([0-9]*\).*/\1/p' <<< "$finished")
TX_FILE=$SCRATCH/transactions.txt
tx_status=0
if [ -s "$JFR_FILE" ]; then
  run_bounded "$JFR_TOOL_BOUND" "jfr print" "$JFR" print --events ravina.soak.Transaction "$JFR_FILE" > "$TX_FILE" 2> "$SCRATCH/print.err" || tx_status=$?
else
  tx_status=-1
fi
tx_count=$(grep -c '^ravina.soak.Transaction {' "$TX_FILE" 2>/dev/null || true)
tx_count=${tx_count:-0}
if [ "$tx_status" != 0 ]; then
  gate 4 transaction-count FAIL "could not read Transaction events from jfr/soak.jfr (status $tx_status)"
elif [ -z "$submitted" ]; then
  gate 4 transaction-count FAIL "no 'Run finished: submitted=N' line in logs/client.log; $tx_count Transaction events"
elif [ "$submitted" -eq 0 ]; then
  gate 4 transaction-count FAIL "the run submitted nothing"
elif [ "$tx_count" -eq "$submitted" ]; then
  gate 4 transaction-count PASS "$tx_count ravina.soak.Transaction events = $submitted submitted"
else
  # Both Run events present means the ring kept the whole run; one missing says maxsize or
  # maxage dropped the start, which explains a shortfall without any event being lost.
  runs=$(awk '$1 == "ravina.soak.Run" { print $2; exit }' "$RUN_DIR/jfr-summary.txt" 2>/dev/null || true)
  gate 4 transaction-count FAIL "$tx_count ravina.soak.Transaction events != $submitted submitted (ravina.soak.Run events: ${runs:-unknown} of 2)"
fi

# 5. The route matches the websocket setting: with it off every transaction settled by polling
# (NO_WEBSOCKET), with it on at least one settled by a signature notification.
routes=$(sed -n 's/^  route = "\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$TX_FILE" 2>/dev/null | sort | uniq -c \
  | awk '{ printf "%s%s=%s", sep, $2, $1; sep = " " }' || true)
outcomes=$(sed -n 's/^  outcome = "\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$TX_FILE" 2>/dev/null | sort | uniq -c \
  | awk '{ printf "%s%s=%s", sep, $2, $1; sep = " " }' || true)
websocket_routes=$(grep -c '^  route = "WEBSOCKET"$' "$TX_FILE" 2>/dev/null || true)
other_routes=$(grep '^  route = ' "$TX_FILE" 2>/dev/null | grep -vc '^  route = "NO_WEBSOCKET"$' || true)
if [ "$tx_count" -eq 0 ]; then
  gate 5 settle-routes FAIL "no Transaction events to read routes from"
elif [ "$SOAK_WEBSOCKET" = false ]; then
  if [ "${other_routes:-0}" -eq 0 ]; then
    gate 5 settle-routes PASS "websocket off; every route NO_WEBSOCKET ($routes)"
  else
    gate 5 settle-routes FAIL "websocket off, but $other_routes routes are not NO_WEBSOCKET ($routes)"
  fi
else
  if [ "${websocket_routes:-0}" -gt 0 ]; then
    gate 5 settle-routes PASS "websocket on; $websocket_routes settled by notification ($routes)"
  else
    gate 5 settle-routes FAIL "websocket on, but no transaction settled by notification ($routes)"
  fi
fi

# 6. The client reached its own end. Main calls System.exit(0) after the drain, so this
# distinguishes a run that finished from one that crashed, hung or was stopped.
if [ "$CLIENT_EXIT" -eq 0 ]; then
  gate 6 client-exit PASS "exit 0"
elif [ "$WATCHDOG_FIRED" = 1 ]; then
  gate 6 client-exit FAIL "exit $CLIENT_EXIT: the watchdog stopped it after $WATCHDOG_BOUND s"
elif [ -n "$EARLY_STOP" ]; then
  gate 6 client-exit FAIL "exit $CLIENT_EXIT: stopped after a launch gate failed ($EARLY_STOP)"
else
  gate 6 client-exit FAIL "exit $CLIENT_EXIT; see logs/client.log"
fi
note "outcomes: ${outcomes:-none}"
note "${finished:-no 'Run finished' line in logs/client.log}"
[ "$VALIDATOR_DIED" = 0 ] || note "the validator exited during the run; see logs/validator.log"

# ---------------------------------------------------------------------------- report

# Report.java is written separately from this runner; until it is in the jar the run is judged
# on the gates alone. Once it exists, a report that fails or writes nothing fails the run.
report_line() { # <verdict> <detail>
  local line
  line=$(printf '%s %-27s %s' "$1" report "$2")
  printf '%s\n' "$line" >> "$GATES"
  printf '%s\n' "$line"
}
if [ ! -s "$JFR_FILE" ]; then
  report_line SKIP "report skipped: no recording to report on"
elif [ -z "$SOAK_JAR" ] || ! unzip -l "$SOAK_JAR" "$REPORT_CLASS" > /dev/null 2>&1; then
  report_line SKIP "report skipped: Report class not built"
else
  report_status=0
  run_bounded "$REPORT_BOUND" "report" "$JAVA" -p "$MP" -m "$REPORT_MAIN" "$JFR_FILE" "$RUN_DIR/summary.md" \
    > "$RUN_DIR/logs/report.log" 2>&1 < /dev/null || report_status=$?
  if [ "$report_status" = 0 ] && [ -s "$RUN_DIR/summary.md" ]; then
    report_line PASS "summary.md written"
  else
    report_line FAIL "exit $report_status$([ -s "$RUN_DIR/summary.md" ] || printf ', no summary.md'); see logs/report.log"
    FAILED="$FAILED report"
  fi
fi

if [ -z "$FAILED" ]; then
  printf 'RESULT PASS %s\n' "$RUN_DIR" | tee -a "$GATES"
  exit 0
fi
printf 'RESULT FAIL (failed:%s) %s\n' "$FAILED" "$RUN_DIR" | tee -a "$GATES"
exit 1
