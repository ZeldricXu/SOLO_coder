-- 回滚：删除所有协作引擎相关表
-- 注意：按依赖关系反向删除（有外键的先删）

DROP TABLE IF EXISTS share_links CASCADE;
DROP TABLE IF EXISTS document_permissions CASCADE;
DROP TABLE IF EXISTS snapshots CASCADE;
DROP TABLE IF EXISTS operation_logs CASCADE;
DROP TABLE IF EXISTS documents CASCADE;
