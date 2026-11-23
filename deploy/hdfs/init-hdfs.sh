set -euo pipefail
if [ -f /data/name/current/VERSION ]; then
  echo 'Existing HDFS metadata found; refusing to format.'
  exit 0
fi
if [ -n "$(find /data/name /data/data -mindepth 1 -print -quit)" ]; then
  echo 'HDFS volumes contain data but no valid NameNode metadata; refusing to format.' >&2
  exit 1
fi
[ "${NETDISK_INITIAL_FORMAT:-}" = YES ] || { echo 'Explicit initial format is required.' >&2; exit 1; }
hdfs namenode -format -nonInteractive
