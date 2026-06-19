-- 协作引擎数据库初始 Schema
-- 对应 storage/mod.rs 中的 init_schema()

-- 文档主表
CREATE TABLE documents (
    id UUID PRIMARY KEY,
    title VARCHAR(1024) NOT NULL DEFAULT 'Untitled',
    owner_id VARCHAR(255) NOT NULL,
    content_preview TEXT,
    current_version BIGINT NOT NULL DEFAULT 0,
    last_snapshot_version BIGINT,
    last_modified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_modified_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE,
    tags TEXT[] NOT NULL DEFAULT '{}'
);

-- 文档索引：按所有者
CREATE INDEX idx_documents_owner ON documents(owner_id);
-- 文档索引：按最后修改时间降序
CREATE INDEX idx_documents_modified ON documents(last_modified_at DESC);

-- 操作日志表（CRDT 增量操作）
CREATE TABLE operation_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    client_id BIGINT NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    session_id UUID,
    op_type VARCHAR(32) NOT NULL,
    yata_client BIGINT NOT NULL,
    yata_clock BIGINT NOT NULL,
    op_payload JSONB NOT NULL,
    op_binary BYTEA,
    sequence BIGINT NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    node_id VARCHAR(255)
);

-- 操作日志索引：按文档 + 时间降序
CREATE INDEX idx_oplogs_doc_time ON operation_logs(document_id, timestamp DESC);
-- 操作日志索引：按文档 + 序列号
CREATE INDEX idx_oplogs_doc_seq ON operation_logs(document_id, sequence);
-- 操作日志索引：按用户
CREATE INDEX idx_oplogs_user ON operation_logs(user_id);
-- 操作日志索引：按文档 + YataID（client, clock）
CREATE INDEX idx_oplogs_yata ON operation_logs(document_id, yata_client, yata_clock);

-- 快照表（定期保存的文档完整状态）
CREATE TABLE snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    ops_count BIGINT NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_backend VARCHAR(32) NOT NULL,
    storage_path VARCHAR(2048) NOT NULL,
    checksum VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL,
    compressed BOOLEAN NOT NULL DEFAULT TRUE,
    vector_clock JSONB NOT NULL,
    UNIQUE(document_id, version)
);

-- 快照索引：按文档 + 版本降序
CREATE INDEX idx_snapshots_doc ON snapshots(document_id, version DESC);

-- 文档权限表
CREATE TABLE document_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    granted_by VARCHAR(255),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    UNIQUE(document_id, user_id)
);

-- 权限索引：按用户
CREATE INDEX idx_perms_user ON document_permissions(user_id);

-- 分享链接表
CREATE TABLE share_links (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    created_by VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ,
    max_uses INTEGER,
    use_count INTEGER NOT NULL DEFAULT 0,
    is_revoked BOOLEAN NOT NULL DEFAULT FALSE,
    requires_email VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 分享链接索引：按文档
CREATE INDEX idx_shares_doc ON share_links(document_id);
