-- 在线考试系统数据库初始化脚本
-- PostgreSQL 15+

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==================== 用户与权限 ====================

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    real_name VARCHAR(64),
    email VARCHAR(128),
    phone VARCHAR(32),
    avatar VARCHAR(512),
    status INTEGER DEFAULT 1,
    subject_id BIGINT,
    last_login_time TIMESTAMP,
    last_login_ip VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT PRIMARY KEY,
    role_code VARCHAR(64) NOT NULL UNIQUE,
    role_name VARCHAR(64) NOT NULL,
    description VARCHAR(255),
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT PRIMARY KEY,
    permission_code VARCHAR(128) NOT NULL UNIQUE,
    permission_name VARCHAR(128),
    description VARCHAR(255),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_role_permission (
    id BIGINT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 题库模块 ====================

CREATE TABLE IF NOT EXISTS exam_subject (
    id BIGINT PRIMARY KEY,
    subject_name VARCHAR(128) NOT NULL,
    subject_code VARCHAR(64) UNIQUE,
    description VARCHAR(512),
    parent_id BIGINT,
    sort_order INTEGER DEFAULT 0,
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_knowledge_point (
    id BIGINT PRIMARY KEY,
    point_name VARCHAR(128) NOT NULL,
    point_code VARCHAR(64),
    subject_id BIGINT NOT NULL,
    parent_id BIGINT,
    sort_order INTEGER DEFAULT 0,
    description VARCHAR(512),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_tag (
    id BIGINT PRIMARY KEY,
    tag_name VARCHAR(64) NOT NULL,
    tag_type VARCHAR(32),
    subject_id BIGINT,
    sort_order INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_question (
    id BIGINT PRIMARY KEY,
    subject_id BIGINT NOT NULL,
    question_type INTEGER NOT NULL,
    difficulty INTEGER DEFAULT 2,
    question_content TEXT NOT NULL,
    question_image VARCHAR(512),
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    option_d TEXT,
    option_e TEXT,
    option_f TEXT,
    correct_answer TEXT,
    analysis TEXT,
    score DECIMAL(10,2) DEFAULT 0,
    knowledge_points VARCHAR(512),
    version INTEGER DEFAULT 1,
    reference_answer TEXT,
    programming_language VARCHAR(32),
    test_cases TEXT,
    code_template TEXT,
    time_limit INTEGER DEFAULT 30000,
    memory_limit INTEGER DEFAULT 256,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_question_version (
    id BIGINT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    version INTEGER NOT NULL,
    subject_id BIGINT,
    question_type INTEGER,
    difficulty INTEGER,
    question_content TEXT,
    option_a TEXT,
    option_b TEXT,
    option_c TEXT,
    option_d TEXT,
    option_e TEXT,
    option_f TEXT,
    correct_answer TEXT,
    analysis TEXT,
    score DECIMAL(10,2),
    knowledge_points VARCHAR(512),
    change_log VARCHAR(512),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_question_tag (
    id BIGINT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 组卷模块 ====================

CREATE TABLE IF NOT EXISTS exam_paper_template (
    id BIGINT PRIMARY KEY,
    template_name VARCHAR(128) NOT NULL,
    subject_id BIGINT NOT NULL,
    paper_mode INTEGER DEFAULT 1,
    total_score DECIMAL(10,2) DEFAULT 0,
    total_minutes INTEGER DEFAULT 60,
    single_count INTEGER DEFAULT 0,
    single_score DECIMAL(10,2),
    multiple_count INTEGER DEFAULT 0,
    multiple_score DECIMAL(10,2),
    judge_count INTEGER DEFAULT 0,
    judge_score DECIMAL(10,2),
    fill_count INTEGER DEFAULT 0,
    fill_score DECIMAL(10,2),
    short_count INTEGER DEFAULT 0,
    short_score DECIMAL(10,2),
    program_count INTEGER DEFAULT 0,
    program_score DECIMAL(10,2),
    easy_ratio DECIMAL(5,4),
    medium_ratio DECIMAL(5,4),
    hard_ratio DECIMAL(5,4),
    knowledge_distribution TEXT,
    description VARCHAR(512),
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_paper (
    id BIGINT PRIMARY KEY,
    paper_name VARCHAR(128) NOT NULL,
    subject_id BIGINT NOT NULL,
    template_id BIGINT,
    paper_mode INTEGER DEFAULT 1,
    paper_version VARCHAR(32),
    ab_type INTEGER DEFAULT 1,
    total_score DECIMAL(10,2),
    total_minutes INTEGER,
    question_count INTEGER DEFAULT 0,
    question_order TEXT,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    allow_late_submit INTEGER DEFAULT 0,
    late_submit_minutes INTEGER DEFAULT 0,
    anti_cheating_level INTEGER DEFAULT 1,
    max_screen_switch INTEGER DEFAULT 5,
    description VARCHAR(512),
    status INTEGER DEFAULT 0,
    publish_by BIGINT,
    publish_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_paper_question (
    id BIGINT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    question_order INTEGER NOT NULL,
    question_type INTEGER,
    question_score DECIMAL(10,2),
    difficulty INTEGER,
    knowledge_points VARCHAR(512),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 考试模块 ====================

CREATE TABLE IF NOT EXISTS exam_exam (
    id BIGINT PRIMARY KEY,
    exam_name VARCHAR(128) NOT NULL,
    subject_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    exam_code VARCHAR(32) UNIQUE,
    exam_status INTEGER DEFAULT 0,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    duration_minutes INTEGER NOT NULL,
    allow_late_entry INTEGER DEFAULT 0,
    late_entry_minutes INTEGER DEFAULT 0,
    allow_late_submit INTEGER DEFAULT 0,
    late_submit_minutes INTEGER DEFAULT 0,
    anti_cheating_level INTEGER DEFAULT 1,
    max_screen_switch INTEGER DEFAULT 5,
    auto_submit_on_time_out INTEGER DEFAULT 1,
    class_ids VARCHAR(512),
    student_ids TEXT,
    description VARCHAR(512),
    create_by BIGINT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_exam_session (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    ab_type INTEGER DEFAULT 1,
    session_status INTEGER DEFAULT 0,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    submit_time TIMESTAMP,
    used_seconds INTEGER DEFAULT 0,
    screen_switch_count INTEGER DEFAULT 0,
    abnormal_count INTEGER DEFAULT 0,
    objective_score DECIMAL(10,2) DEFAULT 0,
    subjective_score DECIMAL(10,2) DEFAULT 0,
    total_score DECIMAL(10,2) DEFAULT 0,
    grading_status INTEGER DEFAULT 0,
    grading_remark VARCHAR(512),
    submit_ip VARCHAR(64),
    device_info VARCHAR(512),
    last_heartbeat TIMESTAMP,
    reconnect_count INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_exam_answer (
    id BIGINT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    exam_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    question_order INTEGER,
    question_type INTEGER,
    student_answer TEXT,
    correct_answer TEXT,
    question_score DECIMAL(10,2),
    student_score DECIMAL(10,2) DEFAULT 0,
    answer_status INTEGER DEFAULT 0,
    grading_status INTEGER DEFAULT 0,
    first_grader_id BIGINT,
    first_grader_score DECIMAL(10,2),
    first_grader_remark VARCHAR(512),
    first_grade_time TIMESTAMP,
    second_grader_id BIGINT,
    second_grader_score DECIMAL(10,2),
    second_grader_remark VARCHAR(512),
    second_grade_time TIMESTAMP,
    arbitrator_id BIGINT,
    final_score DECIMAL(10,2),
    arbitration_remark VARCHAR(512),
    arbitration_time TIMESTAMP,
    judge_log TEXT,
    code_output TEXT,
    last_save_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_exam_abnormal (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    session_id BIGINT,
    student_id BIGINT,
    abnormal_type INTEGER,
    abnormal_name VARCHAR(64),
    description VARCHAR(512),
    happen_time TIMESTAMP,
    client_ip VARCHAR(64),
    extra_info TEXT,
    handled INTEGER DEFAULT 0,
    handle_remark VARCHAR(512),
    handle_by BIGINT,
    handle_time TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 阅卷模块 ====================

CREATE TABLE IF NOT EXISTS exam_grading_task (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    answer_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    grader_id BIGINT,
    grading_round INTEGER DEFAULT 1,
    question_score DECIMAL(10,2),
    grader_score DECIMAL(10,2),
    grader_remark VARCHAR(512),
    task_status INTEGER DEFAULT 0,
    assign_time TIMESTAMP,
    deadline TIMESTAMP,
    grade_time TIMESTAMP,
    timeout_count INTEGER DEFAULT 0,
    arbitration_required INTEGER DEFAULT 0,
    blind_code VARCHAR(64),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 成绩模块 ====================

CREATE TABLE IF NOT EXISTS exam_score (
    id BIGINT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    session_id BIGINT,
    paper_id BIGINT,
    student_id BIGINT NOT NULL,
    class_id BIGINT,
    subject_id BIGINT,
    total_score DECIMAL(10,2),
    objective_score DECIMAL(10,2),
    subjective_score DECIMAL(10,2),
    program_score DECIMAL(10,2),
    rank INTEGER,
    percentile DECIMAL(10,4),
    knowledge_mastery TEXT,
    wrong_questions TEXT,
    publish_time TIMESTAMP,
    published INTEGER DEFAULT 0,
    score_remark VARCHAR(512),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS exam_wrong_book (
    id BIGINT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    subject_id BIGINT,
    exam_id BIGINT,
    question_id BIGINT NOT NULL,
    student_answer TEXT,
    correct_answer TEXT,
    wrong_count INTEGER DEFAULT 1,
    last_wrong_time TIMESTAMP,
    mastered INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_by BIGINT,
    update_by BIGINT,
    deleted INTEGER DEFAULT 0
);

-- ==================== 索引 ====================

CREATE INDEX IF NOT EXISTS idx_question_subject_type ON exam_question(subject_id, question_type, difficulty);
CREATE INDEX IF NOT EXISTS idx_session_exam_student ON exam_exam_session(exam_id, student_id);
CREATE INDEX IF NOT EXISTS idx_answer_session ON exam_exam_answer(session_id);
CREATE INDEX IF NOT EXISTS idx_paper_question_paper ON exam_paper_question(paper_id);
CREATE INDEX IF NOT EXISTS idx_abnormal_exam ON exam_exam_abnormal(exam_id);
CREATE INDEX IF NOT EXISTS idx_score_exam_student ON exam_score(exam_id, student_id);
CREATE INDEX IF NOT EXISTS idx_wrongbook_student ON exam_wrong_book(student_id, question_id);
CREATE INDEX IF NOT EXISTS idx_grading_answer ON exam_grading_task(answer_id);

-- ==================== 初始化数据 ====================

-- 初始化角色
INSERT INTO sys_role (id, role_code, role_name, description) VALUES
(1, 'ROLE_ADMIN', '超级管理员', '系统超级管理员，拥有所有权限'),
(2, 'ROLE_TEACHER', '出题老师', '负责题库管理和组卷'),
(3, 'ROLE_GRADER', '阅卷老师', '负责主观题阅卷'),
(4, 'ROLE_STUDENT', '考生', '参加考试的学生')
ON CONFLICT (id) DO NOTHING;

-- 初始化管理员用户（密码：admin123，BCrypt加密）
INSERT INTO sys_user (id, username, password, real_name, status) VALUES
(1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '系统管理员', 1),
(2, 'teacher01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '张老师', 1),
(3, 'grader01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '李老师', 1),
(4, 'student01', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '学生甲', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO sys_user_role (id, user_id, role_id) VALUES
(1, 1, 1),
(2, 2, 2),
(3, 3, 3),
(4, 4, 4)
ON CONFLICT (id) DO NOTHING;

-- 初始化科目
INSERT INTO exam_subject (id, subject_name, subject_code, description) VALUES
(1, 'Java编程基础', 'JAVA_BASIC', 'Java编程语言基础课程'),
(2, '数据结构与算法', 'DATA_STRUCTURE', '数据结构与算法课程'),
(3, '数据库原理', 'DATABASE', '数据库系统原理')
ON CONFLICT (id) DO NOTHING;
