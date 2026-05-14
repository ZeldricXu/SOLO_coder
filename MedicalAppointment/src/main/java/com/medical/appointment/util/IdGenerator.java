package com.medical.appointment.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class IdGenerator {
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    private static final AtomicInteger appointmentCounter = new AtomicInteger(0);
    private static final AtomicInteger visitCounter = new AtomicInteger(0);
    
    private IdGenerator() {}
    
    public static String generateHospitalId() {
        return "hospital_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateDepartmentId() {
        return "dept_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateDoctorId() {
        return "doctor_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generatePatientId() {
        return "patient_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateScheduleId() {
        return "schedule_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateAppointmentId() {
        return "appoint_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateVisitId() {
        return "visit_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateStatisticsId() {
        return "stat_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    public static String generateAppointmentNumber() {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        int count = appointmentCounter.incrementAndGet();
        if (count > 999) {
            appointmentCounter.set(1);
            count = 1;
        }
        return String.format("GH%s%03d", dateStr, count);
    }
    
    public static String generateVisitNumber() {
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        int count = visitCounter.incrementAndGet();
        if (count > 999) {
            visitCounter.set(1);
            count = 1;
        }
        return String.format("JZ%s%03d", dateStr, count);
    }
    
    public static String getCurrentMonth() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
