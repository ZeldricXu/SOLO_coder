-- ============================================================
-- 设计系统管理平台 - 数据库初始化脚本
-- Database: MySQL 8.0+
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ------------------------------------------------------------
-- 用户与权限相关表
-- ------------------------------------------------------------

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` varchar(64) NOT NULL COMMENT '用户名',
    `password` varchar(128) NOT NULL COMMENT '密码',
    `nickname` varchar(64) DEFAULT NULL COMMENT '昵称',
    `email` varchar(128) DEFAULT NULL COMMENT '邮箱',
    `avatar` varchar(255) DEFAULT NULL COMMENT '头像',
    `phone` varchar(32) DEFAULT NULL COMMENT '手机号',
    `status` tinyint DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    `department` varchar(64) DEFAULT NULL COMMENT '部门',
    `position` varchar(64) DEFAULT NULL COMMENT '职位',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除: 0未删除 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `role_name` varchar(64) NOT NULL COMMENT '角色名称',
    `role_code` varchar(64) NOT NULL COMMENT '角色编码',
    `description` varchar(255) DEFAULT NULL COMMENT '描述',
    `sort_order` int DEFAULT 0 COMMENT '排序',
    `status` tinyint DEFAULT 1 COMMENT '状态: 1启用 0禁用',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `role_id` bigint NOT NULL COMMENT '角色ID',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联表';

-- ------------------------------------------------------------
-- 组件相关表
-- ------------------------------------------------------------

DROP TABLE IF EXISTS `ds_component`;
CREATE TABLE `ds_component` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` varchar(64) NOT NULL COMMENT '组件名称(英文标识)',
    `display_name` varchar(64) DEFAULT NULL COMMENT '显示名称',
    `description` text COMMENT '组件描述',
    `category` varchar(64) DEFAULT NULL COMMENT '分类',
    `tags` varchar(255) DEFAULT NULL COMMENT '标签(逗号分隔)',
    `framework` varchar(16) DEFAULT NULL COMMENT '技术栈: REACT/VUE',
    `maintainer_id` bigint DEFAULT NULL COMMENT '维护人ID',
    `latest_version` varchar(32) DEFAULT NULL COMMENT '最新版本',
    `git_repository` varchar(255) DEFAULT NULL COMMENT 'Git仓库地址',
    `npm_package` varchar(128) DEFAULT NULL COMMENT 'NPM包名',
    `preview_url` varchar(255) DEFAULT NULL COMMENT '预览地址',
    `screenshot_url` varchar(255) DEFAULT NULL COMMENT '截图地址',
    `readme_content` longtext COMMENT 'README内容',
    `status` tinyint DEFAULT 1 COMMENT '状态',
    `published` tinyint DEFAULT 0 COMMENT '是否已发布: 1是 0否',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name_framework` (`name`, `framework`),
    KEY `idx_category` (`category`),
    KEY `idx_maintainer_id` (`maintainer_id`),
    KEY `idx_published` (`published`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件表';

DROP TABLE IF EXISTS `ds_component_version`;
CREATE TABLE `ds_component_version` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_id` bigint NOT NULL COMMENT '组件ID',
    `version` varchar(32) NOT NULL COMMENT '版本号(SemVer)',
    `changelog` text COMMENT '变更日志',
    `release_notes` text COMMENT '发布说明',
    `source_code_path` varchar(255) DEFAULT NULL COMMENT '源码路径',
    `compiled_code_path` varchar(255) DEFAULT NULL COMMENT '编译后代码路径',
    `preview_html_path` varchar(255) DEFAULT NULL COMMENT '预览HTML路径',
    `commit_hash` varchar(64) DEFAULT NULL COMMENT 'Git提交哈希',
    `is_latest` tinyint DEFAULT 0 COMMENT '是否最新版本',
    `is_prerelease` tinyint DEFAULT 0 COMMENT '是否预发布版本',
    `deprecated_reason` varchar(255) DEFAULT NULL COMMENT '弃用说明',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_component_version` (`component_id`, `version`),
    KEY `idx_component_id` (`component_id`),
    KEY `idx_is_latest` (`is_latest`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件版本表';

DROP TABLE IF EXISTS `ds_component_prop`;
CREATE TABLE `ds_component_prop` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_version_id` bigint NOT NULL COMMENT '组件版本ID',
    `name` varchar(64) NOT NULL COMMENT '属性名',
    `prop_type` varchar(128) DEFAULT NULL COMMENT '属性类型',
    `default_value` varchar(255) DEFAULT NULL COMMENT '默认值',
    `description` varchar(512) DEFAULT NULL COMMENT '属性描述',
    `required` tinyint DEFAULT 0 COMMENT '是否必填: 1是 0否',
    `possible_values` text COMMENT '可选值(JSON格式)',
    `sort_order` int DEFAULT 0 COMMENT '排序',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_component_version_id` (`component_version_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件属性表';

DROP TABLE IF EXISTS `ds_component_doc`;
CREATE TABLE `ds_component_doc` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_version_id` bigint NOT NULL COMMENT '组件版本ID',
    `title` varchar(128) DEFAULT NULL COMMENT '文档标题',
    `doc_type` varchar(32) DEFAULT NULL COMMENT '文档类型',
    `content` longtext COMMENT '文档内容(Markdown)',
    `example_code` longtext COMMENT '示例代码',
    `preview_url` varchar(255) DEFAULT NULL COMMENT '预览地址',
    `sort_order` int DEFAULT 0 COMMENT '排序',
    `indexed` tinyint DEFAULT 0 COMMENT '是否已索引到搜索引擎',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_component_version_id` (`component_version_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件文档表';

DROP TABLE IF EXISTS `ds_doc_parse_record`;
CREATE TABLE `ds_doc_parse_record` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_id` bigint DEFAULT NULL COMMENT '组件ID',
    `version_id` bigint DEFAULT NULL COMMENT '组件版本ID',
    `file_path` varchar(512) NOT NULL COMMENT '文件路径',
    `file_hash` varchar(128) DEFAULT NULL COMMENT '文件内容哈希(SHA-256)',
    `file_size` bigint DEFAULT 0 COMMENT '文件大小(字节)',
    `parse_status` tinyint DEFAULT 0 COMMENT '解析状态: 1成功 2失败 3跳过',
    `parse_error` text COMMENT '解析错误信息',
    `last_parsed_commit` varchar(64) DEFAULT NULL COMMENT '上次解析的Git提交哈希',
    `last_parsed_at` datetime DEFAULT NULL COMMENT '上次解析时间',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_component_id` (`component_id`),
    KEY `idx_version_id` (`version_id`),
    KEY `idx_file_path` (`file_path`),
    KEY `idx_file_hash` (`file_hash`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档解析记录表';

-- ------------------------------------------------------------
-- 设计令牌相关表
-- ------------------------------------------------------------

DROP TABLE IF EXISTS `ds_design_token`;
CREATE TABLE `ds_design_token` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_name` varchar(128) NOT NULL COMMENT '令牌名称(CSS变量名)',
    `display_name` varchar(64) DEFAULT NULL COMMENT '显示名称',
    `description` varchar(512) DEFAULT NULL COMMENT '描述',
    `token_type` varchar(32) DEFAULT NULL COMMENT '令牌类型',
    `token_level` varchar(32) DEFAULT NULL COMMENT '令牌层级',
    `base_value` varchar(512) DEFAULT NULL COMMENT '基础值',
    `inherits_from` varchar(128) DEFAULT NULL COMMENT '继承自(父令牌名)',
    `category` varchar(64) DEFAULT NULL COMMENT '分类',
    `tags` varchar(255) DEFAULT NULL COMMENT '标签',
    `status` tinyint DEFAULT 1 COMMENT '状态: 1活跃 0废弃',
    `deprecated_by` varchar(128) DEFAULT NULL COMMENT '被哪个令牌替代',
    `deprecation_reason` varchar(255) DEFAULT NULL COMMENT '废弃原因',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_token_name` (`token_name`),
    KEY `idx_token_type` (`token_type`),
    KEY `idx_token_level` (`token_level`),
    KEY `idx_inherits_from` (`inherits_from`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设计令牌表';

DROP TABLE IF EXISTS `ds_token_override`;
CREATE TABLE `ds_token_override` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_id` bigint NOT NULL COMMENT '令牌ID',
    `override_value` varchar(512) DEFAULT NULL COMMENT '覆盖值',
    `scope` varchar(64) DEFAULT NULL COMMENT '作用域',
    `scope_type` varchar(32) DEFAULT NULL COMMENT '作用域类型',
    `theme` varchar(32) DEFAULT NULL COMMENT '主题(light/dark)',
    `breakpoint` varchar(32) DEFAULT NULL COMMENT '断点',
    `priority` int DEFAULT 0 COMMENT '优先级',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='令牌覆盖表';

DROP TABLE IF EXISTS `ds_component_token_usage`;
CREATE TABLE `ds_component_token_usage` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_id` bigint NOT NULL COMMENT '组件ID',
    `token_id` bigint NOT NULL COMMENT '令牌ID',
    `css_property` varchar(128) DEFAULT NULL COMMENT 'CSS属性名',
    `usage_location` varchar(255) DEFAULT NULL COMMENT '使用位置',
    `usage_context` varchar(255) DEFAULT NULL COMMENT '使用上下文',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_component_id` (`component_id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组件令牌使用关联表';

DROP TABLE IF EXISTS `ds_token_change`;
CREATE TABLE `ds_token_change` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `token_id` bigint NOT NULL COMMENT '令牌ID',
    `change_type` varchar(32) DEFAULT NULL COMMENT '变更类型: CREATE/UPDATE/DELETE/RENAME',
    `old_name` varchar(128) DEFAULT NULL COMMENT '旧名称',
    `new_name` varchar(128) DEFAULT NULL COMMENT '新名称',
    `old_value` varchar(512) DEFAULT NULL COMMENT '旧值',
    `new_value` varchar(512) DEFAULT NULL COMMENT '新值',
    `migration_guide` text COMMENT '迁移指南',
    `breaking_change` tinyint DEFAULT 0 COMMENT '是否破坏性变更',
    `affected_components` text COMMENT '受影响的组件(JSON数组)',
    `affected_pages` text COMMENT '受影响的页面(JSON数组)',
    `approval_request_id` bigint DEFAULT NULL COMMENT '审批请求ID',
    `effective_date` datetime DEFAULT NULL COMMENT '生效日期',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_token_id` (`token_id`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='令牌变更历史表';

-- ------------------------------------------------------------
-- 审批与变更追踪表
-- ------------------------------------------------------------

DROP TABLE IF EXISTS `ds_approval_request`;
CREATE TABLE `ds_approval_request` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `request_type` varchar(32) DEFAULT NULL COMMENT '请求类型',
    `target_id` bigint DEFAULT NULL COMMENT '目标ID',
    `target_type` varchar(32) DEFAULT NULL COMMENT '目标类型',
    `title` varchar(128) DEFAULT NULL COMMENT '标题',
    `description` text COMMENT '描述',
    `change_content` longtext COMMENT '变更内容(JSON)',
    `approver_id` bigint DEFAULT NULL COMMENT '审批人ID',
    `status` varchar(16) DEFAULT 'PENDING' COMMENT '审批状态',
    `approval_comment` text COMMENT '审批意见',
    `approved_at` datetime DEFAULT NULL COMMENT '审批时间',
    `reject_reason` varchar(512) DEFAULT NULL COMMENT '拒绝原因',
    `submitted_by` bigint DEFAULT NULL COMMENT '提交人ID',
    `submitted_at` datetime DEFAULT NULL COMMENT '提交时间',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_approver_id` (`approver_id`),
    KEY `idx_status` (`status`),
    KEY `idx_submitted_by` (`submitted_by`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审批请求表';

DROP TABLE IF EXISTS `ds_changelog`;
CREATE TABLE `ds_changelog` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `component_id` bigint DEFAULT NULL COMMENT '组件ID',
    `version` varchar(32) DEFAULT NULL COMMENT '版本号',
    `commit_type` varchar(32) DEFAULT NULL COMMENT '提交类型',
    `commit_scope` varchar(64) DEFAULT NULL COMMENT '提交范围',
    `commit_subject` varchar(255) DEFAULT NULL COMMENT '提交主题',
    `commit_body` text COMMENT '提交正文',
    `breaking_change` text COMMENT '破坏性变更说明',
    `commit_hash` varchar(64) DEFAULT NULL COMMENT 'Git提交哈希',
    `author` varchar(64) DEFAULT NULL COMMENT '作者',
    `author_email` varchar(128) DEFAULT NULL COMMENT '作者邮箱',
    `committed_at` datetime DEFAULT NULL COMMENT '提交时间',
    `included_in_release` tinyint DEFAULT 0 COMMENT '是否已包含在发布中',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    KEY `idx_component_id` (`component_id`),
    KEY `idx_commit_hash` (`commit_hash`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='变更日志表';

-- ------------------------------------------------------------
-- 下游项目表
-- ------------------------------------------------------------

DROP TABLE IF EXISTS `ds_project`;
CREATE TABLE `ds_project` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `project_name` varchar(64) NOT NULL COMMENT '项目名称',
    `project_code` varchar(64) NOT NULL COMMENT '项目编码',
    `description` text COMMENT '项目描述',
    `git_repository` varchar(255) DEFAULT NULL COMMENT 'Git仓库地址',
    `git_branch` varchar(64) DEFAULT NULL COMMENT 'Git分支',
    `tech_stack` varchar(32) DEFAULT NULL COMMENT '技术栈',
    `contact_person` varchar(64) DEFAULT NULL COMMENT '联系人',
    `contact_email` varchar(128) DEFAULT NULL COMMENT '联系邮箱',
    `subscription_status` tinyint DEFAULT 1 COMMENT '订阅状态: 1已订阅 0已取消',
    `webhook_url` varchar(255) DEFAULT NULL COMMENT 'Webhook地址',
    `notification_config` text COMMENT '通知配置(JSON)',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` bigint DEFAULT NULL COMMENT '创建人',
    `updated_by` bigint DEFAULT NULL COMMENT '更新人',
    `deleted` tinyint DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_code` (`project_code`),
    KEY `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='下游项目表';

SET FOREIGN_KEY_CHECKS = 1;
