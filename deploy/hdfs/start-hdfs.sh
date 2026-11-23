set -euo pipefail
[ -f /data/name/current/VERSION ] || { echo 'NameNode metadata missing; runtime will never format storage.' >&2; exit 1; }
hdfs namenode & nn=$!
for i in $(seq 1 60); do
  if hdfs dfsadmin -safemode get >/dev/null 2>&1; then break; fi
  kill -0 "$nn" 2>/dev/null || { echo 'NameNode exited' >&2; exit 1; }
  sleep 2
done
hdfs datanode & dn=$!
cleanup() { kill "$nn" "$dn" 2>/dev/null || true; wait "$nn" "$dn" 2>/dev/null || true; }
trap cleanup EXIT TERM INT
for i in $(seq 1 90); do
  if hdfs dfsadmin -report 2>/dev/null | grep -Eq 'Live datanodes \([1-9]'; then break; fi
  kill -0 "$nn" "$dn" 2>/dev/null || { echo 'HDFS process exited' >&2; exit 1; }
  sleep 2
done
hdfs dfs -mkdir -p /netdisk/temp /netdisk/users
hdfs dfs -chmod 750 /netdisk /netdisk/temp /netdisk/users
wait -n "$nn" "$dn"
