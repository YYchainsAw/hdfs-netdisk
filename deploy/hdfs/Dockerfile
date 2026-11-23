FROM apache/hadoop:3.5.0
USER root
RUN mkdir -p /data/name /data/data /opt/netdisk /var/log/hadoop && chown -R hadoop:users /data /opt/netdisk /var/log/hadoop
COPY deploy/hdfs/core-site.xml /opt/hadoop/etc/hadoop/core-site.xml
COPY deploy/hdfs/hdfs-site.xml /opt/hadoop/etc/hadoop/hdfs-site.xml
COPY deploy/hdfs/start-hdfs.sh deploy/hdfs/init-hdfs.sh /opt/netdisk/
USER hadoop
ENTRYPOINT ["/bin/bash", "/opt/netdisk/start-hdfs.sh"]
