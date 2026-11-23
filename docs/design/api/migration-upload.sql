-- 设计稿：供首次建表后、且 nd_upload 尚无数据时应用；未在服务器执行。
-- 将上传任务状态与单次内容写入状态分开，并为每次全量写入加入 fencing。
ALTER TABLE nd_upload
    ADD COLUMN content_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'AWAITING'
        COMMENT 'AWAITING、RECEIVING、READY' AFTER status,
    ADD COLUMN attempt_no BIGINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '完整上传尝试序号，领取新尝试时递增' AFTER content_state,
    ADD COLUMN lease_token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '当前写入者令牌，仅服务端持有' AFTER attempt_no,
    ADD COLUMN lease_until DATETIME(3) DEFAULT NULL
        COMMENT '写入租约过期时间，使用数据库时间判断' AFTER lease_token,
    ADD COLUMN received_size BIGINT UNSIGNED DEFAULT NULL
        COMMENT '完成校验的实际字节数' AFTER lease_until,
    ADD COLUMN content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '服务端计算的实际 SHA-256' AFTER received_size,
    ADD COLUMN resolution VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT '已接受的冲突处理决定 KEEP_BOTH 或 SKIP，非冲突取消为 NULL' AFTER content_sha256,
    ADD COLUMN terminal_at DATETIME(3) DEFAULT NULL
        COMMENT '首次进入 COMPLETED、FAILED 或 CANCELLED 的数据库时间' AFTER resolution,
    ADD KEY ix_upload_lease (status, content_state, lease_until),
    ADD CONSTRAINT chk_upload_content_state CHECK (content_state IN ('AWAITING', 'RECEIVING', 'READY')),
    ADD CONSTRAINT chk_upload_content_lease CHECK (
        (content_state = 'RECEIVING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (content_state IN ('AWAITING', 'READY') AND lease_token IS NULL AND lease_until IS NULL)
    ),
    ADD CONSTRAINT chk_upload_content_ready CHECK (
        content_state <> 'READY' OR (received_size IS NOT NULL AND content_sha256 IS NOT NULL)
    ),
    ADD CONSTRAINT chk_upload_resolution CHECK (resolution IS NULL OR resolution IN ('KEEP_BOTH', 'SKIP')),
    ADD CONSTRAINT chk_upload_terminal_time CHECK (
        (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND terminal_at IS NOT NULL)
        OR (status IN ('UPLOADING', 'CONFLICT', 'FINALIZING') AND terminal_at IS NULL)
    );
