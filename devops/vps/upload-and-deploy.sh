#!/usr/bin/env bash
set -euo pipefail
umask 077

# GitHub Actions supplies these via env, never by interpolating text into shell code.
: "${VPS_HOST:?Missing VPS_HOST}" "${VPS_USER:?Missing VPS_USER}"
: "${VPS_SSH_KEY:?Missing VPS_SSH_KEY}" "${VPS_KNOWN_HOSTS:?Missing VPS_KNOWN_HOSTS}"
: "${GITHUB_SHA:?Missing GITHUB_SHA}" "${GITHUB_RUN_ID:?Missing GITHUB_RUN_ID}"
: "${GITHUB_RUN_ATTEMPT:?Missing GITHUB_RUN_ATTEMPT}"
port="${VPS_SSH_PORT:-22}"
# Name the bad setting, never its value.
invalid() { echo "Invalid $1; check the repository/environment settings." >&2; exit 2; }
[[ "$VPS_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] || invalid 'VPS_HOST (host name or IP only)'
[[ "$VPS_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || invalid 'VPS_USER (lowercase Linux user)'
[[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || invalid 'VPS_SSH_PORT (1-65535)'
[[ "$GITHUB_SHA" =~ ^[a-f0-9]{40}$ ]] || invalid 'GITHUB_SHA'
[[ "$GITHUB_RUN_ID" =~ ^[0-9]+$ && "$GITHUB_RUN_ATTEMPT" =~ ^[0-9]+$ ]] || invalid 'GITHUB_RUN_ID/GITHUB_RUN_ATTEMPT'
jar="${1:?Usage: upload-and-deploy.sh candidate.jar}"
[[ -f "$jar" ]] || invalid 'JAR path (artifact was not downloaded)'
release="${GITHUB_SHA}-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"
checksum="$(sha256sum "$jar" | cut -d ' ' -f 1)"
temporary="$(mktemp -d)"
trap 'rm -rf -- "$temporary"' EXIT
printf '%s\n' "$VPS_SSH_KEY" > "$temporary/key"
printf '%s\n' "$VPS_KNOWN_HOSTS" > "$temporary/known_hosts"
unset VPS_SSH_KEY VPS_KNOWN_HOSTS
options=(-i "$temporary/key" -o BatchMode=yes -o StrictHostKeyChecking=yes
         -o "UserKnownHostsFile=$temporary/known_hosts" -o ConnectTimeout=20
         -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
target="$VPS_USER@$VPS_HOST"
# Logs older than 30 days and leftover uploaded JARs (named <sha>-<run>-<attempt>.jar) are removed.
ssh "${options[@]}" -p "$port" "$target" 'test -f /opt/ttcs/deploy.sh && mkdir -p /opt/ttcs/incoming /opt/ttcs/logs &&
  find /opt/ttcs/logs -maxdepth 1 -name "*.log" -mtime +30 -delete &&
  find /opt/ttcs/incoming -maxdepth 1 -name "[0-9a-f]*-[0-9]*-[0-9]*.jar" -mmin +1440 -delete'
scp "${options[@]}" -P "$port" "$jar" "$target:/opt/ttcs/incoming/$release.jar"
# The server wrapper ignores SIGHUP and writes locally, so a dropped SSH link
# cannot cut off database recovery. A nonzero result still fails this CI job.
ssh "${options[@]}" -p "$port" "$target" \
  "bash /opt/ttcs/deploy.sh /opt/ttcs/incoming/$release.jar $release $checksum > /opt/ttcs/logs/$release.log 2>&1; result=\$?; rm -f -- /opt/ttcs/incoming/$release.jar; cat /opt/ttcs/logs/$release.log; exit \$result"
