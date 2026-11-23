FROM mysql:8.4
COPY docs/design/database/schema.sql /docker-entrypoint-initdb.d/01-schema.sql
COPY docs/design/api/migration-upload.sql /docker-entrypoint-initdb.d/02-upload.sql
COPY docs/design/api/multipart-schema.sql /docker-entrypoint-initdb.d/03-multipart.sql
COPY deploy/mysql/04-privileges.sql /docker-entrypoint-initdb.d/04-privileges.sql

COPY docs/design/api/trash-schema.sql /docker-entrypoint-initdb.d/05-trash.sql
COPY docs/design/api/share-schema.sql /docker-entrypoint-initdb.d/06-share.sql
