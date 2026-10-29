-- HDFS 网盘基础版参考设计，适用于 MySQL 8.0.17+ / 8.4。
-- 请先自行选择专用空数据库；本脚本不会创建数据库、账号或删除数据。
-- 脚本为首次建表使用，已存在同名表时会停止而不是覆盖。
SET NAMES utf8mb4;

CREATE TABLE nd_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL COMMENT '登录名，不区分大小写',
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '密码哈希，不存明文',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网盘账号';

CREATE TABLE nd_node (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 表示虚拟根目录，非 0 为目录节点 ID',
    name VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    node_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FILE 或 FOLDER',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PENDING、READY、DELETING',
    storage_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT '服务端生成的 HDFS 对象标识',
    size_bytes BIGINT UNSIGNED DEFAULT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_sibling_name (user_id, parent_id, name),
    UNIQUE KEY uk_node_storage_key (storage_key),
    KEY ix_node_parent_status (user_id, parent_id, status),
    CONSTRAINT fk_node_user FOREIGN KEY (user_id) REFERENCES nd_user (id),
    CONSTRAINT chk_node_type CHECK (node_type IN ('FILE', 'FOLDER')),
    CONSTRAINT chk_node_status CHECK (status IN ('PENDING', 'READY', 'DELETING')),
    CONSTRAINT chk_node_content CHECK (
        (node_type = 'FOLDER' AND storage_key IS NULL AND size_bytes IS NULL AND sha256 IS NULL)
        OR (node_type = 'FILE' AND storage_key IS NOT NULL AND size_bytes IS NOT NULL)
    ),
    CONSTRAINT chk_node_name CHECK (CHAR_LENGTH(name) > 0),
    CONSTRAINT chk_node_version CHECK (version >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件和目录元数据';

CREATE TABLE nd_upload (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '同一用户内的幂等键',
    parent_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
    requested_name VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    target_name VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
    temp_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    final_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_size BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UPLOADING、CONFLICT、FINALIZING、COMPLETED、FAILED、CANCELLED',
    node_id BIGINT UNSIGNED DEFAULT NULL COMMENT '发布时关联文件节点',
    expires_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_request (user_id, request_id),
    UNIQUE KEY uk_upload_temp_key (temp_key),
    UNIQUE KEY uk_upload_final_key (final_key),
    KEY ix_upload_status_expiry (status, expires_at),
    KEY ix_upload_node (node_id),
    CONSTRAINT fk_upload_user FOREIGN KEY (user_id) REFERENCES nd_user (id),
    CONSTRAINT fk_upload_node FOREIGN KEY (node_id) REFERENCES nd_node (id) ON DELETE SET NULL,
    CONSTRAINT chk_upload_status CHECK (status IN ('UPLOADING', 'CONFLICT', 'FINALIZING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_upload_request CHECK (CHAR_LENGTH(request_id) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='上传任务与幂等记录';

CREATE TABLE nd_storage_gc (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    storage_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reason VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UPLOAD_FAILED、NODE_DELETED 或 ORPHAN',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_gc_storage_key (storage_key),
    KEY ix_gc_due (status, next_retry_at),
    CONSTRAINT fk_gc_user FOREIGN KEY (user_id) REFERENCES nd_user (id),
    CONSTRAINT chk_gc_reason CHECK (reason IN ('UPLOAD_FAILED', 'NODE_DELETED', 'ORPHAN')),
    CONSTRAINT chk_gc_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HDFS 异步清理任务';
