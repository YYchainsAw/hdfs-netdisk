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
container_created=0
paths_owned=0
cleanup() {
  if [ -n "$app_pid" ]; then kill "$app_pid" >/dev/null 2>&1 || true; wait "$app_pid" 2>/dev/null || true; fi
  if [ "$container_created" = 1 ]; then docker rm -fv "$container" >/dev/null 2>&1 || true; fi
  rm -f -- "/tmp/netdisk-fileops-${qa_root}.lock"
  local path
  if [ "$paths_owned" = 1 ]; then
    for path in "/netdisk/temp/$qa_root" "/netdisk/users/$qa_root"; do
      sudo -u hdfs env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 /opt/hadoop/bin/hdfs dfs -rm -r -skipTrash "$path" >/dev/null 2>&1 || true
    done
  fi
  rm -rf -- "$task_dir"
}
trap cleanup EXIT
if docker container inspect "$container" >/dev/null 2>&1; then echo 'Test container exists'; exit 3; fi
for path in "/netdisk/temp/$qa_root" "/netdisk/users/$qa_root"; do
 if sudo -u hdfs env JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 /opt/hadoop/bin/hdfs dfs -test -e "$path"; then echo 'Test path exists'; exit 3; fi
done
paths_owned=1
qa_password=$(openssl rand -hex 32)
printf 'MYSQL_ROOT_PASSWORD=%s\nMYSQL_ROOT_HOST=%%\nMYSQL_DATABASE=netdisk_qa\n' "$qa_password" > "$task_dir/docker.env"
docker run -d --name "$container" --network bridge --memory 640m --cpus 1 -p 127.0.0.1::3306 --env-file "$task_dir/docker.env" mysql:8.4 --innodb-buffer-pool-size=64M --performance-schema=OFF >/dev/null
container_created=1
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
sed -i "s@/run/lock/netdisk-multipart.lock@/tmp/netdisk-fileops-${qa_root}.lock@" "$task_dir/project/src/main/java/xyz/yychainsaw/backend/service/MultipartGarbageCollector.java"
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/storage"
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/controller"
mkdir -p "$task_dir/project/src/main/java/xyz/yychainsaw/backend/repository"
mkdir -p "$task_dir/project/src/test/java/xyz/yychainsaw/backend/service"
if [ ! -f "$task_dir/project/src/main/java/xyz/yychainsaw/backend/controller/DownloadController.java" ]; then cp "$input_dir/DownloadController.java" "$task_dir/project/src/main/java/xyz/yychainsaw/backend/controller/"; fi
if [ ! -f "$task_dir/project/src/main/java/xyz/yychainsaw/backend/repository/DownloadMapper.java" ]; then cp "$input_dir/DownloadMapper.java" "$task_dir/project/src/main/java/xyz/yychainsaw/backend/repository/"; fi
cp "$input_dir/ShareMySqlIT.java" "$task_dir/project/src/test/java/xyz/yychainsaw/backend/controller/"
cd "$task_dir/project"
sha256sum src/main/java/xyz/yychainsaw/backend/service/MultipartService.java src/main/java/xyz/yychainsaw/backend/service/MultipartGarbageCollector.java src/main/java/xyz/yychainsaw/backend/storage/HdfsStorage.java "$task_dir/multipart-schema.sql"
export NETDISK_QA_DB_URL="jdbc:mysql://127.0.0.1:$port/netdisk_qa?allowPublicKeyRetrieval=true&useSSL=false"
export NETDISK_QA_DB_PASSWORD="$qa_password"
export NETDISK_QA_ROOT="$qa_root"
export NETDISK_QA_USER_ID=780000000000000001
mkdir "$task_dir/empty-config"
# Override server profile configtree so no production secrets are imported.
export SPRING_CONFIG_IMPORT="optional:configtree:$task_dir/empty-config/"
timeout 180s ./mvnw -B -ntp -o -Dtest=ShareMySqlIT test > "$task_dir/test.log" 2>&1 || { tail -n 90 "$task_dir/test.log"; exit 5; }
tail -n 25 "$task_dir/test.log"
if [ "${1:-}" = "--hold" ]; then
  if ss -H -ltn 'sport = :18086' | grep -q .; then echo 'Test port already in use'; exit 6; fi
  timeout 120s ./mvnw -q -ntp -o -DskipTests package > "$task_dir/package.log" 2>&1 || { tail -n 60 "$task_dir/package.log"; exit 7; }
  export SPRING_DATASOURCE_URL="$NETDISK_QA_DB_URL" SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$qa_password"
  export SERVER_SERVLET_SESSION_COOKIE_SECURE=false
  :
  java -Xms64m -Xmx256m -jar target/backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=server --server.port=18086 \
    "--netdisk.hdfs.temp-root=/netdisk/temp/$qa_root" "--netdisk.hdfs.users-root=/netdisk/users/$qa_root" > "$task_dir/app.log" 2>&1 &
  app_pid=$!
  for i in $(seq 1 40); do
    if curl -sf --max-time 1 http://127.0.0.1:18086/api/v1/auth/csrf >/dev/null; then break; fi
    if ! kill -0 "$app_pid" 2>/dev/null || [ "$i" = 40 ]; then echo 'Test backend failed to start'; tail -n 35 "$task_dir/app.log"; exit 8; fi
    sleep 1
  done
  echo "E2E_READY_PORT=18086; REGISTER_A_TEMPORARY_ACCOUNT; DATABASE_AND_HDFS_ARE_ISOLATED"
  read -r || true
fi
