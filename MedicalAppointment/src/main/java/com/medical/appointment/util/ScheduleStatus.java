package com.medical.appointment.util;

public class ScheduleStatus {
    
    public static final String AVAILABLE = "available";
    public static final String FULL = "full";
    public static final String CLOSED = "closed";
    
    private ScheduleStatus() {}
    
    public static boolean isValidStatus(String status) {
        return AVAILABLE.equals(status) || FULL.equals(status) || CLOSED.equals(status);
    }
    
    public static boolean canAppointment(String status) {
        return AVAILABLE.equals(status);
    }
}
