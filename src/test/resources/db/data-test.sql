INSERT INTO `sys_role` (`role_name`, `role_code`, `description`, `sort_order`, `status`, `created_at`, `updated_at`, `deleted`) VALUES
('超级管理员', 'ROLE_ADMIN', '系统超级管理员，拥有所有权限', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('普通用户', 'ROLE_USER', '普通用户，拥有基础查看和操作权限', 2, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('访客', 'ROLE_GUEST', '访客用户，仅拥有公开内容查看权限', 3, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO `sys_user` (`username`, `password`, `nickname`, `email`, `avatar`, `phone`, `status`, `department`, `position`, `created_at`, `updated_at`, `deleted`) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '系统管理员', 'admin@designsystem.com', null, '13800000001', 1, '技术部', '架构师', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('user', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '普通用户', 'user@designsystem.com', null, '13800000002', 1, '设计部', '设计师', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
('guest', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '访客用户', 'guest@designsystem.com', null, null, 1, null, null, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO `sys_user_role` (`user_id`, `role_id`, `created_at`, `updated_at`, `deleted`) VALUES
(1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(2, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(3, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO `ds_component` (`name`, `display_name`, `description`, `category`, `tags`, `framework`, `maintainer_id`, `latest_version`, `git_repository`, `npm_package`, `preview_url`, `screenshot_url`, `readme_content`, `status`, `published`, `created_at`, `updated_at`, `created_by`, `deleted`) VALUES
('Button', '按钮', '基础按钮组件，支持多种类型和尺寸', '基础组件', 'button,ui,basic', 'REACT', 1, '1.0.0', 'https://github.com/design-system/button', '@ds/button', '/preview/button/1.0.0', null, '# Button 按钮组件\n\n基础按钮组件，用于触发操作。', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('Input', '输入框', '基础文本输入框组件', '基础组件', 'input,form,ui', 'REACT', 1, '1.2.0', 'https://github.com/design-system/input', '@ds/input', '/preview/input/1.2.0', null, '# Input 输入框组件\n\n基础文本输入框组件。', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('Table', '表格', '数据表格组件，支持排序、筛选、分页', '数据展示', 'table,data,grid', 'VUE', 2, '2.0.0', 'https://github.com/design-system/table', '@ds/table-vue', '/preview/table/2.0.0', null, '# Table 表格组件\n\n功能强大的数据表格组件。', 1, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2, 0),
('Modal', '弹窗', '模态对话框组件', '反馈组件', 'modal,dialog,popup', 'REACT', 1, '1.1.0', 'https://github.com/design-system/modal', '@ds/modal', '/preview/modal/1.1.0', null, '# Modal 弹窗组件\n\n模态对话框组件。', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0);

INSERT INTO `ds_component_version` (`component_id`, `version`, `changelog`, `release_notes`, `source_code_path`, `compiled_code_path`, `preview_html_path`, `commit_hash`, `is_latest`, `is_prerelease`, `created_at`, `updated_at`, `created_by`, `deleted`) VALUES
(1, '1.0.0', '初始版本发布', '第一个正式版本', '/components/button/src', '/components/button/dist', '/preview/button/1.0.0/index.html', 'abc123def456', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
(2, '1.0.0', '初始版本', '初始版本发布', '/components/input/src/v1.0.0', '/components/input/dist/v1.0.0', null, 'def456abc123', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
(2, '1.2.0', '新增支持placeholder属性，修复IE11兼容性问题', '功能增强版本', '/components/input/src/v1.2.0', '/components/input/dist/v1.2.0', '/preview/input/1.2.0/index.html', 'ghi789def456', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
(3, '2.0.0', '重构虚拟滚动，性能提升50%，API简化', '重大更新版本', '/components/table/src/v2.0.0', '/components/table/dist/v2.0.0', '/preview/table/2.0.0/index.html', 'jkl012ghi789', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2, 0),
(4, '1.0.0', '初始版本', '第一个版本', '/components/modal/src/v1.0.0', null, null, 'mno345jkl012', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
(4, '1.1.0', '新增拖拽功能，修复遮罩层点击穿透问题', '功能增强', '/components/modal/src/v1.1.0', '/components/modal/dist/v1.1.0', '/preview/modal/1.1.0/index.html', 'pqr678mno345', 1, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0);

INSERT INTO `ds_component_prop` (`component_version_id`, `name`, `prop_type`, `default_value`, `description`, `required`, `sort_order`, `created_at`, `updated_at`, `deleted`) VALUES
(1, 'type', 'string', 'default', '按钮类型：default|primary|danger|warning', 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1, 'size', 'string', 'medium', '按钮尺寸：small|medium|large', 0, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1, 'disabled', 'boolean', 'false', '是否禁用', 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(1, 'onClick', 'function', null, '点击回调函数', 0, 4, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(3, 'placeholder', 'string', null, '占位文本', 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
(3, 'maxLength', 'number', null, '最大输入长度', 0, 2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO `ds_design_token` (`token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `inherits_from`, `category`, `tags`, `status`, `created_at`, `updated_at`, `created_by`, `deleted`) VALUES
('--color-primary-500', '主题色', '主品牌色，用于主要操作按钮、链接等', 'color', 'global', '#1677ff', null, '颜色/主题色', 'primary,color,brand', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--color-primary-600', '主题色悬浮态', '主题色的悬浮状态', 'color', 'global', '#4096ff', '--color-primary-500', '颜色/主题色', 'primary,color,hover', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--color-success-500', '成功色', '成功状态色', 'color', 'global', '#52c41a', null, '颜色/状态色', 'success,color,status', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--color-warning-500', '警告色', '警告状态色', 'color', 'global', '#faad14', null, '颜色/状态色', 'warning,color,status', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--color-danger-500', '危险色', '危险/错误状态色', 'color', 'global', '#ff4d4f', null, '颜色/状态色', 'danger,error,color', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--color-text-primary', '主文本色', '主要文本颜色', 'color', 'semantic', 'rgba(0, 0, 0, 0.88)', null, '颜色/文本色', 'text,color,typography', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--spacing-base', '基础间距', '基础间距单位', 'spacing', 'global', '4px', null, '间距/基础', 'spacing,layout', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--spacing-md', '中等间距', '中等大小间距', 'spacing', 'semantic', '16px', '--spacing-base', '间距/语义', 'spacing,layout,medium', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--font-size-base', '基础字号', '基础字体大小', 'typography', 'global', '14px', null, '字体/基础', 'font,typography,size', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('--radius-md', '中等圆角', '中等大小圆角半径', 'border', 'semantic', '8px', null, '边框/圆角', 'radius,border,corner', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0);

INSERT INTO `ds_project` (`project_name`, `project_code`, `description`, `git_repository`, `git_branch`, `tech_stack`, `contact_person`, `contact_email`, `subscription_status`, `created_at`, `updated_at`, `created_by`, `deleted`) VALUES
('电商平台', 'ecommerce-web', '公司核心电商平台前端项目', 'https://github.com/company/ecommerce-web', 'main', 'REACT', '张三', 'zhangsan@company.com', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0),
('管理后台', 'admin-console', '内部运营管理后台', 'https://github.com/company/admin-console', 'develop', 'VUE', '李四', 'lisi@company.com', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 2, 0),
('营销活动页', 'marketing-h5', '营销活动H5页面生成器', 'https://github.com/company/marketing-h5', 'main', 'REACT', '王五', 'wangwu@company.com', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, 0);
