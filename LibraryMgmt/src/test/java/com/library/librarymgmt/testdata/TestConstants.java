package com.library.librarymgmt.testdata;

public class TestConstants {

    public static final String READER_TYPE_VIP = "vip";
    public static final String READER_TYPE_NORMAL = "normal";

    public static final String READER_STATUS_ACTIVE = "active";
    public static final String READER_STATUS_FROZEN = "frozen";
    public static final String READER_STATUS_SUSPENDED = "suspended";

    public static final String BOOK_STATUS_AVAILABLE = "available";
    public static final String BOOK_STATUS_BORROWED = "borrowed";
    public static final String BOOK_STATUS_UNAVAILABLE = "unavailable";

    public static final String BORROW_STATUS_BORROWED = "borrowed";
    public static final String BORROW_STATUS_RETURNED = "returned";

    public static final String RESERVE_STATUS_WAITING = "waiting";
    public static final String RESERVE_STATUS_NOTIFIED = "notified";
    public static final String RESERVE_STATUS_CANCELLED = "cancelled";
    public static final String RESERVE_STATUS_COMPLETED = "completed";

    public static final String RETURN_STATUS_NORMAL = "normal";
    public static final String RETURN_STATUS_OVERDUE = "overdue";

    public static final String BOOK_CATEGORY_HOT = "热门";
    public static final String BOOK_CATEGORY_NORMAL = "普通";
    public static final String BOOK_CATEGORY_LITERATURE = "文学";
    public static final String BOOK_CATEGORY_TECHNOLOGY = "科技";

    public static final int VIP_LOCK_TIMEOUT_SECONDS = 30;
    public static final int NORMAL_LOCK_TIMEOUT_SECONDS = 120;

    public static final int HOT_BOOK_REMIND_DAYS_BEFORE = 7;
    public static final int NORMAL_BOOK_REMIND_DAYS_BEFORE = 3;

    public static final int MAX_NOTIFICATION_RETRIES = 3;
    public static final int NOTIFICATION_RETRY_DELAY_SECONDS = 60;

    public static final int DEFAULT_BORROW_LIMIT = 5;
    public static final int VIP_BORROW_LIMIT = 10;

    public static final int DEFAULT_BORROW_DAYS = 15;

    public static final double OVERDUE_FINE_PER_DAY = 0.5;
}
