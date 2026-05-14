package com.medical.appointment.util;

public class AppointmentStatus {
    
    public static final String APPOINTED = "appointed";
    public static final String VISITED = "visited";
    public static final String CANCELLED = "cancelled";
    public static final String EXPIRED = "expired";
    
    private AppointmentStatus() {}
    
    public static boolean isValidStatus(String status) {
        return APPOINTED.equals(status) 
            || VISITED.equals(status) 
            || CANCELLED.equals(status) 
            || EXPIRED.equals(status);
    }
    
    public static boolean canCancel(String status) {
        return APPOINTED.equals(status);
    }
    
    public static boolean canVisit(String status) {
        return APPOINTED.equals(status);
    }
}
