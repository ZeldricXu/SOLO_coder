ALTER TABLE `configs` ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '配置状态' AFTER `enabled`;
ALTER TABLE `configs` ADD KEY `idx_status` (`status`);
