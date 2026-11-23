#!/usr/bin/env bash
set -euo pipefail
umask 077
input_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=${NETDISK_QA_PROJECT_ROOT:-/www/wwwroot/netdisk}
backend_src=${NETDISK_QA_BACKEND_SRC:-"$project_root/backend"}
[ -f "$backend_src/pom.xml" ] && [ -x "$backend_src/mvnw" ] || { echo 'Backend source or wrapper missing' >&2; exit 2; }
[ -x /www/server/nginx/sbin/nginx ] || { echo 'Baota nginx executable missing' >&2; exit 2; }
task_dir=$(mktemp -d /tmp/netdisk-proxy-qa-src.XXXXXXXX)
[[ "$task_dir" =~ ^/tmp/netdisk-proxy-qa-src\.[A-Za-z0-9]{8}$ ]] || exit 2
cleanup() { rm -rf -- "$task_dir"; }
trap cleanup EXIT
mkdir -p "$task_dir/backend" "$task_dir/empty-config"
tar -C "$backend_src" --exclude=target --exclude=.git -cf - . | tar -xf - -C "$task_dir/backend"
install -m 0644 "$input_dir/ProxyForwardingIT.java" "$task_dir/backend/src/test/java/xyz/yychainsaw/backend/security/ProxyForwardingIT.java"
cd "$task_dir/backend"
export NETDISK_PROXY_QA=true NETDISK_DB_INTEGRATION_TEST=false ACCOUNT_TEST_DB_URL=''
export SPRING_CONFIG_IMPORT="optional:configtree:$task_dir/empty-config/"
export SPRING_PROFILES_ACTIVE=localdev
timeout 180s ./mvnw -B -ntp -o -Dtest=ProxyForwardingIT test > "$task_dir/test.log" 2>&1 || { tail -n 80 "$task_dir/test.log"; exit 1; }
tail -n 20 "$task_dir/test.log"
