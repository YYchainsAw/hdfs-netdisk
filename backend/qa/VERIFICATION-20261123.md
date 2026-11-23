# Reliability and security verification

This document records executed tests and known limitations; it is not a claim that all failures or attacks are prevented.

## 2026-11-23 verification

- Backend Maven suite: 27 tests, 25 passed, two opt-in database tests skipped, no failures. The real MySQL and HDFS tests below are separate.
- `backend/qa/reliability-extra`: isolated MySQL 8.4 and random HDFS directories; verified concurrent request and chunk idempotency, completion retry, folder protection during upload, cancellation and expired-task cleanup. The test ran with small objects.
- `backend/qa/proxy-security`: isolated H2 backend and a separate loopback Nginx process with the site's API proxy rules; spoofed forwarding headers while alternating successful logins with password guesses. The old baseline returned 401 where the test expected 429; the repaired version returned 429.
- `backend/qa/performance-qa`: isolated MySQL and random HDFS paths, including one exact 2 GiB test with matching download SHA-256. See `performance-qa/results-20261123.md` and the raw result file for workload, timings and constraints.
- Existing integration suites cover process interruption, isolated users, deletion and upload recovery. Each suite documents its resource requirements and cleanup in its README.
- GitHub Actions workflow runs Maven tests and frontend install, type check and build. It does not run MySQL/HDFS fault or performance tests; those remain opt-in and must run on a suitable isolated server.

## Known limits

Login throttling is held in one backend process and resets on restart. It is not a distributed attack defense. Every source IP has a shared limit of 50 attempts per five minutes; each IP and existing account pair is additionally limited to 10 attempts in that window. Many legitimate users behind the same NAT share the IP limit. Unknown accounts share an account bucket; response differences can enable username enumeration, and public registration has only basic per-IP throttling. No email verification, CAPTCHA, multi-factor authentication, or distributed abuse controls are implemented.

The system runs one backend and one HDFS data node. Tests do not replace independent HDFS and database backups or a restore rehearsal. HDFS shares physical storage with other server services; free-space checks cannot reserve against their concurrent writes. A single local 2 GiB sample does not establish public network speed or peak concurrent capacity. Nonempty folders cannot currently be deleted; deleted files are not recoverable through a recycle bin.
