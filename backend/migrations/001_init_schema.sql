-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20),
    department VARCHAR(100),
    avatar VARCHAR(500),
    role VARCHAR(20) DEFAULT 'user',
    wechat_id VARCHAR(100),
    dingtalk_id VARCHAR(100),
    feishu_id VARCHAR(100),
    password_hash VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Rooms table
CREATE TABLE IF NOT EXISTS rooms (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(100) NOT NULL,
    floor INTEGER NOT NULL,
    capacity INTEGER NOT NULL,
    equipment TEXT,
    description VARCHAR(500),
    status VARCHAR(20) DEFAULT 'active',
    need_approval BOOLEAN DEFAULT FALSE,
    approver_id UUID REFERENCES users(id),
    location VARCHAR(200),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

-- Bookings table
CREATE TABLE IF NOT EXISTS bookings (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    room_id UUID NOT NULL REFERENCES rooms(id),
    user_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(200) NOT NULL,
    description TEXT,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    status VARCHAR(20) DEFAULT 'confirmed',
    recurring_rule VARCHAR(100),
    recurring_id UUID,
    attendees TEXT,
    approval_status VARCHAR(20) DEFAULT 'approved',
    approver_id UUID REFERENCES users(id),
    approved_at TIMESTAMP,
    reject_reason VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_bookings_room_time ON bookings(room_id, start_time, end_time);
CREATE INDEX IF NOT EXISTS idx_bookings_user ON bookings(user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_recurring ON bookings(recurring_id);

-- Meeting docs table
CREATE TABLE IF NOT EXISTS meeting_docs (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id),
    agenda TEXT,
    content TEXT,
    summary VARCHAR(1000),
    is_archived BOOLEAN DEFAULT FALSE,
    archived_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Todos table
CREATE TABLE IF NOT EXISTS todos (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    doc_id UUID NOT NULL REFERENCES meeting_docs(id),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    content VARCHAR(500) NOT NULL,
    assignee_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(20) DEFAULT 'pending',
    due_date TIMESTAMP,
    priority INTEGER DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_todos_assignee ON todos(assignee_id);
CREATE INDEX IF NOT EXISTS idx_todos_booking ON todos(booking_id);

-- Check-ins table
CREATE TABLE IF NOT EXISTS check_ins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    user_id UUID NOT NULL REFERENCES users(id),
    check_in_at TIMESTAMP NOT NULL,
    qr_code VARCHAR(100),
    status VARCHAR(20) DEFAULT 'checked_in',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_check_ins_booking ON check_ins(booking_id);
CREATE INDEX IF NOT EXISTS idx_check_ins_user ON check_ins(user_id);

-- Notifications table
CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL REFERENCES users(id),
    type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    channels VARCHAR(200),
    status VARCHAR(20) DEFAULT 'unread',
    booking_id UUID REFERENCES bookings(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user ON notifications(user_id, status);

-- Notification preferences table
CREATE TABLE IF NOT EXISTS notification_preferences (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    booking_confirm BOOLEAN DEFAULT TRUE,
    upcoming_remind BOOLEAN DEFAULT TRUE,
    minutes_release BOOLEAN DEFAULT TRUE,
    todo_assign BOOLEAN DEFAULT TRUE,
    channels VARCHAR(200) DEFAULT 'wechat,email',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- QR code tokens table
CREATE TABLE IF NOT EXISTS qr_code_tokens (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    token VARCHAR(100) NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qr_tokens_booking ON qr_code_tokens(booking_id);

-- Insert default admin user
INSERT INTO users (id, name, email, role, password_hash)
VALUES (uuid_generate_v4(), '系统管理员', 'admin@company.com', 'admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy')
ON CONFLICT (email) DO NOTHING;

-- Insert sample users
INSERT INTO users (name, email, department, role) VALUES
('张三', 'zhangsan@company.com', '技术部', 'user'),
('李四', 'lisi@company.com', '产品部', 'user'),
('王五', 'wangwu@company.com', '设计部', 'user'),
('赵六', 'zhaoliu@company.com', '技术部', 'user'),
('钱七', 'qianqi@company.com', '市场部', 'user')
ON CONFLICT (email) DO NOTHING;

-- Insert sample rooms
INSERT INTO rooms (name, floor, capacity, equipment, description, status, need_approval, location) VALUES
('301 创新厅', 3, 20, '投影仪,白板,视频会议', '大型会议室，适合部门会议', 'active', FALSE, '3楼东侧'),
('302 协作室', 3, 8, '电视,白板', '中型会议室，适合小组讨论', 'active', FALSE, '3楼西侧'),
('303 头脑风暴', 3, 6, '白板,便利贴', '小型讨论室', 'active', FALSE, '3楼南侧'),
('501 董事会议室', 5, 30, '投影仪,视频会议,音响系统', '高端会议室，重要会议需审批', 'active', TRUE, '5楼北侧'),
('502 培训室', 5, 50, '投影仪,麦克风,录播设备', '大型培训室，需审批使用', 'active', TRUE, '5楼东侧'),
('201 洽谈室', 2, 4, '电视,茶几', '小型会客室', 'active', FALSE, '2楼大堂');
