#!/usr/bin/env bash
set -euo pipefail
umask 077
input_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_root=${NETDISK_QA_PROJECT_ROOT:-/www/wwwroot/netdisk}
backend_src=${NETDISK_QA_BACKEND_SRC:-"$project_root/backend"}
multipart_schema=${NETDISK_QA_SCHEMA:-"$project_root/docs/design/api/multipart-schema.sql"}
available_kib=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
if [ "$available_kib" -lt 1400000 ]; then echo 'Insufficient available memory'; exit 2; fi
root_token=$(openssl rand -hex 16)
qa_root="test-$root_token"
[[ "$qa_root" =~ ^test-[0-9a-f]{32}$ ]] || exit 3
task_dir=$(mktemp -d /tmp/netdisk-multipart-it.XXXXXX)
case "$task_dir" in /tmp/netdisk-multipart-it.??????) ;; *) exit 3 ;; esac
container="netdisk-multipart-it-${task_dir##*.}"
app_pid=''
cleanup() {
  if [ -n "$app_pid" ]; then kill "$app_pid" >/dev/null 2>&1 || true; wait "$app_pid" 2>/dev/null || true; fi
  docker rm -fv "$container" >/dev/null 2>&1 || true
  rm -f -- "/tmp/netdisk-fileops-${qa_root}.lock"
  local path
  for path in "/netdisk/temp/$qa_root" "/netdisk/users/$qa_root"; do
    sudo -u hdfs env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 /opt/hadoop/bin/hdfs dfs -rm -r -skipTrash "$path" >/dev/null 2>&1 || true
  done
  rm -rf -- "$task_dir"
}
trap cleanup EXIT
qa_password=$(openssl rand -hex 32)
printf 'MYSQL_ROOT_PASSWORD=%s\nMYSQL_ROOT_HOST=%%\nMYSQL_DATABASE=netdisk_qa\n' "$qa_password" > "$task_dir/docker.env"
docker run -d --name "$container" --network bridge --memory 640m --cpus 1 -p 127.0.0.1::3306 --env-file "$task_dir/docker.env" mysql:8.4 --innodb-buffer-pool-size=64M --performance-schema=OFF >/dev/null
for i in $(seq 1 70); do
  if docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -e "SELECT 1" >/dev/null 2>&1' 2>/dev/null; then break; fi
  if [ "$i" = 70 ]; then echo 'Isolated MySQL failed to start'; exit 4; fi
  sleep 1
done
port=$(docker port "$container" 3306/tcp); port=${port##*:}
cp -- "$multipart_schema" "$task_dir/multipart-schema.sql"
docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 netdisk_qa' < "$project_root/docs/design/database/schema.sql"
docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 netdisk_qa' < "$project_root/docs/design/api/migration-upload.sql"
docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 netdisk_qa' < "$task_dir/multipart-schema.sql"
docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 netdisk_qa' < "$project_root/docs/design/api/trash-schema.sql"
docker exec -i "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 netdisk_qa' < "$project_root/docs/design/api/share-schema.sql"
cp -a "$backend_src" "$task_dir/project"
gc_source="$task_dir/project/src/main/java/xyz/yychainsaw/backend/service/MultipartGarbageCollector.java"
grep -qF '/run/lock/netdisk-multipart.lock' "$gc_source" || { echo 'Expected production lock path not found; refusing to test'; exit 3; }
sed -i "s@/run/lock/netdisk-multipart.lock@/tmp/netdisk-fileops-${qa_root}.lock@" "$gc_source"
grep -qF "/tmp/netdisk-fileops-${qa_root}.lock" "$gc_source" || { echo 'Isolated lock substitution failed'; exit 3; }
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/storage"
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/controller"
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/repository"
mkdir -p "$task_dir/project/src/test/java/xyz/yychainsaw/backend/service"
test -f "$task_dir/project/src/main/java/xyz/yychainsaw/backend/controller/DownloadController.java"
test -f "$task_dir/project/src/main/java/xyz/yychainsaw/backend/repository/DownloadMapper.java"
cp "$input_dir/ReliabilityExtraIT.java" "$task_dir/project/src/test/java/xyz/yychainsaw/backend/service/"
cd "$task_dir/project"
sha256sum src/main/java/xyz/yychainsaw/backend/service/MultipartService.java src/main/java/xyz/yychainsaw/backend/service/MultipartGarbageCollector.java src/main/java/xyz/yychainsaw/backend/storage/HdfsStorage.java "$task_dir/multipart-schema.sql"
export NETDISK_QA_DB_URL="jdbc:mysql://127.0.0.1:$port/netdisk_qa?allowPublicKeyRetrieval=true&useSSL=false"
export NETDISK_QA_DB_PASSWORD="$qa_password"
export NETDISK_QA_ROOT="$qa_root"
export NETDISK_QA_USER_ID=780000000000000001
mkdir "$task_dir/empty-config"
# Override server profile configtree so no production secrets are imported.
export SPRING_CONFIG_IMPORT="optional:configtree:$task_dir/empty-config/"
timeout 180s ./mvnw -B -ntp -o -Dtest=ReliabilityExtraIT test > "$task_dir/test.log" 2>&1 || { tail -n 90 "$task_dir/test.log"; exit 5; }
tail -n 25 "$task_dir/test.log"
