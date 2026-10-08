#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
# A dropped SSH session must not abandon a database restore halfway through.
trap '' HUP
exec 9>.deploy.lock
# Wait for a deployment left running by a dropped CI connection (it ignores HUP).
# The CI deploy job timeout (45 min) must stay above this wait plus one deployment.
flock -w 900 9 || { echo 'FAILED: another deployment is still running after 15 minutes.' >&2; exit 1; }
exec python3 deploy.py "$@"
