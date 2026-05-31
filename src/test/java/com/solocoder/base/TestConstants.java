package com.solocoder.base;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestConstants {

    public static final String TEST_FILE_ID = "test_file_001";
    public static final String TEST_FILE_NAME = "test.txt";
    public static final String TEST_CONTENT = "Hello, World!";
    public static final long TEST_FILE_SIZE = 13L;
    public static final Map<String, String> TEST_METADATA = Map.of(
            "author", "test",
            "category", "document"
    );

    public static final String TEST_FEATURE_NAME = "user_click_count";
    public static final String TEST_ENTITY_ID = "user_123";
    public static final String TEST_FEATURE_DESCRIPTION = "用户点击次数统计";
    public static final Map<String, Object> TEST_FEATURE_SCHEMA = Map.of(
            "type", "integer",
            "min", 0,
            "max", Integer.MAX_VALUE
    );
    public static final Map<String, Object> TEST_FEATURES = Map.of(
            "user_click_count", 42,
            "user_view_count", 100,
            "user_purchase_count", 5
    );
    public static final List<String> TEST_FEATURE_NAMES = List.of(
            "user_click_count",
            "user_view_count"
    );
    public static final Instant TEST_EVENT_TIME = Instant.parse("2026-05-27T10:00:00Z");

    public static final String EMPTY_STRING = "";
    public static final String BLANK_STRING = "   ";
    public static final String VERY_LONG_STRING = "a".repeat(10000);
    public static final String SPECIAL_CHARS_STRING = "!@#$%^&*()_+-=[]{}|;':\",./<>?\\\r\n\t";
    public static final String UNICODE_STRING = "中文😀日本語한글";
    public static final String PATH_TRAVERSAL_STRING = "../../etc/passwd";
    public static final String NULL_BYTE_STRING = "file\u0000name.txt";

    public static final Set<String> SUSPICIOUS_PATTERNS = Set.of(
            "<script>",
            "javascript:",
            "onerror=",
            "DROP TABLE",
            "UNION SELECT"
    );

    public static final int CONCURRENT_THREADS = 50;
    public static final int CONCURRENT_ITERATIONS = 100;
    public static final long CONCURRENT_TIMEOUT_SECONDS = 30;

    public static final String TEST_POLICY_NAME = "archive_after_30_days";
    public static final String TEST_STORAGE_CLASS = "GLACIER";

    private TestConstants() {
    }
}
