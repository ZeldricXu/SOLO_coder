package com.schedulebook.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfig {
    
    public static final String BOOKING_STATUS_PENDING = "pending";
    public static final String BOOKING_STATUS_CONFIRMED = "confirmed";
    public static final String BOOKING_STATUS_CANCELLED = "cancelled";
    public static final String BOOKING_STATUS_COMPLETED = "completed";
    public static final String BOOKING_STATUS_REJECTED = "rejected";
    
    public static final String RESOURCE_STATUS_AVAILABLE = "available";
    public static final String RESOURCE_STATUS_MAINTENANCE = "maintenance";
    public static final String RESOURCE_STATUS_UNAVAILABLE = "unavailable";
    
    public static final String SLOT_STATUS_AVAILABLE = "available";
    public static final String SLOT_STATUS_BOOKED = "booked";
    public static final String SLOT_STATUS_BLOCKED = "blocked";
    
    public static final String DISPATCH_STATUS_ASSIGNED = "assigned";
    public static final String DISPATCH_STATUS_RELEASED = "released";
    
    public static final String REMINDER_STATUS_PENDING = "pending";
    public static final String REMINDER_STATUS_SENT = "sent";
    public static final String REMINDER_STATUS_CANCELLED = "cancelled";
    
    public static final String REMINDER_TYPE_BEFORE_TIME = "before_time";
    public static final String REMINDER_TYPE_ON_TIME = "on_time";
    
    public static final String ACTION_TYPE_CREATE = "create";
    public static final String ACTION_TYPE_CONFIRM = "confirm";
    public static final String ACTION_TYPE_CANCEL = "cancel";
    public static final String ACTION_TYPE_COMPLETE = "complete";
    public static final String ACTION_TYPE_ADJUST = "adjust";
    
    public static final String DEFAULT_RESOURCE_TYPE_ROOM = "room";
    public static final String DEFAULT_RESOURCE_TYPE_EQUIPMENT = "equipment";
}
