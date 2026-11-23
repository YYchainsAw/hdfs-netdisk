#!/usr/bin/env bash
# Runs only the project's isolated MySQL/HDFS test harness; never targets the public site.
set -euo pipefail
umask 077
input_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=${NETDISK_QA_PROJECT_ROOT:-/www/wwwroot/netdisk}
base="$project_root/backend/qa/combined-qa"
size=${NETDISK_PERF_FILE_MIB:-32}
timeout_seconds=${NETDISK_PERF_TIMEOUT_SECONDS:-900}
[[ "$size" =~ ^[0-9]+$ && "$timeout_seconds" =~ ^[0-9]+$ ]] || { echo 'Invalid numeric setting' >&2; exit 2; }
(( size >= 8 && size <= 2048 && size % 8 == 0 )) || { echo 'File size must be an 8 MiB multiple in [8, 2048]' >&2; exit 2; }
(( timeout_seconds >= 120 && timeout_seconds <= 7200 )) || { echo 'Timeout must be 120 to 7200 seconds' >&2; exit 2; }
if (( size > 256 )) && [[ "${NETDISK_PERF_LARGE:-}" != YES ]]; then
  echo 'Files above 256 MiB require explicit NETDISK_PERF_LARGE=YES' >&2; exit 2
fi
if (( size > 256 )) && [[ "${NETDISK_PERF_SKIP_PARALLEL:-}" != YES ]]; then
  echo 'Files above 256 MiB require NETDISK_PERF_SKIP_PARALLEL=YES' >&2; exit 2
fi
[[ -r "$base/run-combined-it.sh" && -r "$input_dir/PerformanceBaselineIT.java" ]] || { echo 'Test files are missing' >&2; exit 2; }
available_kib=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
(( available_kib >= 2000000 )) || { echo 'Less than 2,000,000 KiB RAM available; do not run' >&2; exit 2; }
df_output=$(sudo -u hdfs env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 /opt/hadoop/bin/hdfs dfs -df /netdisk)
hdfs_available=$(printf '%s\n' "$df_output" | awk 'NR==2 {print $4}')
[[ "$hdfs_available" =~ ^[0-9]+$ ]] || { echo 'Cannot determine HDFS capacity' >&2; exit 2; }
# Two copies during publication, three 16 MiB parallel files, and an extra GiB margin.
required=$((10*1024*1024*1024 + 2*(size+48)*1024*1024 + 1024*1024*1024))
(( hdfs_available >= required )) || { echo 'Insufficient HDFS capacity for reserved space and test data' >&2; exit 2; }
tmp=$(mktemp -d /tmp/netdisk-perf-wrapper.XXXXXXXX)
case "$tmp" in /tmp/netdisk-perf-wrapper.????????) ;; *) echo 'Unexpected temporary path' >&2; exit 3 ;; esac
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT
cp -- "$input_dir/PerformanceBaselineIT.java" "$base/DownloadController.java" "$base/DownloadMapper.java" "$tmp/"
python3 - "$base/run-combined-it.sh" "$tmp/run-isolated.sh" <<'PY'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
replacements={
 'cp "$input_dir/CombinedOpsIT.java" "$task_dir/project/src/test/java/xyz/yychainsaw/backend/service/"':
 'cp "$input_dir/PerformanceBaselineIT.java" "$task_dir/project/src/test/java/xyz/yychainsaw/backend/service/"',
 'timeout 180s ./mvnw -B -ntp -o -Dtest=CombinedOpsIT test':
 'timeout --kill-after=30s "${NETDISK_PERF_TIMEOUT_SECONDS:-900}s" ./mvnw -B -ntp -o -Dtest=PerformanceBaselineIT -DargLine=-Xmx512m test',
 'tail -n 25 "$task_dir/test.log"':
 'grep "^PERF " "$task_dir/test.log" || true; tail -n 12 "$task_dir/test.log"',
 "app_pid=''\ncleanup() {":
 '''app_pid=''
container_created=0
for path in "/netdisk/temp/$qa_root" "/netdisk/users/$qa_root"; do
  if sudo -u hdfs env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 /opt/hadoop/bin/hdfs dfs -test -e "$path"; then
    echo 'Random test path already exists; refusing to use or delete it' >&2
    exit 3
  fi
done
printf 'PERF_ISOLATION root=%s container=%s\\n' "$qa_root" "$container"
cleanup() {''',
 '  docker rm -fv "$container" >/dev/null 2>&1 || true':
 '  if [ "$container_created" = 1 ]; then docker rm -fv "$container" >/dev/null 2>&1 || true; fi',
 'docker run -d --name "$container" --network bridge --memory 640m --cpus 1 -p 127.0.0.1::3306 --env-file "$task_dir/docker.env" mysql:8.4 --innodb-buffer-pool-size=64M --performance-schema=OFF >/dev/null':
 'docker run -d --name "$container" --network bridge --memory 640m --cpus 1 -p 127.0.0.1::3306 --env-file "$task_dir/docker.env" mysql:8.4 --innodb-buffer-pool-size=64M --performance-schema=OFF >/dev/null\ncontainer_created=1',
}
for old,new in replacements.items():
 if source.count(old)!=1: raise SystemExit('Isolated harness has changed; review before running')
 source=source.replace(old,new)
pathlib.Path(sys.argv[2]).write_text(source)
PY
export NETDISK_PERF_FILE_MIB="$size" NETDISK_PERF_TIMEOUT_SECONDS="$timeout_seconds"
export NETDISK_PERF_SKIP_PARALLEL="${NETDISK_PERF_SKIP_PARALLEL:-NO}"
export MAVEN_OPTS='-Xms64m -Xmx256m'
printf 'PERF_CONTEXT size_mib=%s available_memory_kib=%s hdfs_available_bytes=%s\n' "$size" "$available_kib" "$hdfs_available"
bash "$tmp/run-isolated.sh"
