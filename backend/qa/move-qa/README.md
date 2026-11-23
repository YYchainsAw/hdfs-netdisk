# Move operations test

`NodeBatchServiceTest.java` runs with the normal backend Maven test suite and uses an in-memory H2 database. It covers atomic multi-item moves, stale versions, ownership, destination name collisions and directory cycle rejection.

`NodeBatchMySqlIT.java` is an opt-in integration test using an isolated MySQL 8.4 database. The isolated harness must create the project schema, set `NETDISK_QA_DB_URL` and `NETDISK_QA_DB_PASSWORD` to the test-only database, and run `./mvnw -Dtest=NodeBatchMySqlIT test`; do not point it at production. It covers MySQL rollback on collision, authorization, cycles, stale versions and concurrent moves into a same-name target. The last isolated run passed 3/3 and removed its temporary MySQL resources. The UI browser script `qa-batch.cjs` uses simulated APIs and verifies selection, move requests, per-item delete versions, partial failures, and mobile width.

This implementation supports up to 100 items from a single source directory per atomic move. Batch deletion sends individual delete requests, so some can succeed while others fail; the UI reports the result for each failure. It uses the existing DELETE semantics of whichever app version is integrated.
