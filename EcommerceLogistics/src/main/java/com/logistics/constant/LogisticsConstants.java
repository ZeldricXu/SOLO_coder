package com.logistics.constant;

public class LogisticsConstants {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SHIPPING = "shipping";
    public static final String STATUS_DELIVERING = "delivering";
    public static final String STATUS_DELIVERED = "delivered";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String TASK_STATUS_PENDING = "pending";
    public static final String TASK_STATUS_ASSIGNED = "assigned";
    public static final String TASK_STATUS_DELIVERING = "delivering";
    public static final String TASK_STATUS_COMPLETED = "completed";
    public static final String TASK_STATUS_FAILED = "failed";

    public static final String COURIER_STATUS_AVAILABLE = "available";
    public static final String COURIER_STATUS_BUSY = "busy";
    public static final String COURIER_STATUS_OFFLINE = "offline";

    public static final String STATION_STATUS_ACTIVE = "active";
    public static final String STATION_STATUS_INACTIVE = "inactive";
    public static final String STATION_STATUS_CLOSED = "closed";

    public static final String NOTIFY_TYPE_STATUS = "status";
    public static final String NOTIFY_TYPE_TRACK = "track";
    public static final String NOTIFY_TYPE_DELIVERY = "delivery";

    public static final String HISTORY_TYPE_CREATE = "create";
    public static final String HISTORY_TYPE_ASSIGN = "assign";
    public static final String HISTORY_TYPE_START = "start";
    public static final String HISTORY_TYPE_TRACK = "track";
    public static final String HISTORY_TYPE_COMPLETE = "complete";
    public static final String HISTORY_TYPE_CANCEL = "cancel";

    public static final String ACTION_START = "start";
    public static final String ACTION_UPDATE = "update";
    public static final String ACTION_COMPLETE = "complete";
    public static final String ACTION_CANCEL = "cancel";
}
