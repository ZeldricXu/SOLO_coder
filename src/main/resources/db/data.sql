-- ============================================================
-- 设计系统管理平台 - 初始化数据
-- ============================================================

-- ------------------------------------------------------------
-- 角色数据
-- ------------------------------------------------------------
INSERT INTO `sys_role` (`id`, `role_name`, `role_code`, `description`, `sort_order`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, '超级管理员', 'ADMIN', '系统超级管理员，拥有所有权限', 1, 1, NOW(), NOW(), 1, 1, 0),
(2, '设计负责人', 'DESIGN_LEAD', '设计团队负责人，负责设计令牌审批', 2, 1, NOW(), NOW(), 1, 1, 0),
(3, '设计师', 'DESIGNER', '普通设计师，可查看和申请修改令牌', 3, 1, NOW(), NOW(), 1, 1, 0),
(4, '开发者', 'DEVELOPER', '前端开发者，负责组件维护和发布', 4, 1, NOW(), NOW(), 1, 1, 0),
(5, 'Code Reviewer', 'CODE_REVIEWER', '代码审查员，负责组件发布审批', 5, 1, NOW(), NOW(), 1, 1, 0),
(6, '普通用户', 'USER', '普通用户，可浏览组件和文档', 6, 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 用户数据 (密码: BCrypt加密)
-- admin / admin123
-- developer / dev123
-- designer / design123
-- ------------------------------------------------------------
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `email`, `status`, `department`, `position`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '系统管理员', 'admin@designsystem.com', 1, '平台架构组', '架构师', NOW(), NOW(), 1, 1, 0),
(2, 'developer', '$2a$10$6P5zT4QJz5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y', '前端开发', 'dev@designsystem.com', 1, '前端架构组', '高级前端工程师', NOW(), NOW(), 1, 1, 0),
(3, 'designer', '$2a$10$6P5zT4QJz5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y', 'UI设计师', 'design@designsystem.com', 1, '设计中心', '高级UI设计师', NOW(), NOW(), 1, 1, 0),
(4, 'reviewer', '$2a$10$6P5zT4QJz5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y', '代码审查员', 'review@designsystem.com', 1, '前端架构组', '技术专家', NOW(), NOW(), 1, 1, 0),
(5, 'lead_design', '$2a$10$6P5zT4QJz5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y5Q4J8z5Y5QeT5Y', '设计负责人', 'leaddesign@designsystem.com', 1, '设计中心', '设计总监', NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 用户角色关联
-- ------------------------------------------------------------
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 1, 1, NOW(), NOW(), 1, 1, 0),
(2, 2, 4, NOW(), NOW(), 1, 1, 0),
(3, 3, 3, NOW(), NOW(), 1, 1, 0),
(4, 4, 5, NOW(), NOW(), 1, 1, 0),
(5, 5, 2, NOW(), NOW(), 1, 1, 0),
(6, 2, 5, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 基础颜色令牌 (BASE level)
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, '--color-indigo-50', '靛蓝50', '靛蓝色系最浅色', 'COLOR', 'BASE', '#eef2ff', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(2, '--color-indigo-100', '靛蓝100', '靛蓝色系浅色调', 'COLOR', 'BASE', '#e0e7ff', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(3, '--color-indigo-200', '靛蓝200', '靛蓝色系较浅色调', 'COLOR', 'BASE', '#c7d2fe', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(4, '--color-indigo-300', '靛蓝300', '靛蓝色系中浅色调', 'COLOR', 'BASE', '#a5b4fc', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(5, '--color-indigo-400', '靛蓝400', '靛蓝色系中色调', 'COLOR', 'BASE', '#818cf8', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(6, '--color-indigo-500', '靛蓝500', '靛蓝色系标准色', 'COLOR', 'BASE', '#6366f1', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(7, '--color-indigo-600', '靛蓝600', '靛蓝色系较深色调', 'COLOR', 'BASE', '#4f46e5', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(8, '--color-indigo-700', '靛蓝700', '靛蓝色系深色调', 'COLOR', 'BASE', '#4338ca', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(9, '--color-indigo-800', '靛蓝800', '靛蓝色系很深色调', 'COLOR', 'BASE', '#3730a3', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(10, '--color-indigo-900', '靛蓝900', '靛蓝色系最深色', 'COLOR', 'BASE', '#312e81', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),

(11, '--color-green-500', '绿色500', '绿色系标准色', 'COLOR', 'BASE', '#22c55e', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(12, '--color-green-600', '绿色600', '绿色系深色调', 'COLOR', 'BASE', '#16a34a', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(13, '--color-red-500', '红色500', '红色系标准色', 'COLOR', 'BASE', '#ef4444', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(14, '--color-red-600', '红色600', '红色系深色调', 'COLOR', 'BASE', '#dc2626', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(15, '--color-amber-500', '琥珀500', '琥珀色标准色', 'COLOR', 'BASE', '#f59e0b', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(16, '--color-blue-500', '蓝色500', '蓝色标准色', 'COLOR', 'BASE', '#3b82f6', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(17, '--color-gray-50', '灰色50', '灰色系最浅色', 'COLOR', 'BASE', '#f9fafb', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(18, '--color-gray-100', '灰色100', '灰色系浅色调', 'COLOR', 'BASE', '#f3f4f6', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(19, '--color-gray-200', '灰色200', '灰色系较浅色调', 'COLOR', 'BASE', '#e5e7eb', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(20, '--color-gray-300', '灰色300', '灰色系中浅色调', 'COLOR', 'BASE', '#d1d5db', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(21, '--color-gray-500', '灰色500', '灰色系标准色', 'COLOR', 'BASE', '#6b7280', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(22, '--color-gray-700', '灰色700', '灰色系深色调', 'COLOR', 'BASE', '#374151', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(23, '--color-gray-900', '灰色900', '灰色系最深色', 'COLOR', 'BASE', '#111827', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(24, '--color-white', '白色', '纯白色', 'COLOR', 'BASE', '#ffffff', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(25, '--color-black', '黑色', '纯黑色', 'COLOR', 'BASE', '#000000', '颜色系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 语义化颜色令牌 (SEMANTIC level) - 继承基础令牌
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `inherits_from`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(26, '--color-primary', '主色', '品牌主色调', 'COLOR', 'SEMANTIC', '#6366f1', '--color-indigo-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(27, '--color-primary-hover', '主色悬停', '主色调悬停状态', 'COLOR', 'SEMANTIC', '#4f46e5', '--color-indigo-600', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(28, '--color-primary-active', '主色激活', '主色调激活状态', 'COLOR', 'SEMANTIC', '#4338ca', '--color-indigo-700', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(29, '--color-success', '成功色', '成功状态提示色', 'COLOR', 'SEMANTIC', '#22c55e', '--color-green-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(30, '--color-success-hover', '成功色悬停', '成功状态悬停色', 'COLOR', 'SEMANTIC', '#16a34a', '--color-green-600', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(31, '--color-danger', '危险色', '危险/错误状态提示色', 'COLOR', 'SEMANTIC', '#ef4444', '--color-red-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(32, '--color-danger-hover', '危险色悬停', '危险状态悬停色', 'COLOR', 'SEMANTIC', '#dc2626', '--color-red-600', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(33, '--color-warning', '警告色', '警告状态提示色', 'COLOR', 'SEMANTIC', '#f59e0b', '--color-amber-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(34, '--color-info', '信息色', '信息提示色', 'COLOR', 'SEMANTIC', '#3b82f6', '--color-blue-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),

(35, '--color-text-primary', '主要文字色', '主要文字颜色', 'COLOR', 'SEMANTIC', '#111827', '--color-gray-900', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(36, '--color-text-secondary', '次要文字色', '次要文字颜色', 'COLOR', 'SEMANTIC', '#6b7280', '--color-gray-500', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(37, '--color-bg-primary', '主要背景色', '主要背景颜色', 'COLOR', 'SEMANTIC', '#ffffff', '--color-white', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(38, '--color-bg-secondary', '次要背景色', '次要背景颜色', 'COLOR', 'SEMANTIC', '#f9fafb', '--color-gray-50', '颜色系统', 1, NOW(), NOW(), 1, 1, 0),
(39, '--color-border', '边框色', '通用边框颜色', 'COLOR', 'SEMANTIC', '#d1d5db', '--color-gray-300', '颜色系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 间距令牌
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(40, '--spacing-1', '间距1', '最小间距', 'SPACING', 'BASE', '4px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(41, '--spacing-2', '间距2', '小间距', 'SPACING', 'BASE', '8px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(42, '--spacing-3', '间距3', '中小间距', 'SPACING', 'BASE', '12px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(43, '--spacing-4', '间距4', '标准间距', 'SPACING', 'BASE', '16px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(44, '--spacing-5', '间距5', '中大间距', 'SPACING', 'BASE', '20px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(45, '--spacing-6', '间距6', '大间距', 'SPACING', 'BASE', '24px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(46, '--spacing-8', '间距8', '更大间距', 'SPACING', 'BASE', '32px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(47, '--spacing-10', '间距10', '大间距', 'SPACING', 'BASE', '40px', '间距系统', 1, NOW(), NOW(), 1, 1, 0),
(48, '--spacing-12', '间距12', '超大间距', 'SPACING', 'BASE', '48px', '间距系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 圆角令牌
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(49, '--border-radius-sm', '小圆角', '小圆角', 'BORDER_RADIUS', 'BASE', '4px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0),
(50, '--border-radius-md', '中圆角', '标准圆角', 'BORDER_RADIUS', 'BASE', '8px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0),
(51, '--border-radius-lg', '大圆角', '大圆角', 'BORDER_RADIUS', 'BASE', '12px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0),
(52, '--border-radius-xl', '特大圆角', '特大圆角', 'BORDER_RADIUS', 'BASE', '16px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0),
(53, '--border-radius-2xl', '超大圆角', '超大圆角', 'BORDER_RADIUS', 'BASE', '24px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0),
(54, '--border-radius-full', '全圆角', '全圆角(圆形)', 'BORDER_RADIUS', 'BASE', '9999px', '圆角系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 阴影令牌
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(55, '--box-shadow-sm', '小阴影', '轻微阴影', 'BOX_SHADOW', 'BASE', '0 1px 2px 0 rgb(0 0 0 / 0.05)', '阴影系统', 1, NOW(), NOW(), 1, 1, 0),
(56, '--box-shadow-md', '中阴影', '标准阴影', 'BOX_SHADOW', 'BASE', '0 4px 6px -1px rgb(0 0 0 / 0.1)', '阴影系统', 1, NOW(), NOW(), 1, 1, 0),
(57, '--box-shadow-lg', '大阴影', '较大阴影', 'BOX_SHADOW', 'BASE', '0 10px 15px -3px rgb(0 0 0 / 0.1)', '阴影系统', 1, NOW(), NOW(), 1, 1, 0),
(58, '--box-shadow-xl', '特大阴影', '大阴影', 'BOX_SHADOW', 'BASE', '0 20px 25px -5px rgb(0 0 0 / 0.1)', '阴影系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 字体令牌
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(59, '--font-family-sans', '无衬线字体', '默认无衬线字体', 'FONT', 'BASE', '-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(60, '--font-family-mono', '等宽字体', '代码用等宽字体', 'FONT', 'BASE', 'Monaco, Consolas, \"Courier New\"', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(61, '--font-size-xs', '超小字号', '超小字体大小', 'FONT', 'BASE', '12px', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(62, '--font-size-sm', '小字号', '小字体大小', 'FONT', 'BASE', '14px', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(63, '--font-size-base', '标准字号', '标准字体大小', 'FONT', 'BASE', '16px', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(64, '--font-size-lg', '大字号', '大字体大小', 'FONT', 'BASE', '18px', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(65, '--font-size-xl', '特大字号', '特大字体大小', 'FONT', 'BASE', '20px', '字体系统', 1, NOW(), NOW(), 1, 1, 0),
(66, '--font-size-2xl', '超大字号', '超大字体大小', 'FONT', 'BASE', '24px', '字体系统', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件级颜色令牌 (COMPONENT level)
-- ------------------------------------------------------------
INSERT INTO `ds_design_token` (`id`, `token_name`, `display_name`, `description`, `token_type`, `token_level`, `base_value`, `inherits_from`, `category`, `status`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(67, '--btn-bg-primary', '按钮主色背景', '按钮主色背景色', 'COLOR', 'COMPONENT', '#6366f1', '--color-primary', 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(68, '--btn-bg-primary-hover', '按钮主色悬停背景', '按钮主色悬停背景色', 'COLOR', 'COMPONENT', '#4f46e5', '--color-primary-hover', 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(69, '--btn-text-primary', '按钮主色文字', '按钮主色文字颜色', 'COLOR', 'COMPONENT', '#ffffff', '--color-white', 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(70, '--btn-height-sm', '按钮小高度', '小按钮高度', 'SPACING', 'COMPONENT', '28px', NULL, 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(71, '--btn-height-md', '按钮标准高度', '标准按钮高度', 'SPACING', 'COMPONENT', '36px', NULL, 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(72, '--btn-height-lg', '按钮大高度', '大按钮高度', 'SPACING', 'COMPONENT', '44px', NULL, 'Button组件', 1, NOW(), NOW(), 1, 1, 0),
(73, '--btn-radius', '按钮圆角', '按钮圆角大小', 'BORDER_RADIUS', 'COMPONENT', '8px', '--border-radius-md', 'Button组件', 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件数据 (React版本)
-- ------------------------------------------------------------
INSERT INTO `ds_component` (`id`, `name`, `display_name`, `description`, `category`, `tags`, `framework`, `maintainer_id`, `latest_version`, `git_repository`, `npm_package`, `status`, `published`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 'button', 'Button 按钮', '按钮组件，用于触发操作。支持多种类型、大小和状态。', '基础组件', '基础,交互,表单', 'REACT', 2, 'v2.1.0', 'https://github.com/design-system/button', '@design-system/button', 1, 1, NOW(), NOW(), 1, 1, 0),
(2, 'input', 'Input 输入框', '文本输入框组件，支持多种类型和验证。', '表单组件', '表单,输入,基础', 'REACT', 2, 'v1.5.2', 'https://github.com/design-system/input', '@design-system/input', 1, 1, NOW(), NOW(), 1, 1, 0),
(3, 'select', 'Select 选择器', '下拉选择器组件，支持单选、多选和搜索。', '表单组件', '表单,选择,基础', 'REACT', 2, 'v1.3.0', 'https://github.com/design-system/select', '@design-system/select', 1, 1, NOW(), NOW(), 1, 1, 0),
(4, 'table', 'Table 表格', '数据表格组件，支持排序、筛选、分页。', '数据展示', '表格,数据,高级', 'REACT', 2, 'v3.0.0', 'https://github.com/design-system/table', '@design-system/table', 1, 1, NOW(), NOW(), 1, 1, 0),
(5, 'modal', 'Modal 弹窗', '模态弹窗组件，支持自定义内容和操作。', '反馈组件', '弹窗,反馈,交互', 'REACT', 2, 'v1.2.0', 'https://github.com/design-system/modal', '@design-system/modal', 1, 1, NOW(), NOW(), 1, 1, 0),
(6, 'tabs', 'Tabs 标签页', '标签页切换组件，支持动态切换。', '导航组件', '标签,导航,基础', 'REACT', 2, 'v1.1.0', 'https://github.com/design-system/tabs', '@design-system/tabs', 1, 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件数据 (Vue版本)
-- ------------------------------------------------------------
INSERT INTO `ds_component` (`id`, `name`, `display_name`, `description`, `category`, `tags`, `framework`, `maintainer_id`, `latest_version`, `git_repository`, `npm_package`, `status`, `published`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(7, 'button', 'Button 按钮', '按钮组件，用于触发操作。支持多种类型、大小和状态。', '基础组件', '基础,交互,表单', 'VUE', 2, 'v2.1.0', 'https://github.com/design-system/vue-button', '@design-system/vue-button', 1, 1, NOW(), NOW(), 1, 1, 0),
(8, 'input', 'Input 输入框', '文本输入框组件，支持多种类型和验证。', '表单组件', '表单,输入,基础', 'VUE', 2, 'v1.5.2', 'https://github.com/design-system/vue-input', '@design-system/vue-input', 1, 1, NOW(), NOW(), 1, 1, 0),
(9, 'card', 'Card 卡片', '卡片容器组件，用于展示内容。', '数据展示', '卡片,容器,基础', 'REACT', 2, 'v1.0.0', 'https://github.com/design-system/card', '@design-system/card', 1, 0, NOW(), NOW(), 1, 1, 0),
(10, 'breadcrumb', 'Breadcrumb 面包屑', '面包屑导航组件，显示当前页面位置。', '导航组件', '面包屑,导航', 'REACT', 2, 'v1.0.0', 'https://github.com/design-system/breadcrumb', '@design-system/breadcrumb', 1, 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件版本数据
-- ------------------------------------------------------------
INSERT INTO `ds_component_version` (`id`, `component_id`, `version`, `changelog`, `release_notes`, `commit_hash`, `is_latest`, `is_prerelease`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 1, 'v2.1.0', '新增加载状态和禁用样式', '新增 loading 属性控制按钮加载状态，新增 disabled 属性控制按钮禁用状态', 'a1b2c3d4e5f6', 1, 0, NOW(), NOW(), 1, 1, 0),
(2, 1, 'v2.0.0', '重构组件API，支持多种按钮类型', '破坏性变更：重构了组件的props接口', 'g7h8i9j0k1l2', 0, 0, NOW(), NOW(), 1, 1, 0),
(3, 1, 'v1.5.0', '新增图标按钮支持', '新增 icon 属性支持自定义图标', 'm3n4o5p6q7r8', 0, 0, NOW(), NOW(), 1, 1, 0),
(4, 2, 'v1.5.2', '修复聚焦状态边框颜色问题', '修复在某些浏览器下聚焦状态边框颜色不正确的问题', 's9t0u1v2w3x4', 1, 0, NOW(), NOW(), 1, 1, 0),
(5, 4, 'v3.0.0', '表格性能优化，支持虚拟滚动', '新增虚拟滚动支持，大幅提升大数据量渲染性能', 'y5z6a7b8c9d0', 1, 0, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件属性数据
-- ------------------------------------------------------------
INSERT INTO `ds_component_prop` (`id`, `component_version_id`, `name`, `prop_type`, `default_value`, `description`, `required`, `possible_values`, `sort_order`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 1, 'type', 'string', '\'primary\'', '按钮类型', 0, '[\"primary\",\"default\",\"secondary\",\"danger\"]', 1, NOW(), NOW(), 1, 1, 0),
(2, 1, 'size', 'string', '\'medium\'', '按钮尺寸', 0, '[\"small\",\"medium\",\"large\"]', 2, NOW(), NOW(), 1, 1, 0),
(3, 1, 'disabled', 'boolean', 'false', '是否禁用', 0, '[true,false]', 3, NOW(), NOW(), 1, 1, 0),
(4, 1, 'loading', 'boolean', 'false', '是否加载中', 0, '[true,false]', 4, NOW(), NOW(), 1, 1, 0),
(5, 1, 'onClick', 'function', 'undefined', '点击事件回调', 0, NULL, 5, NOW(), NOW(), 1, 1, 0),
(6, 4, 'placeholder', 'string', 'undefined', '占位符文本', 0, NULL, 1, NOW(), NOW(), 1, 1, 0),
(7, 4, 'value', 'string', 'undefined', '输入框值', 0, NULL, 2, NOW(), NOW(), 1, 1, 0),
(8, 4, 'onChange', 'function', 'undefined', '值变化回调', 0, NULL, 3, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 组件令牌使用关联
-- ------------------------------------------------------------
INSERT INTO `ds_component_token_usage` (`id`, `component_id`, `token_id`, `css_property`, `usage_location`, `usage_context`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 1, 67, 'background-color', '按钮背景', 'primary类型按钮', NOW(), NOW(), 1, 1, 0),
(2, 1, 68, 'background-color', '按钮悬停背景', 'primary类型按钮hover状态', NOW(), NOW(), 1, 1, 0),
(3, 1, 69, 'color', '按钮文字', 'primary类型按钮文字颜色', NOW(), NOW(), 1, 1, 0),
(4, 1, 73, 'border-radius', '按钮圆角', '所有类型按钮', NOW(), NOW(), 1, 1, 0),
(5, 2, 39, 'border-color', '输入框边框', '默认状态', NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 审批请求数据
-- ------------------------------------------------------------
INSERT INTO `ds_approval_request` (`id`, `request_type`, `target_id`, `target_type`, `title`, `description`, `approver_id`, `status`, `submitted_by`, `submitted_at`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 'COMPONENT_PUBLISH', 9, 'COMPONENT', 'Card 组件 v1.0.0 发布申请', '新增 Card 卡片组件，支持自定义标题、内容和操作区域。需要代码审查。', 4, 'PENDING', 2, NOW(), NOW(), NOW(), 1, 1, 0),
(2, 'TOKEN_CHANGE', 6, 'TOKEN', '主色值调整申请', '将主色由 #4f46e5 调整为 #6366f1，更符合新的品牌视觉规范。需要设计负责人审批。', 5, 'PENDING', 3, NOW(), NOW(), NOW(), 1, 1, 0),
(3, 'COMPONENT_PUBLISH', 1, 'COMPONENT', 'Button 组件 v2.2.0 发布申请', '新增 block 属性支持按钮宽度自适应父容器。', 4, 'PENDING', 2, NOW(), NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 变更日志数据
-- ------------------------------------------------------------
INSERT INTO `ds_changelog` (`id`, `component_id`, `version`, `commit_type`, `commit_scope`, `commit_subject`, `commit_body`, `commit_hash`, `author`, `author_email`, `committed_at`, `included_in_release`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, 1, 'v2.1.0', 'feat', 'button', '新增加载状态和禁用样式', '新增 loading 属性控制按钮加载状态，新增 disabled 属性控制按钮禁用状态', 'a1b2c3d', '张三', 'zhangsan@example.com', NOW(), 1, NOW(), NOW(), 1, 1, 0),
(2, 2, 'v1.5.2', 'fix', 'input', '修复聚焦状态边框颜色问题', '修复在Safari浏览器下聚焦状态边框颜色不正确的问题', 'e5f6g7h', '李四', 'lisi@example.com', NOW(), 1, NOW(), NOW(), 1, 1, 0),
(3, 4, 'v3.0.0', 'feat', 'table', '表格性能优化，支持虚拟滚动', '新增虚拟滚动支持，大幅提升大数据量渲染性能。\n\nBREAKING CHANGE: 移除了 legacy 属性', 'i8j9k0l', '王五', 'wangwu@example.com', NOW(), 1, NOW(), NOW(), 1, 1, 0),
(4, 1, 'v2.1.0', 'docs', 'readme', '更新README文档', '完善组件使用示例和API文档', 'm2n3o4p', '张三', 'zhangsan@example.com', NOW(), 1, NOW(), NOW(), 1, 1, 0);

-- ------------------------------------------------------------
-- 下游项目数据
-- ------------------------------------------------------------
INSERT INTO `ds_project` (`id`, `project_name`, `project_code`, `description`, `git_repository`, `git_branch`, `tech_stack`, `contact_person`, `contact_email`, `subscription_status`, `webhook_url`, `created_at`, `updated_at`, `created_by`, `updated_by`, `deleted`) VALUES
(1, '电商管理后台', 'ecommerce-admin', '电商平台管理后台系统', 'https://github.com/example/ecommerce-admin', 'main', 'REACT', '赵六', 'zhaoliu@example.com', 1, 'https://api.example.com/webhook/design-system', NOW(), NOW(), 1, 1, 0),
(2, '移动端H5商城', 'mobile-mall', '移动端H5商城项目', 'https://github.com/example/mobile-mall', 'main', 'VUE', '孙七', 'sunqi@example.com', 1, 'https://api.example.com/webhook/mobile', NOW(), NOW(), 1, 1, 0),
(3, '数据可视化平台', 'data-viz', '企业数据可视化分析平台', 'https://github.com/example/data-viz', 'develop', 'REACT', '周八', 'zhouba@example.com', 0, NULL, NOW(), NOW(), 1, 1, 0);
