-- One-time MySQL 8 migration. Back up first; DDL is not transactional.
-- Preserve existing nodes, ongoing uploads, and DELETING cleanup work.
ALTER TABLE nd_node
    DROP INDEX uk_node_sibling_name,
    ADD COLUMN trash_id BIGINT UNSIGNED NULL COMMENT 'Root node id of the logical trash group',
    ADD COLUMN deleted_at DATETIME(3) NULL,
    ADD COLUMN active_name VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin
      GENERATED ALWAYS AS (CASE WHEN status = 'TRASHED' OR trash_id IS NOT NULL THEN NULL ELSE name END) STORED,
    ADD UNIQUE KEY uk_node_active_sibling_name (user_id, parent_id, active_name),
    ADD KEY ix_node_trash (user_id, trash_id, status),
    ADD KEY ix_node_trash_listing (user_id, status, deleted_at, id),
    DROP CHECK chk_node_status,
    ADD CONSTRAINT chk_node_status CHECK (status IN ('PENDING','READY','DELETING','TRASHED'));
