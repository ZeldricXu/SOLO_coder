CREATE DATABASE IF NOT EXISTS flow_platform DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE flow_platform;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(256) NOT NULL,
    real_name VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    phone VARCHAR(20),
    avatar VARCHAR(512),
    dept_id BIGINT,
    status TINYINT DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status TINYINT DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    dept_name VARCHAR(128) NOT NULL,
    dept_code VARCHAR(64) NOT NULL UNIQUE,
    leader VARCHAR(64),
    sort_order INT DEFAULT 0,
    status TINYINT DEFAULT 1,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT DEFAULT 0,
    perm_code VARCHAR(128) NOT NULL UNIQUE,
    perm_name VARCHAR(128) NOT NULL,
    perm_type TINYINT NOT NULL,
    path VARCHAR(256),
    icon VARCHAR(64),
    sort_order INT DEFAULT 0,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS form_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_key VARCHAR(128) NOT NULL UNIQUE,
    form_name VARCHAR(256) NOT NULL,
    form_desc VARCHAR(1024),
    form_schema JSON NOT NULL,
    version INT DEFAULT 1,
    status TINYINT DEFAULT 0,
    category VARCHAR(64),
    creator_id BIGINT NOT NULL,
    dept_ids JSON,
    role_ids JSON,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS form_field_option (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    form_id BIGINT NOT NULL,
    field_key VARCHAR(128) NOT NULL,
    field_type VARCHAR(64) NOT NULL,
    field_label VARCHAR(256) NOT NULL,
    field_props JSON,
    sort_order INT DEFAULT 0,
    required TINYINT DEFAULT 0,
    visible TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS process_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    process_key VARCHAR(128) NOT NULL UNIQUE,
    process_name VARCHAR(256) NOT NULL,
    process_desc VARCHAR(1024),
    form_id BIGINT NOT NULL,
    bpmn_xml MEDIUMTEXT NOT NULL,
    process_data JSON,
    version INT DEFAULT 1,
    status TINYINT DEFAULT 0,
    category VARCHAR(64),
    creator_id BIGINT NOT NULL,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS process_node_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    process_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_name VARCHAR(256) NOT NULL,
    node_type VARCHAR(64) NOT NULL,
    node_config JSON,
    sort_order INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS process_instance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    process_id BIGINT NOT NULL,
    form_id BIGINT NOT NULL,
    title VARCHAR(512) NOT NULL,
    initiator_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    form_data JSON,
    current_nodes JSON,
    start_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    end_time DATETIME,
    deleted TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS process_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    instance_id BIGINT NOT NULL,
    process_id BIGINT NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    node_name VARCHAR(256),
    node_type VARCHAR(64),
    assignee_id BIGINT,
    assignee_ids JSON,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    action VARCHAR(32),
    comment TEXT,
    due_date DATETIME,
    claim_time DATETIME,
    complete_time DATETIME,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS approval_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    instance_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    comment TEXT,
    signature_url VARCHAR(512),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(256) NOT NULL,
    content TEXT,
    notification_type VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    biz_type VARCHAR(64),
    biz_id BIGINT,
    is_read TINYINT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notification_preference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    enable_in_app TINYINT DEFAULT 1,
    enable_email TINYINT DEFAULT 1,
    enable_wechat TINYINT DEFAULT 0,
    task_arrival TINYINT DEFAULT 1,
    task_timeout TINYINT DEFAULT 1,
    task_complete TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quick_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    content VARCHAR(512) NOT NULL,
    sort_order INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_user (id, username, password, real_name, email, phone, dept_id, status) VALUES
(1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iKtBiMkhcCQ0wOB7jFKnVD2DvzG6', '系统管理员', 'admin@flowplatform.com', '13800138000', 1, 1),
(2, 'zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iKtBiMkhcCQ0wOB7jFKnVD2DvzG6', '张三', 'zhangsan@flowplatform.com', '13800138001', 2, 1),
(3, 'lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iKtBiMkhcCQ0wOB7jFKnVD2DvzG6', '李四', 'lisi@flowplatform.com', '13800138002', 2, 1),
(4, 'wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iKtBiMkhcCQ0wOB7jFKnVD2DvzG6', '王五', 'wangwu@flowplatform.com', '13800138003', 3, 1),
(5, 'zhaoliu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iKtBiMkhcCQ0wOB7jFKnVD2DvzG6', '赵六', 'zhaoliu@flowplatform.com', '13800138004', 3, 1);

INSERT INTO sys_dept (id, parent_id, dept_name, dept_code, leader, sort_order) VALUES
(1, 0, '总公司', 'HQ', 'admin', 1),
(2, 1, '技术研发部', 'DEV', 'zhangsan', 1),
(3, 1, '市场营销部', 'MKT', 'wangwu', 2),
(4, 1, '人力资源部', 'HR', 'lisi', 3),
(5, 1, '财务部', 'FIN', 'zhaoliu', 4);

INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '拥有所有权限'),
(2, 'FORM_DESIGNER', '表单设计师', '可设计和管理表单'),
(3, 'PROCESS_DESIGNER', '流程设计师', '可设计和管理流程'),
(4, 'DEPT_MANAGER', '部门经理', '部门审批权限'),
(5, 'EMPLOYEE', '普通员工', '提交申请和查看');

INSERT INTO sys_user_role (user_id, role_id) VALUES
(1, 1), (2, 2), (2, 3), (3, 4), (4, 4), (5, 5);

INSERT INTO sys_permission (id, parent_id, perm_code, perm_name, perm_type, path, icon, sort_order) VALUES
(1, 0, 'dashboard', '工作台', 1, '/dashboard', 'fa-tachometer-alt', 1),
(2, 0, 'form', '表单管理', 1, '/form', 'fa-wpforms', 2),
(3, 2, 'form:create', '创建表单', 2, NULL, NULL, 1),
(4, 2, 'form:edit', '编辑表单', 2, NULL, NULL, 2),
(5, 2, 'form:delete', '删除表单', 2, NULL, NULL, 3),
(6, 0, 'process', '流程管理', 1, '/process', 'fa-project-diagram', 3),
(7, 6, 'process:create', '创建流程', 2, NULL, NULL, 1),
(8, 6, 'process:edit', '编辑流程', 2, NULL, NULL, 2),
(9, 6, 'process:delete', '删除流程', 2, NULL, NULL, 3),
(10, 0, 'approval', '审批中心', 1, '/approval', 'fa-check-circle', 4),
(11, 0, 'report', '数据报表', 1, '/report', 'fa-chart-bar', 5),
(12, 0, 'permission', '权限管理', 1, '/permission', 'fa-shield-alt', 6),
(13, 0, 'notification', '消息中心', 1, '/notification', 'fa-bell', 7);

INSERT INTO quick_comment (user_id, content, sort_order) VALUES
(1, '同意', 1), (1, '已确认，同意', 2), (1, '情况属实，同意', 3),
(1, '退回修改', 4), (1, '请补充材料后重新提交', 5);
