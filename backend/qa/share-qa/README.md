# Isolated share integration test

Run `NETDISK_QA_PROJECT_ROOT=/path/to/project bash run-share-it.sh` on a Linux server with Docker, MySQL 8.4 image, Java 17, cached Maven dependencies and a running testable HDFS. The script creates a random MySQL container and random HDFS directories, uses a test-specific backend lock and cleans up its own resources. It executes the project schema and migrations only against the temporary database; never point the test at production data.

The suite covers sharing with a passcode, expiry, access limits, cross-user access, revocation and file-state changes. Test credentials are generated for the isolated run. If forcibly interrupted, inspect the specific random container and HDFS paths before cleaning only those resources.
