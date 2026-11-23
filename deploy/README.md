# Compose deployment for an independent single-node course environment

This deployment creates its own MySQL 8.4 database, single-node HDFS, backend and frontend. It does not adopt the existing host HDFS, MySQL or site. It has been exercised in a randomly named isolated project: registration, folder creation, HDFS file upload/download, and download after all four containers were stopped and started were verified. Do not point it at production volumes without an explicit migration plan.

## Requirements

Linux, Docker Engine and Compose v2 or later, OpenSSL and curl; sufficient RAM and disk for Hadoop, MySQL, application images and uploads; internet to obtain Docker images and build dependencies. A cold build can take many minutes. Run from a clean project checkout with database schema scripts and `deploy/` in the repository root. Verify that no other deployment uses the selected Compose project name or local port.

```bash
cd deploy
sudo bash start.sh
```

The script starts a new stack named `netdisk-course` and binds its web port only to `127.0.0.1:18090`. Set `NETDISK_COMPOSE_PROJECT` and `NETDISK_PORT` to choose a separate stack and loopback port. New root and application passwords are generated in `deploy/secrets/`, which must be kept private and preserved with the volumes; the application runs as a restricted database user. No default application login is created; register an account after configuring HTTPS.

For a real browser, configure a trusted HTTPS reverse proxy in front of the local port. The production profile uses Secure cookies. The upstream proxy must **replace** inbound `Forwarded` and `X-Forwarded-*` headers with trusted values derived from its own connection; forwarding user-supplied values can bypass origin checks and limit controls. The included inner Nginx trusts its loopback-bound host proxy and should not be exposed directly to untrusted clients. TLS certificate issuance and host Nginx setup are not automated by this Compose script.

Three persistent data volumes contain MySQL, NameNode metadata and DataNode blocks; a fourth holds the backend's lock. Only when all three data volumes are absent and the HDFS directories are empty does the initialization service format a new NameNode. The normal HDFS start script refuses to format storage. If some but not all data volumes exist, if they are missing their original secrets, or if metadata is abnormal, startup refuses to initialize; inspect and restore existing data rather than recreating volumes. `docker compose down -v` removes data and is not a routine stop command. Keep independent backups of all three data volumes and the secrets, and test restoration.

Use `docker compose -p netdisk-course -f compose.yaml logs` and `docker compose -p netdisk-course -f compose.yaml ps` from `deploy/` for diagnostics. To stop without deleting storage: `docker compose -p netdisk-course -f compose.yaml stop`. Compose names differ if using `NETDISK_COMPOSE_PROJECT`. MySQL runs with UTC as its default timezone and the JDBC connection requests UTC. HDFS and MySQL ports are not published to the host; NameNode and DataNode web endpoints listen only on their container network namespace loopback.

Limitations: single HDFS node, one backend instance, no automated HTTPS setup, no cross-host high availability or automatic backup. The actual test used HTTP on loopback with an explicit cookie handling test client; production browser operation requires a correctly configured HTTPS proxy. This documentation cannot replace a fresh-environment deployment and recovery exercise by another person.
