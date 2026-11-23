# Recycle bin verification on 2026-11-23

The final isolated MySQL 8.4 and HDFS test suite `trash-qa/run-trash-it.sh` was run with random test-only resources and an isolated backend lock. Six tests passed with no failures. The suite exercises nested recycle and restore, conflicts and authorization, asynchronous deletion failure and retry, concurrent directory/upload operations, and retained usage. Temporary resources were cleaned up after the run. A GC warning in the log was produced intentionally by the simulated HDFS failure.

The regular Maven suite reported 23 passed and two opt-in tests skipped. Separate combined and upload reliability tests were rerun with the recycle-bin migration and passed. The browser test used isolated MySQL and HDFS to cover recycle, name conflict, restore, download integrity, use of the original name and retained usage. These tests do not prove every possible race or restore scenario.

The database migration changes the uniqueness constraint. It has not been applied to the live database. Stop writes, create a fresh verified backup and review the migration and forward recovery plan before any deployment. Existing recycle entries or reused file names cannot safely be handled by the previous backend or index.
