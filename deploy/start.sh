#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
[ "$(id -u)" = 0 ] || { echo 'Run as root to set secret ownership for the container UIDs.' >&2; exit 2; }
command -v docker >/dev/null && command -v openssl >/dev/null && command -v curl >/dev/null || { echo 'Docker, openssl and curl are required.' >&2; exit 2; }
project=${NETDISK_COMPOSE_PROJECT:-netdisk-course}
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]{1,40}$ ]] || { echo 'Invalid Compose project name.' >&2; exit 2; }
port=${NETDISK_PORT:-18090}
[[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port >= 1024 && port <= 65535 )) || { echo 'Invalid local web port.' >&2; exit 2; }
export NETDISK_PORT="$port"
compose=(docker compose -p "$project" -f compose.yaml)
existing=0
for suffix in mysql_data hdfs_name hdfs_data; do
  if docker volume inspect "${project}_${suffix}" >/dev/null 2>&1; then existing=$((existing+1)); fi
done
if (( existing != 0 && existing != 3 )); then
  echo 'Only part of the database/HDFS volumes exist; refusing automatic initialization. Inspect and restore the existing data.' >&2
  exit 3
fi
umask 077
mkdir -p secrets
if (( existing == 0 )); then
  [ ! -e secrets/db_app ] && [ ! -e secrets/db_root ] || { echo 'Secrets already exist while data volumes are absent; refusing to initialize.' >&2; exit 3; }
  openssl rand -hex 32 > secrets/db_app
  openssl rand -hex 32 > secrets/db_root
elif [ ! -s secrets/db_app ] || [ ! -s secrets/db_root ]; then
  echo 'Existing data volumes require the original secret files; refusing to generate replacements.' >&2; exit 3
fi
chown root:10001 secrets/db_app
chmod 0640 secrets/db_app
chown root:root secrets/db_root
chmod 0400 secrets/db_root
if (( existing == 0 )); then
  echo 'Initializing new isolated HDFS volumes.'
  "${compose[@]}" --profile init run --rm --no-deps -e NETDISK_INITIAL_FORMAT=YES hdfs-init
fi
"${compose[@]}" up -d --build mysql hdfs backend frontend
for i in $(seq 1 90); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$port/api/v1/auth/csrf" >/dev/null 2>&1; then
    echo "Services are responding at 127.0.0.1:$port. Configure a trusted HTTPS reverse proxy before use."
    exit 0
  fi
  sleep 2
done
echo 'Backend did not become ready; inspect docker compose logs. Data volumes are retained.' >&2
exit 4
