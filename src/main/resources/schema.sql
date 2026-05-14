CREATE TABLE IF NOT EXISTS resources (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    resource_id VARCHAR(50) NOT NULL UNIQUE,
    resource_name VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_capacity INT,
    resource_status VARCHAR(50) NOT NULL DEFAULT 'available',
    resource_location VARCHAR(100),
    available_hours VARCHAR(255),
    priority INT DEFAULT 0,
    current_occupancy INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS bookings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id VARCHAR(50) NOT NULL UNIQUE,
    booking_type VARCHAR(50) NOT NULL DEFAULT 'appointment',
    user_id VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(50),
    booking_date DATE NOT NULL,
    booking_time TIME NOT NULL,
    booking_duration INT NOT NULL DEFAULT 60,
    booking_status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP,
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id VARCHAR(50) NOT NULL UNIQUE,
    resource_id VARCHAR(50) NOT NULL,
    schedule_date DATE NOT NULL,
    max_booking_per_slot INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE KEY unique_resource_date (resource_id, schedule_date)
);

CREATE TABLE IF NOT EXISTS schedule_slots (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    schedule_id BIGINT NOT NULL,
    slot_time TIME NOT NULL,
    slot_status VARCHAR(50) NOT NULL DEFAULT 'available',
    current_bookings INT DEFAULT 0,
    booking_id VARCHAR(50),
    FOREIGN KEY (schedule_id) REFERENCES schedules(id)
);

CREATE TABLE IF NOT EXISTS dispatches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_id VARCHAR(50) NOT NULL UNIQUE,
    booking_id VARCHAR(50) NOT NULL,
    resource_id VARCHAR(50) NOT NULL,
    dispatch_time TIME NOT NULL,
    dispatch_status VARCHAR(50) NOT NULL,
    dispatched_at TIMESTAMP,
    released_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS reminders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reminder_id VARCHAR(50) NOT NULL UNIQUE,
    booking_id VARCHAR(50) NOT NULL,
    reminder_type VARCHAR(50) NOT NULL DEFAULT 'before_time',
    reminder_time TIME NOT NULL,
    reminder_channel VARCHAR(50) NOT NULL DEFAULT 'sms',
    reminder_status VARCHAR(50) NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP,
    sent_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cancel_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cancel_id VARCHAR(50) NOT NULL UNIQUE,
    booking_id VARCHAR(50) NOT NULL,
    cancel_reason VARCHAR(255) NOT NULL,
    cancel_time TIMESTAMP NOT NULL,
    cancel_by VARCHAR(50) NOT NULL
);

CREATE TABLE IF NOT EXISTS booking_statistics (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_id VARCHAR(50) NOT NULL UNIQUE,
    stat_date DATE NOT NULL UNIQUE,
    total_bookings INT DEFAULT 0,
    confirmed_bookings INT DEFAULT 0,
    cancelled_bookings INT DEFAULT 0,
    resource_utilization INT DEFAULT 0,
    updated_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS booking_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    history_id VARCHAR(50) NOT NULL UNIQUE,
    booking_id VARCHAR(50) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(50),
    booking_date DATE NOT NULL,
    booking_time TIME NOT NULL,
    final_status VARCHAR(50) NOT NULL,
    action_type VARCHAR(50) NOT NULL,
    action_time TIMESTAMP NOT NULL,
    action_detail VARCHAR(255)
);
