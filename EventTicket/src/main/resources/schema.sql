-- 活动表
CREATE TABLE IF NOT EXISTS events (
    event_id VARCHAR(50) PRIMARY KEY,
    event_name VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_date TIMESTAMP NOT NULL,
    event_venue VARCHAR(200) NOT NULL,
    event_capacity INT NOT NULL,
    event_status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- 座位表
CREATE TABLE IF NOT EXISTS seats (
    seat_id VARCHAR(50) PRIMARY KEY,
    event_id VARCHAR(50) NOT NULL,
    seat_number VARCHAR(50) NOT NULL,
    seat_section VARCHAR(50) NOT NULL,
    seat_price INT NOT NULL,
    seat_status VARCHAR(50) NOT NULL,
    locked_at TIMESTAMP,
    sold_at TIMESTAMP,
    admitted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- 票务表
CREATE TABLE IF NOT EXISTS tickets (
    ticket_id VARCHAR(50) PRIMARY KEY,
    event_id VARCHAR(50) NOT NULL,
    seat_id VARCHAR(50) NOT NULL,
    participant_id VARCHAR(50),
    participant_name VARCHAR(100) NOT NULL,
    participant_phone VARCHAR(20) NOT NULL,
    ticket_status VARCHAR(50) NOT NULL,
    ticket_price INT NOT NULL,
    payment_method VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    used_at TIMESTAMP
);

-- 参与者表
CREATE TABLE IF NOT EXISTS participants (
    participant_id VARCHAR(50) PRIMARY KEY,
    participant_name VARCHAR(100) NOT NULL,
    participant_phone VARCHAR(20) NOT NULL,
    participant_id_type VARCHAR(50),
    participant_id_number VARCHAR(100),
    created_at TIMESTAMP NOT NULL
);

-- 验证记录表
CREATE TABLE IF NOT EXISTS verifications (
    verify_id VARCHAR(50) PRIMARY KEY,
    ticket_id VARCHAR(50) NOT NULL,
    verify_time TIMESTAMP NOT NULL,
    verify_result VARCHAR(50) NOT NULL,
    verify_operator VARCHAR(50)
);

-- 退改记录表
CREATE TABLE IF NOT EXISTS change_records (
    change_id VARCHAR(50) PRIMARY KEY,
    ticket_id VARCHAR(50) NOT NULL,
    change_type VARCHAR(50) NOT NULL,
    change_reason VARCHAR(500),
    change_amount INT,
    change_status VARCHAR(50) NOT NULL,
    change_time TIMESTAMP NOT NULL
);

-- 统计表
CREATE TABLE IF NOT EXISTS statistics (
    stat_id VARCHAR(50) PRIMARY KEY,
    stat_month VARCHAR(7) NOT NULL,
    event_count INT NOT NULL,
    ticket_count INT NOT NULL,
    total_amount BIGINT NOT NULL,
    admission_count INT NOT NULL
);

-- 活动日程表
CREATE TABLE IF NOT EXISTS event_schedules (
    schedule_id VARCHAR(50) PRIMARY KEY,
    event_id VARCHAR(50) NOT NULL,
    schedule_title VARCHAR(200) NOT NULL,
    schedule_start_time TIMESTAMP NOT NULL,
    schedule_end_time TIMESTAMP NOT NULL,
    schedule_venue VARCHAR(200),
    schedule_description VARCHAR(500),
    created_at TIMESTAMP NOT NULL
);

-- 票务历史记录表
CREATE TABLE IF NOT EXISTS ticket_history (
    history_id VARCHAR(50) PRIMARY KEY,
    ticket_id VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    action_time TIMESTAMP NOT NULL,
    action_description VARCHAR(500),
    operator VARCHAR(50)
);
