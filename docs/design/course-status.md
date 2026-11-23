# Course project status

This integrated feature worktree includes file upload/download with resumable chunks, account registration/login/password changes, file/folder creation and rename, recycle and restore, permanent deletion, folder navigation and sorting, batch move/delete, image/text/PDF preview, share links with passcode/expiry/visit limit, and an isolated Compose deployment option. The live server has not received these feature changes or the `trash-schema.sql` and `share-schema.sql` migrations.

## Validation performed

- Backend Maven suite on the integrated worktree: 41 tests, 0 failures, 0 errors, 2 database-dependent tests skipped by default. The final sorting change was included in this run.
- Frontend type check and production build on the integrated source passed with Node 24.15.0. An Edge test with mocked APIs exercised sorting, preview, share, batch move and recycle-bin navigation at desktop and mobile widths; it was not a real-HDFS end-to-end test.
- Integrated recycle and share tests passed in isolated MySQL 8.4 and HDFS directories (6 and 3 tests respectively). Separate real MySQL testing covered batch move. Preview was validated using backend tests and a simulated browser; it has not yet been verified end to end against real HDFS.
- The 2 GiB and process-kill tests recorded in `backend/qa` were run on earlier revisions, not the final integrated code.
- A previous isolated Compose iteration passed upload/download and restart persistence. A later independent smoke run used prebuilt integrated JAR and frontend assets, applied both recycle/share migrations and verified registration, upload/download, passcode share and recycle/restore. The final integrated Dockerfiles were not rebuilt from a cold cache in that run.

## Remaining work

The running production instance is still the previous version. To deploy the integrated code, reconcile and apply the two database migrations using the actual environment's permitted workflow, then verify production with an appropriate test account. Earlier automation blocked the production migration attempt. There is no verified off-server database/HDFS backup or restore exercise. Compose still needs a trusted HTTPS reverse proxy configured separately. Persistent per-user storage quotas, file version history, malware scanning, and recovery automation are not implemented.

Course grading is determined by the teacher and depends on a live demonstration, project report and the evidence requested in the course materials; these notes do not guarantee a grade.
