#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
[[ ! -f runtime/last-success.jar ]] || { echo 'Already installed. Use deploy.sh or CI for updates.' >&2; exit 1; }
sha256sum --check --quiet SHA256SUMS
if [[ ! -f .env ]]; then
  python3 configure.py
fi
docker compose --env-file .env config --quiet
docker compose --env-file .env pull database web
docker compose --env-file .env build backend
docker compose --env-file .env up -d --wait --wait-timeout 90 database
python3 verify_restore.py
checksum="$(sha256sum incoming/initial.jar | cut -d ' ' -f 1)"
bash deploy.sh incoming/initial.jar initial "$checksum"
docker compose ps
echo 'Next: open https://internal-hire.com and test login; read LOGIN.txt only on this VPS.'
