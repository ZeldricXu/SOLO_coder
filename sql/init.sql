CREATE DATABASE IF NOT EXISTS `gateway_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE `gateway_db`;

DROP TABLE IF EXISTS `gw_route_definition`;
CREATE TABLE `gw_route_definition` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) NOT NULL COMMENT '路由ID',
  `uri` VARCHAR(512) NOT NULL COMMENT '目标URI',
  `predicates` TEXT COMMENT '断言定义(JSON)',
  `filters` TEXT COMMENT '过滤器定义(JSON)',
  `metadata` TEXT COMMENT '元数据(JSON)',
  `order_num` INT DEFAULT 0 COMMENT '排序',
  `status` TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
  `match_type` VARCHAR(32) DEFAULT 'PREFIX' COMMENT '匹配类型 PREFIX/REGEX/WEIGHT',
  `weight` INT DEFAULT 100 COMMENT '权重',
  `group_id` VARCHAR(64) COMMENT '分组ID',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_id` (`route_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由定义表';

DROP TABLE IF EXISTS `gw_rate_limit_rule`;
CREATE TABLE `gw_rate_limit_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) NOT NULL COMMENT '路由ID',
  `strategy` VARCHAR(32) NOT NULL DEFAULT 'TOKEN_BUCKET' COMMENT '限流策略 TOKEN_BUCKET/SLIDING_WINDOW',
  `capacity` BIGINT DEFAULT 100 COMMENT '令牌桶容量',
  `refill_rate` BIGINT DEFAULT 10 COMMENT '令牌桶每秒补充速率',
  `window_size` BIGINT DEFAULT 60 COMMENT '滑动窗口大小(秒)',
  `permits` BIGINT DEFAULT 100 COMMENT '窗口内允许的请求数',
  `status` TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_id` (`route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='限流规则表';

DROP TABLE IF EXISTS `gw_circuit_breaker_rule`;
CREATE TABLE `gw_circuit_breaker_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) NOT NULL COMMENT '路由ID',
  `failure_rate_threshold` DOUBLE DEFAULT 50.0 COMMENT '失败率阈值(%)',
  `slow_call_rate_threshold` DOUBLE DEFAULT 60.0 COMMENT '慢调用率阈值(%)',
  `slow_call_duration_threshold` BIGINT DEFAULT 5000 COMMENT '慢调用阈值(ms)',
  `wait_duration_in_open_state` BIGINT DEFAULT 30000 COMMENT '熔断后等待时间(ms)',
  `permitted_number_of_calls_in_half_open_state` INT DEFAULT 10 COMMENT '半开状态允许调用次数',
  `minimum_number_of_calls` INT DEFAULT 20 COMMENT '触发熔断最小请求数',
  `sliding_window_size` INT DEFAULT 100 COMMENT '滑动窗口大小',
  `status` TINYINT DEFAULT 1 COMMENT '状态 1启用 0禁用',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_id` (`route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='熔断规则表';

DROP TABLE IF EXISTS `gw_plugin_config`;
CREATE TABLE `gw_plugin_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `plugin_name` VARCHAR(128) NOT NULL COMMENT '插件名称',
  `plugin_type` VARCHAR(32) NOT NULL COMMENT '插件类型 AUTH/RATE_LIMIT/TRANSFORM/CIRCUIT_BREAKER/IP_FILTER/CUSTOM',
  `route_id` VARCHAR(128) COMMENT '绑定的路由ID(空表示全局)',
  `config` TEXT COMMENT '插件配置(JSON)',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否启用',
  `order_num` INT DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plugin_route` (`plugin_name`, `route_id`),
  KEY `idx_route_id` (`route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='插件配置表';

DROP TABLE IF EXISTS `gw_gray_release_rule`;
CREATE TABLE `gw_gray_release_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) NOT NULL COMMENT '路由ID',
  `gray_version` VARCHAR(64) COMMENT '灰度版本标识',
  `gray_weight` INT DEFAULT 0 COMMENT '灰度流量权重(0-100)',
  `gray_headers` TEXT COMMENT '灰度Header条件(JSON)',
  `gray_params` TEXT COMMENT '灰度参数条件(JSON)',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_route_id` (`route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='灰度发布规则表';

DROP TABLE IF EXISTS `gw_traffic_mirror_rule`;
CREATE TABLE `gw_traffic_mirror_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) NOT NULL COMMENT '路由ID',
  `mirror_target` VARCHAR(512) NOT NULL COMMENT '镜像目标地址',
  `mirror_ratio` DOUBLE DEFAULT 100.0 COMMENT '镜像流量比例(0-100)',
  `mirror_headers` TEXT COMMENT '镜像Header过滤(JSON)',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否启用',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_route_id` (`route_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='流量镜像规则表';

DROP TABLE IF EXISTS `gw_ip_rule`;
CREATE TABLE `gw_ip_rule` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `route_id` VARCHAR(128) COMMENT '路由ID(空表示全局)',
  `ip_or_cidr` VARCHAR(64) NOT NULL COMMENT 'IP或CIDR',
  `rule_type` VARCHAR(16) NOT NULL COMMENT '规则类型 BLACKLIST/WHITELIST',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_route_type` (`route_id`, `rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IP黑白名单表';

DROP TABLE IF EXISTS `gw_api_permission`;
CREATE TABLE `gw_api_permission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `permission_code` VARCHAR(128) NOT NULL COMMENT '权限编码',
  `permission_name` VARCHAR(128) COMMENT '权限名称',
  `path_pattern` VARCHAR(512) NOT NULL COMMENT '路径模式',
  `method` VARCHAR(16) DEFAULT '*' COMMENT '请求方法 *表示全部',
  `parent_id` BIGINT COMMENT '父权限ID',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`permission_code`),
  KEY `idx_path_method` (`path_pattern`(191), `method`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API权限表';

DROP TABLE IF EXISTS `gw_role_permission`;
CREATE TABLE `gw_role_permission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `role_code` VARCHAR(64) NOT NULL COMMENT '角色编码',
  `permission_code` VARCHAR(128) NOT NULL COMMENT '权限编码',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_permission` (`role_code`, `permission_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限关联表';

INSERT INTO `gw_route_definition` (`route_id`, `uri`, `predicates`, `filters`, `metadata`, `order_num`, `status`, `match_type`, `weight`, `group_id`)
VALUES
('user-service', 'lb://user-service', '[{"name":"Path","args":{"pattern":"/api/user/**"}}]', '[{"name":"StripPrefix","args":{"parts":"2"}}]', '{"description":"用户服务"}', 0, 1, 'PREFIX', 100, 'business'),
('order-service', 'lb://order-service', '[{"name":"Path","args":{"pattern":"/api/order/**"}}]', '[{"name":"StripPrefix","args":{"parts":"2"}}]', '{"description":"订单服务"}', 1, 1, 'PREFIX', 100, 'business'),
('gray-demo', 'lb://demo-service-v2', '[{"name":"Path","args":{"pattern":"/api/gray/**"}}]', '[{"name":"StripPrefix","args":{"parts":"2"}}]', '{"description":"灰度演示服务","weight":{"v1":70,"v2":30}}', 2, 1, 'WEIGHT', 100, 'gray');

INSERT INTO `gw_rate_limit_rule` (`route_id`, `strategy`, `capacity`, `refill_rate`, `window_size`, `permits`, `status`)
VALUES
('user-service', 'TOKEN_BUCKET', 100, 20, 60, 1200, 1),
('order-service', 'SLIDING_WINDOW', 50, 10, 60, 600, 1);

INSERT INTO `gw_circuit_breaker_rule` (`route_id`, `failure_rate_threshold`, `slow_call_rate_threshold`, `slow_call_duration_threshold`, `wait_duration_in_open_state`, `permitted_number_of_calls_in_half_open_state`, `minimum_number_of_calls`, `sliding_window_size`, `status`)
VALUES
('user-service', 50.0, 60.0, 5000, 30000, 10, 20, 100, 1),
('order-service', 40.0, 50.0, 3000, 20000, 5, 15, 50, 1);

INSERT INTO `gw_ip_rule` (`route_id`, `ip_or_cidr`, `rule_type`)
VALUES
(NULL, '10.0.0.0/8', 'WHITELIST'),
(NULL, '192.168.0.0/16', 'WHITELIST'),
(NULL, '172.16.0.0/12', 'WHITELIST'),
(NULL, '123.45.67.89', 'BLACKLIST');

INSERT INTO `gw_api_permission` (`permission_code`, `permission_name`, `path_pattern`, `method`, `parent_id`)
VALUES
('user:view', '查看用户', '/api/user/**', 'GET', NULL),
('user:create', '创建用户', '/api/user/**', 'POST', NULL),
('user:update', '更新用户', '/api/user/**', 'PUT', NULL),
('user:delete', '删除用户', '/api/user/**', 'DELETE', NULL),
('order:view', '查看订单', '/api/order/**', 'GET', NULL),
('order:create', '创建订单', '/api/order/**', 'POST', NULL);

INSERT INTO `gw_role_permission` (`role_code`, `permission_code`)
VALUES
('ROLE_ADMIN', 'user:view'),
('ROLE_ADMIN', 'user:create'),
('ROLE_ADMIN', 'user:update'),
('ROLE_ADMIN', 'user:delete'),
('ROLE_ADMIN', 'order:view'),
('ROLE_ADMIN', 'order:create'),
('ROLE_USER', 'user:view'),
('ROLE_USER', 'order:view');

INSERT INTO `gw_plugin_config` (`plugin_name`, `plugin_type`, `route_id`, `config`, `enabled`, `order_num`)
VALUES
('auth-plugin', 'AUTH', NULL, '{}', 1, 0),
('ratelimit-plugin', 'RATE_LIMIT', NULL, '{}', 1, 1),
('transform-plugin', 'TRANSFORM', NULL, '{}', 1, 2),
('circuitbreaker-plugin', 'CIRCUIT_BREAKER', NULL, '{}', 1, 3),
('ipfilter-plugin', 'IP_FILTER', NULL, '{}', 1, -10);
