SET MODE MySQL;
SET DATABASE_TO_LOWER TRUE;

DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `ds_component_token_usage`;
DROP TABLE IF EXISTS `ds_component_prop`;
DROP TABLE IF EXISTS `ds_component_doc`;
DROP TABLE IF EXISTS `ds_component_version`;
DROP TABLE IF EXISTS `ds_component`;
DROP TABLE IF EXISTS `ds_token_override`;
DROP TABLE IF EXISTS `ds_token_change`;
DROP TABLE IF EXISTS `ds_design_token`;
DROP TABLE IF EXISTS `ds_approval_request`;
DROP TABLE IF EXISTS `ds_changelog`;
DROP TABLE IF EXISTS `ds_project`;

CREATE TABLE `sys_user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `username` VARCHAR(64) NOT NULL,
    `password` VARCHAR(128) NOT NULL,
    `nickname` VARCHAR(64),
    `email` VARCHAR(128),
    `avatar` VARCHAR(255),
    `phone` VARCHAR(32),
    `status` TINYINT DEFAULT 1,
    `department` VARCHAR(64),
    `position` VARCHAR(64),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`username`)
);

CREATE TABLE `sys_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `role_name` VARCHAR(64) NOT NULL,
    `role_code` VARCHAR(64) NOT NULL,
    `description` VARCHAR(255),
    `sort_order` INT DEFAULT 0,
    `status` TINYINT DEFAULT 1,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`role_code`)
);

CREATE TABLE `sys_user_role` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL,
    `role_id` BIGINT NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_component` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(64) NOT NULL,
    `display_name` VARCHAR(64),
    `description` CLOB,
    `category` VARCHAR(64),
    `tags` VARCHAR(255),
    `framework` VARCHAR(16),
    `maintainer_id` BIGINT,
    `latest_version` VARCHAR(32),
    `git_repository` VARCHAR(255),
    `npm_package` VARCHAR(128),
    `preview_url` VARCHAR(255),
    `screenshot_url` VARCHAR(255),
    `readme_content` CLOB,
    `status` TINYINT DEFAULT 1,
    `published` TINYINT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`name`, `framework`)
);

CREATE TABLE `ds_component_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `component_id` BIGINT NOT NULL,
    `version` VARCHAR(32) NOT NULL,
    `changelog` CLOB,
    `release_notes` CLOB,
    `source_code_path` VARCHAR(255),
    `compiled_code_path` VARCHAR(255),
    `preview_html_path` VARCHAR(255),
    `commit_hash` VARCHAR(64),
    `is_latest` TINYINT DEFAULT 0,
    `is_prerelease` TINYINT DEFAULT 0,
    `deprecated_reason` VARCHAR(255),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`component_id`, `version`)
);

CREATE TABLE `ds_component_prop` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `component_version_id` BIGINT NOT NULL,
    `name` VARCHAR(64) NOT NULL,
    `prop_type` VARCHAR(128),
    `default_value` VARCHAR(255),
    `description` VARCHAR(512),
    `required` TINYINT DEFAULT 0,
    `possible_values` CLOB,
    `sort_order` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_component_doc` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `component_version_id` BIGINT NOT NULL,
    `title` VARCHAR(128),
    `doc_type` VARCHAR(32),
    `content` CLOB,
    `example_code` CLOB,
    `preview_url` VARCHAR(255),
    `sort_order` INT DEFAULT 0,
    `indexed` TINYINT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_design_token` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `token_name` VARCHAR(128) NOT NULL,
    `display_name` VARCHAR(64),
    `description` VARCHAR(512),
    `token_type` VARCHAR(32),
    `token_level` VARCHAR(32),
    `base_value` VARCHAR(512),
    `inherits_from` VARCHAR(128),
    `category` VARCHAR(64),
    `tags` VARCHAR(255),
    `status` TINYINT DEFAULT 1,
    `deprecated_by` VARCHAR(128),
    `deprecation_reason` VARCHAR(255),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`token_name`)
);

CREATE TABLE `ds_token_override` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `token_id` BIGINT NOT NULL,
    `override_value` VARCHAR(512),
    `scope` VARCHAR(64),
    `scope_type` VARCHAR(32),
    `theme` VARCHAR(32),
    `breakpoint` VARCHAR(32),
    `priority` INT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_component_token_usage` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `component_id` BIGINT NOT NULL,
    `token_id` BIGINT NOT NULL,
    `css_property` VARCHAR(128),
    `usage_location` VARCHAR(255),
    `usage_context` VARCHAR(255),
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_token_change` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `token_id` BIGINT NOT NULL,
    `change_type` VARCHAR(32),
    `old_name` VARCHAR(128),
    `new_name` VARCHAR(128),
    `old_value` VARCHAR(512),
    `new_value` VARCHAR(512),
    `migration_guide` CLOB,
    `breaking_change` TINYINT DEFAULT 0,
    `affected_components` CLOB,
    `affected_pages` CLOB,
    `approval_request_id` BIGINT,
    `effective_date` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_approval_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `request_type` VARCHAR(32),
    `target_id` BIGINT,
    `target_type` VARCHAR(32),
    `title` VARCHAR(128),
    `description` CLOB,
    `change_content` CLOB,
    `approver_id` BIGINT,
    `status` VARCHAR(16) DEFAULT 'PENDING',
    `approval_comment` CLOB,
    `approved_at` TIMESTAMP,
    `reject_reason` VARCHAR(512),
    `submitted_by` BIGINT,
    `submitted_at` TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_changelog` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `component_id` BIGINT,
    `version` VARCHAR(32),
    `commit_type` VARCHAR(32),
    `commit_scope` VARCHAR(64),
    `commit_subject` VARCHAR(255),
    `commit_body` CLOB,
    `breaking_change` CLOB,
    `commit_hash` VARCHAR(64),
    `author` VARCHAR(64),
    `author_email` VARCHAR(128),
    `committed_at` TIMESTAMP,
    `included_in_release` TINYINT DEFAULT 0,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`)
);

CREATE TABLE `ds_project` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `project_name` VARCHAR(64) NOT NULL,
    `project_code` VARCHAR(64) NOT NULL,
    `description` CLOB,
    `git_repository` VARCHAR(255),
    `git_branch` VARCHAR(64),
    `tech_stack` VARCHAR(32),
    `contact_person` VARCHAR(64),
    `contact_email` VARCHAR(128),
    `subscription_status` TINYINT DEFAULT 1,
    `webhook_url` VARCHAR(255),
    `notification_config` CLOB,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `created_by` BIGINT,
    `updated_by` BIGINT,
    `deleted` TINYINT DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE (`project_code`)
);
