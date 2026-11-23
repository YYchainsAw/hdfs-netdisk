-- Apply once after backing up and verifying the target database.
CREATE TABLE nd_multipart_limits (id TINYINT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO nd_multipart_limits (id) VALUES (1);
CREATE TABLE nd_multipart_upload (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 parent_id BIGINT UNSIGNED NOT NULL,
 name VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 size_bytes BIGINT UNSIGNED NOT NULL,
 chunk_size INT UNSIGNED NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 node_id BIGINT UNSIGNED NULL,
 final_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
 finalize_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 finalize_until DATETIME(3) NULL,
 temp_dir_cleaned BOOLEAN NOT NULL DEFAULT FALSE,
 expires_at DATETIME(3) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_mp_request(user_id,request_id),
 KEY ix_mp_expiry(status,expires_at),
 CONSTRAINT fk_mp_user FOREIGN KEY(user_id) REFERENCES nd_user(id),
 CONSTRAINT fk_mp_node FOREIGN KEY(node_id) REFERENCES nd_node(id),
 CONSTRAINT chk_mp_status CHECK(status IN ('UPLOADING','FINALIZING','COMPLETED','SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE nd_multipart_manifest (
 upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 part_no INT UNSIGNED NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(upload_id,part_no),
 CONSTRAINT fk_mp_manifest_upload FOREIGN KEY(upload_id) REFERENCES nd_multipart_upload(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE nd_multipart_part (
 upload_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 part_no INT UNSIGNED NOT NULL,
 object_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 size_bytes INT UNSIGNED NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 lease_until DATETIME(3) NULL,
 PRIMARY KEY(upload_id,part_no),
 CONSTRAINT fk_mp_part_upload FOREIGN KEY(upload_id) REFERENCES nd_multipart_upload(id),
 CONSTRAINT chk_mp_part_state CHECK(state IN ('WRITING','READY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
