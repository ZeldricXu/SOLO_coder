-- =====================================================
-- 在线考试系统数据库初始化脚本
-- 数据库: PostgreSQL
-- 版本: 1.0.0
-- =====================================================

-- 创建数据库
-- CREATE DATABASE exam_db WITH ENCODING 'UTF8' LC_COLLATE='zh_CN.UTF-8' LC_CTYPE='zh_CN.UTF-8' TEMPLATE=template0;

-- =====================================================
-- 用户与权限
-- =====================================================

-- 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码',
    real_name VARCHAR(64) COMMENT '真实姓名',
    email VARCHAR(128) COMMENT '邮箱',
    phone VARCHAR(32) COMMENT '手机号',
    avatar VARCHAR(512) COMMENT '头像',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    subject_id BIGINT COMMENT '所属科目ID',
    last_login_time TIMESTAMP COMMENT '最后登录时间',
    last_login_ip VARCHAR(64) COMMENT '最后登录IP',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id BIGSERIAL PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE COMMENT '角色编码',
    role_name VARCHAR(64) NOT NULL COMMENT '角色名称',
    description VARCHAR(256) COMMENT '角色描述',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 用户角色关联表
CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0,
    UNIQUE(user_id, role_id)
);

-- =====================================================
-- 题库管理
-- =====================================================

-- 科目表
CREATE TABLE IF NOT EXISTS exam_subject (
    id BIGSERIAL PRIMARY KEY,
    subject_name VARCHAR(128) NOT NULL COMMENT '科目名称',
    subject_code VARCHAR(64) UNIQUE COMMENT '科目编码',
    parent_id BIGINT DEFAULT 0 COMMENT '父级ID',
    sort_order INT DEFAULT 0 COMMENT '排序',
    description VARCHAR(512) COMMENT '描述',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 知识点表
CREATE TABLE IF NOT EXISTS exam_knowledge_point (
    id BIGSERIAL PRIMARY KEY,
    point_name VARCHAR(128) NOT NULL COMMENT '知识点名称',
    point_code VARCHAR(64) COMMENT '知识点编码',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    parent_id BIGINT DEFAULT 0 COMMENT '父级ID',
    sort_order INT DEFAULT 0 COMMENT '排序',
    description VARCHAR(512) COMMENT '描述',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 题目标签表
CREATE TABLE IF NOT EXISTS exam_question_tag (
    id BIGSERIAL PRIMARY KEY,
    tag_name VARCHAR(64) NOT NULL COMMENT '标签名称',
    tag_color VARCHAR(16) COMMENT '标签颜色',
    subject_id BIGINT COMMENT '所属科目ID',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 题目表
CREATE TABLE IF NOT EXISTS exam_question (
    id BIGSERIAL PRIMARY KEY,
    question_title VARCHAR(1024) NOT NULL COMMENT '题目标题',
    question_content TEXT COMMENT '题目内容',
    question_type INT NOT NULL COMMENT '题型 1-单选 2-多选 3-判断 4-填空 5-简答 6-编程',
    difficulty INT NOT NULL COMMENT '难度 1-简单 2-中等 3-困难',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    default_score DECIMAL(10,2) DEFAULT 0 COMMENT '默认分数',
    answer TEXT COMMENT '参考答案',
    analysis TEXT COMMENT '解析',
    version INT DEFAULT 1 COMMENT '版本号',
    version_remark VARCHAR(512) COMMENT '版本备注',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    tag_ids VARCHAR(512) COMMENT '标签ID列表，逗号分隔',
    knowledge_point_ids VARCHAR(512) COMMENT '知识点ID列表，逗号分隔',
    programming_language VARCHAR(32) COMMENT '编程语言',
    test_cases TEXT COMMENT '测试用例JSON',
    time_limit INT COMMENT '时间限制(ms)',
    memory_limit INT COMMENT '内存限制(KB)',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 题目选项表
CREATE TABLE IF NOT EXISTS exam_question_option (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL COMMENT '题目ID',
    option_label VARCHAR(8) NOT NULL COMMENT '选项标签 A/B/C/D',
    option_content TEXT COMMENT '选项内容',
    sort_order INT DEFAULT 0 COMMENT '排序',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 题目版本历史表
CREATE TABLE IF NOT EXISTS exam_question_version (
    id BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL COMMENT '题目ID',
    question_title VARCHAR(1024) COMMENT '题目标题',
    question_content TEXT COMMENT '题目内容',
    question_type INT COMMENT '题型',
    difficulty INT COMMENT '难度',
    subject_id BIGINT COMMENT '科目ID',
    default_score DECIMAL(10,2) COMMENT '默认分数',
    answer TEXT COMMENT '参考答案',
    analysis TEXT COMMENT '解析',
    version INT NOT NULL COMMENT '版本号',
    version_remark VARCHAR(512) COMMENT '版本备注',
    options_snapshot TEXT COMMENT '选项快照JSON',
    programming_language VARCHAR(32) COMMENT '编程语言',
    test_cases TEXT COMMENT '测试用例JSON',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 试卷管理
-- =====================================================

-- 试卷模板表
CREATE TABLE IF NOT EXISTS exam_paper_template (
    id BIGSERIAL PRIMARY KEY,
    template_name VARCHAR(128) NOT NULL COMMENT '模板名称',
    template_code VARCHAR(64) UNIQUE COMMENT '模板编码',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    paper_mode INT DEFAULT 1 COMMENT '组卷模式 1-固定 2-随机',
    total_score INT DEFAULT 100 COMMENT '总分',
    total_questions INT COMMENT '总题数',
    duration INT COMMENT '考试时长(分钟)',
    pass_score INT COMMENT '及格分',
    difficulty_config TEXT COMMENT '难度配置JSON',
    knowledge_config TEXT COMMENT '知识点配置JSON',
    question_type_config TEXT COMMENT '题型配置JSON',
    description VARCHAR(512) COMMENT '描述',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    effective_start_time TIMESTAMP COMMENT '生效开始时间',
    effective_end_time TIMESTAMP COMMENT '生效结束时间',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 试卷表
CREATE TABLE IF NOT EXISTS exam_paper (
    id BIGSERIAL PRIMARY KEY,
    paper_name VARCHAR(128) NOT NULL COMMENT '试卷名称',
    paper_code VARCHAR(64) UNIQUE COMMENT '试卷编码',
    template_id BIGINT COMMENT '模板ID',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    paper_mode INT DEFAULT 1 COMMENT '组卷模式 1-固定 2-随机',
    paper_version INT DEFAULT 1 COMMENT '试卷版本',
    total_score INT DEFAULT 100 COMMENT '总分',
    total_questions INT COMMENT '总题数',
    duration INT COMMENT '考试时长(分钟)',
    pass_score INT COMMENT '及格分',
    difficulty_avg DECIMAL(4,2) COMMENT '平均难度',
    question_ids TEXT COMMENT '题目ID列表，逗号分隔',
    difficulty_config TEXT COMMENT '难度配置JSON',
    knowledge_config TEXT COMMENT '知识点配置JSON',
    description VARCHAR(512) COMMENT '描述',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    is_template INT DEFAULT 0 COMMENT '是否模板 0-否 1-是',
    effective_start_time TIMESTAMP COMMENT '生效开始时间',
    effective_end_time TIMESTAMP COMMENT '生效结束时间',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 试卷题目关联表
CREATE TABLE IF NOT EXISTS exam_paper_question (
    id BIGSERIAL PRIMARY KEY,
    paper_id BIGINT NOT NULL COMMENT '试卷ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    question_type INT COMMENT '题型',
    question_order INT COMMENT '题目序号',
    score DECIMAL(10,2) COMMENT '题目分值',
    section_name VARCHAR(64) COMMENT '大题名称',
    sort_order INT DEFAULT 0 COMMENT '排序',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 考试管理
-- =====================================================

-- 班级表
CREATE TABLE IF NOT EXISTS exam_class (
    id BIGSERIAL PRIMARY KEY,
    class_name VARCHAR(128) NOT NULL COMMENT '班级名称',
    class_code VARCHAR(64) UNIQUE COMMENT '班级编码',
    subject_id BIGINT COMMENT '科目ID',
    teacher_id BIGINT COMMENT '班主任ID',
    description VARCHAR(512) COMMENT '描述',
    student_count INT DEFAULT 0 COMMENT '学生数量',
    status INT DEFAULT 1 COMMENT '状态 0-禁用 1-启用',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 考试表
CREATE TABLE IF NOT EXISTS exam_info (
    id BIGSERIAL PRIMARY KEY,
    exam_name VARCHAR(128) NOT NULL COMMENT '考试名称',
    exam_code VARCHAR(64) UNIQUE COMMENT '考试编码',
    subject_id BIGINT NOT NULL COMMENT '科目ID',
    paper_id BIGINT NOT NULL COMMENT '试卷ID',
    paper_mode INT COMMENT '试卷模式 1-固定 2-随机',
    total_score INT DEFAULT 100 COMMENT '总分',
    pass_score INT COMMENT '及格分',
    duration INT COMMENT '考试时长(分钟)',
    start_time TIMESTAMP NOT NULL COMMENT '考试开始时间',
    end_time TIMESTAMP NOT NULL COMMENT '考试结束时间',
    enter_start_time TIMESTAMP COMMENT '允许进入开始时间',
    enter_end_time TIMESTAMP COMMENT '允许进入结束时间',
    exam_status INT DEFAULT 0 COMMENT '考试状态 0-未开始 1-进行中 2-已结束 3-阅卷中 4-已完成',
    allow_switch_screen INT DEFAULT 0 COMMENT '是否允许切屏 0-否 1-是',
    max_switch_screen_count INT DEFAULT 3 COMMENT '最大切屏次数',
    allow_back INT DEFAULT 1 COMMENT '是否允许返回上一题',
    random_order INT DEFAULT 0 COMMENT '题目是否乱序 0-否 1-是',
    ab_paper INT DEFAULT 0 COMMENT '是否AB卷 0-否 1-是',
    description VARCHAR(512) COMMENT '考试说明',
    class_id BIGINT COMMENT '班级ID',
    candidate_ids TEXT COMMENT '考生ID列表，逗号分隔',
    total_candidates INT DEFAULT 0 COMMENT '考生总数',
    submitted_count INT DEFAULT 0 COMMENT '已交卷数',
    grading_status INT DEFAULT 0 COMMENT '阅卷状态 0-待阅卷 1-客观题已阅 2-阅卷中 3-已阅卷 4-仲裁中 5-全部完成',
    rules TEXT COMMENT '考试规则',
    notice TEXT COMMENT '考试须知',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 考试记录表
CREATE TABLE IF NOT EXISTS exam_record (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL COMMENT '考试ID',
    user_id BIGINT NOT NULL COMMENT '考生ID',
    paper_id BIGINT COMMENT '试卷ID',
    paper_version VARCHAR(32) COMMENT '试卷版本',
    start_time TIMESTAMP COMMENT '开始答题时间',
    submit_time TIMESTAMP COMMENT '交卷时间',
    end_time TIMESTAMP COMMENT '考试结束时间',
    duration INT COMMENT '考试时长(秒)',
    used_time INT COMMENT '已用时间(秒)',
    exam_status INT DEFAULT 0 COMMENT '考试状态 0-未开始 1-进行中 2-已结束',
    grading_status INT DEFAULT 0 COMMENT '阅卷状态',
    total_score DECIMAL(10,2) COMMENT '总分',
    objective_score DECIMAL(10,2) COMMENT '客观题得分',
    subjective_score DECIMAL(10,2) COMMENT '主观题得分',
    programming_score DECIMAL(10,2) COMMENT '编程题得分',
    final_score DECIMAL(10,2) COMMENT '最终得分',
    is_pass INT COMMENT '是否及格 0-否 1-是',
    rank INT COMMENT '排名',
    answer_sheet TEXT COMMENT '答题卡JSON',
    grading_detail TEXT COMMENT '评分详情JSON',
    abnormal_count INT DEFAULT 0 COMMENT '异常行为次数',
    screen_switch_count INT DEFAULT 0 COMMENT '切屏次数',
    disconnect_count INT DEFAULT 0 COMMENT '断线次数',
    submit_type INT DEFAULT 1 COMMENT '交卷类型 1-主动交卷 2-超时自动交卷 3-强制交卷',
    ip_address VARCHAR(64) COMMENT 'IP地址',
    device_info VARCHAR(512) COMMENT '设备信息',
    ab_paper_type VARCHAR(8) COMMENT 'AB卷类型 A/B',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- 答题记录表
CREATE TABLE IF NOT EXISTS exam_answer (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL COMMENT '考试ID',
    exam_record_id BIGINT NOT NULL COMMENT '考试记录ID',
    user_id BIGINT NOT NULL COMMENT '考生ID',
    paper_id BIGINT COMMENT '试卷ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    question_type INT COMMENT '题型',
    question_order INT COMMENT '题目序号',
    question_score DECIMAL(10,2) COMMENT '题目分值',
    user_answer TEXT COMMENT '考生答案',
    correct_answer TEXT COMMENT '正确答案',
    score DECIMAL(10,2) COMMENT '得分',
    answer_status INT DEFAULT 0 COMMENT '答题状态 0-未答 1-已答 2-标记',
    is_correct INT COMMENT '是否正确 0-否 1-是',
    grading_status INT DEFAULT 0 COMMENT '批改状态 0-待批改 1-自动批改完成 2-批改中 3-已批改 4-仲裁中 5-已完成',
    grading_remark VARCHAR(512) COMMENT '批改备注',
    answer_time TIMESTAMP COMMENT '答题时间',
    sort_order INT DEFAULT 0 COMMENT '排序',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 阅卷管理
-- =====================================================

-- 阅卷记录表
CREATE TABLE IF NOT EXISTS exam_grading_record (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL COMMENT '考试ID',
    exam_record_id BIGINT NOT NULL COMMENT '考试记录ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    answer_id BIGINT NOT NULL COMMENT '答题记录ID',
    grader_id BIGINT COMMENT '阅卷老师ID',
    grading_type INT DEFAULT 0 COMMENT '阅卷类型 0-自动 1-人工',
    score DECIMAL(10,2) COMMENT '得分',
    max_score DECIMAL(10,2) COMMENT '满分',
    grading_remark VARCHAR(512) COMMENT '批改备注',
    grading_status INT DEFAULT 0 COMMENT '状态 0-待批改 1-自动批改完成 2-批改中 3-已批改 4-仲裁中 5-已完成',
    grading_time TIMESTAMP COMMENT '批改时间',
    is_arbitration INT DEFAULT 0 COMMENT '是否仲裁 0-否 1-是',
    arbitration_grader_id BIGINT COMMENT '仲裁老师ID',
    arbitration_score DECIMAL(10,2) COMMENT '仲裁得分',
    arbitration_remark VARCHAR(512) COMMENT '仲裁备注',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 异常行为
-- =====================================================

-- 异常行为记录表
CREATE TABLE IF NOT EXISTS exam_abnormal_record (
    id BIGSERIAL PRIMARY KEY,
    exam_id BIGINT NOT NULL COMMENT '考试ID',
    exam_record_id BIGINT NOT NULL COMMENT '考试记录ID',
    user_id BIGINT NOT NULL COMMENT '考生ID',
    abnormal_type INT NOT NULL COMMENT '异常类型 1-切屏 2-断线 3-失焦 4-复制粘贴',
    abnormal_detail TEXT COMMENT '异常详情',
    abnormal_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '异常时间',
    ip_address VARCHAR(64) COMMENT 'IP地址',
    device_info VARCHAR(512) COMMENT '设备信息',
    severity INT DEFAULT 1 COMMENT '严重程度 1-轻微 2-一般 3-严重',
    handled INT DEFAULT 0 COMMENT '是否已处理 0-否 1-是',
    handle_remark VARCHAR(512) COMMENT '处理备注',
    handle_by BIGINT COMMENT '处理人',
    handle_time TIMESTAMP COMMENT '处理时间',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 错题本
-- =====================================================

-- 错题本表
CREATE TABLE IF NOT EXISTS exam_wrong_book (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    question_type INT COMMENT '题型',
    subject_id BIGINT COMMENT '科目ID',
    exam_id BIGINT COMMENT '来源考试ID',
    user_answer TEXT COMMENT '考生答案',
    correct_answer TEXT COMMENT '正确答案',
    wrong_count INT DEFAULT 1 COMMENT '错误次数',
    mastery_level INT DEFAULT 1 COMMENT '掌握程度 1-未掌握 2-一般 3-已掌握',
    last_wrong_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '最后错误时间',
    knowledge_point_ids VARCHAR(512) COMMENT '知识点ID列表',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INT DEFAULT 0
);

-- =====================================================
-- 创建索引
-- =====================================================

CREATE INDEX IF NOT EXISTS idx_question_subject_type ON exam_question(subject_id, question_type, difficulty);
CREATE INDEX IF NOT EXISTS idx_exam_record_exam_user ON exam_record(exam_id, user_id);
CREATE INDEX IF NOT EXISTS idx_answer_record_question ON exam_answer(exam_record_id, question_id);
CREATE INDEX IF NOT EXISTS idx_paper_question_paper ON exam_paper_question(paper_id);
CREATE INDEX IF NOT EXISTS idx_abnormal_exam ON exam_abnormal_record(exam_id);
CREATE INDEX IF NOT EXISTS idx_wrong_book_user ON exam_wrong_book(user_id, subject_id);
CREATE INDEX IF NOT EXISTS idx_grading_answer ON exam_grading_record(answer_id);
CREATE INDEX IF NOT EXISTS idx_grading_grader ON exam_grading_record(grader_id, grading_status);

-- =====================================================
-- 初始化数据
-- =====================================================

-- 初始化角色
INSERT INTO sys_role (role_code, role_name, description, status) VALUES
('ROLE_ADMIN', '超级管理员', '系统超级管理员，拥有全部权限', 1),
('ROLE_TEACHER', '出题老师', '负责题库管理、组卷、考试管理', 1),
('ROLE_GRADER', '阅卷老师', '负责主观题阅卷和仲裁', 1),
('ROLE_STUDENT', '考生', '参加考试的学生', 1)
ON CONFLICT (role_code) DO NOTHING;

-- 初始化管理员用户 (密码: admin123，BCrypt加密)
INSERT INTO sys_user (username, password, real_name, email, status) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '超级管理员', 'admin@example.com', 1),
('teacher', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '张老师', 'teacher@example.com', 1),
('grader', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '李老师', 'grader@example.com', 1),
('student', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', '张三', 'student@example.com', 1)
ON CONFLICT (username) DO NOTHING;

-- 为用户分配角色
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'admin' AND r.role_code = 'ROLE_ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'teacher' AND r.role_code = 'ROLE_TEACHER'
ON CONFLICT DO NOTHING;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'grader' AND r.role_code = 'ROLE_GRADER'
ON CONFLICT DO NOTHING;

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username = 'student' AND r.role_code = 'ROLE_STUDENT'
ON CONFLICT DO NOTHING;

-- 初始化科目
INSERT INTO exam_subject (subject_name, subject_code, sort_order, status) VALUES
('计算机科学', 'CS', 1, 1),
('Java程序设计', 'JAVA', 2, 1),
('数据结构', 'DS', 3, 1),
('数据库', 'DB', 4, 1)
ON CONFLICT (subject_code) DO NOTHING;

-- 初始化知识点
INSERT INTO exam_knowledge_point (point_name, point_code, subject_id, sort_order) VALUES
('Java基础', 'JAVA_BASIC', 2, 1),
('面向对象', 'JAVA_OOP', 2, 2),
('集合框架', 'JAVA_COLLECTION', 2, 3),
('多线程', 'JAVA_THREAD', 2, 4),
('IO流', 'JAVA_IO', 2, 5)
ON CONFLICT DO NOTHING;
