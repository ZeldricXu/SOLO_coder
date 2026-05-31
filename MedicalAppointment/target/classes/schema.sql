CREATE DATABASE IF NOT EXISTS medical_appointment DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE medical_appointment;

CREATE TABLE IF NOT EXISTS hospitals (
    hospital_id VARCHAR(50) PRIMARY KEY,
    hospital_name VARCHAR(200) NOT NULL,
    hospital_type VARCHAR(50),
    hospital_address VARCHAR(500),
    hospital_level VARCHAR(20),
    hospital_status VARCHAR(20),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS departments (
    department_id VARCHAR(50) PRIMARY KEY,
    hospital_id VARCHAR(50) NOT NULL,
    department_name VARCHAR(100) NOT NULL,
    department_type VARCHAR(50),
    department_status VARCHAR(20),
    INDEX idx_hospital_id (hospital_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS doctors (
    doctor_id VARCHAR(50) PRIMARY KEY,
    doctor_name VARCHAR(100) NOT NULL,
    doctor_title VARCHAR(50),
    department_id VARCHAR(50) NOT NULL,
    doctor_rating DECIMAL(3,2),
    doctor_status VARCHAR(20),
    created_at DATETIME,
    appointment_count INT DEFAULT 0,
    visit_count INT DEFAULT 0,
    INDEX idx_department_id (department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS patients (
    patient_id VARCHAR(50) PRIMARY KEY,
    patient_name VARCHAR(100) NOT NULL,
    patient_phone VARCHAR(20),
    patient_id_number VARCHAR(50),
    patient_status VARCHAR(20),
    registered_at DATETIME,
    appointment_count INT DEFAULT 0,
    visit_count INT DEFAULT 0,
    INDEX idx_phone (patient_phone),
    INDEX idx_id_number (patient_id_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS schedules (
    schedule_id VARCHAR(50) PRIMARY KEY,
    department_id VARCHAR(50) NOT NULL,
    doctor_id VARCHAR(50) NOT NULL,
    schedule_date DATE,
    schedule_time VARCHAR(20),
    schedule_quota INT,
    schedule_available INT,
    schedule_status VARCHAR(20),
    INDEX idx_department_id (department_id),
    INDEX idx_doctor_id (doctor_id),
    INDEX idx_schedule_date (schedule_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS appointments (
    appointment_id VARCHAR(50) PRIMARY KEY,
    patient_id VARCHAR(50) NOT NULL,
    schedule_id VARCHAR(50) NOT NULL,
    doctor_id VARCHAR(50) NOT NULL,
    department_id VARCHAR(50),
    hospital_id VARCHAR(50),
    appointment_number VARCHAR(50) UNIQUE,
    appointment_status VARCHAR(20),
    appointment_time DATETIME,
    created_at DATETIME,
    cancel_reason VARCHAR(500),
    INDEX idx_patient_id (patient_id),
    INDEX idx_schedule_id (schedule_id),
    INDEX idx_doctor_id (doctor_id),
    INDEX idx_appointment_status (appointment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS visits (
    visit_id VARCHAR(50) PRIMARY KEY,
    appointment_id VARCHAR(50) NOT NULL,
    patient_id VARCHAR(50) NOT NULL,
    doctor_id VARCHAR(50) NOT NULL,
    visit_time DATETIME,
    visit_status VARCHAR(20),
    visit_record TEXT,
    visit_diagnosis TEXT,
    visit_prescription TEXT,
    INDEX idx_appointment_id (appointment_id),
    INDEX idx_patient_id (patient_id),
    INDEX idx_doctor_id (doctor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS statistics (
    stat_id VARCHAR(50) PRIMARY KEY,
    stat_month VARCHAR(20) UNIQUE,
    appointment_count INT DEFAULT 0,
    visit_count INT DEFAULT 0,
    cancel_count INT DEFAULT 0,
    department_stat TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS appointment_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appointment_id VARCHAR(50) NOT NULL,
    action_type VARCHAR(20),
    previous_status VARCHAR(20),
    new_status VARCHAR(20),
    action_time DATETIME,
    action_by VARCHAR(100),
    remark VARCHAR(500),
    INDEX idx_appointment_id (appointment_id),
    INDEX idx_action_type (action_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
