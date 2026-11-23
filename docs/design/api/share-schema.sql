-- Run once against the selected MySQL 8 netdisk database after checking table absence.
-- Additive only; existing files and accounts are not modified.
CREATE TABLE nd_share (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 owner_id BIGINT UNSIGNED NOT NULL,
 node_id BIGINT UNSIGNED NOT NULL,
 node_version BIGINT UNSIGNED NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 code_hash VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(3) NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 max_visits INT UNSIGNED NULL,
 access_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY (id),
 UNIQUE KEY uk_share_token (token_hash),
 KEY ix_share_owner (owner_id,created_at,id),
 KEY ix_share_node (node_id),
 CONSTRAINT fk_share_owner FOREIGN KEY (owner_id) REFERENCES nd_user(id),
 CONSTRAINT fk_share_node FOREIGN KEY (node_id) REFERENCES nd_node(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
