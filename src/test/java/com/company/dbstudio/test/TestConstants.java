package com.company.dbstudio.test;

public class TestConstants {

    // Connection test URLs
    public static final String MYSQL_URL_LOCAL = "jdbc:mysql://localhost:3306/testdb";
    public static final String MYSQL_URL_REMOTE = "jdbc:mysql://192.168.1.100:3307/prod?useSSL=true";
    public static final String POSTGRESQL_URL = "jdbc:postgresql://localhost:5432/mydb";
    public static final String ORACLE_URL = "jdbc:oracle:thin:@//localhost:1521/XE";
    public static final String SQLSERVER_URL = "jdbc:sqlserver://localhost:1433;databaseName=master";
    public static final String H2_URL = "jdbc:h2:mem:test";

    // SQL test cases
    public static final String SQL_SIMPLE_SELECT = "SELECT * FROM users";
    public static final String SQL_SELECT_WHERE = "SELECT id, name FROM users WHERE age > 18";
    public static final String SQL_SELECT_JOIN = "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id";
    public static final String SQL_INSERT = "INSERT INTO users (name, age) VALUES ('Alice', 25)";
    public static final String SQL_UPDATE = "UPDATE users SET age = 26 WHERE name = 'Alice'";
    public static final String SQL_DELETE = "DELETE FROM users WHERE id = 1";
    public static final String SQL_COMPLEX = """
        SELECT u.name, COUNT(o.id) as order_count, SUM(o.amount) as total
        FROM users u
        LEFT JOIN orders o ON u.id = o.user_id
        WHERE u.active = true
        GROUP BY u.id, u.name
        HAVING COUNT(o.id) > 5
        ORDER BY total DESC
        LIMIT 10
        """;
    public static final String SQL_SYNTAX_ERROR = "SELECT * FORM users WHERE name = 'test'";

    // Test data
    public static final String[] CSV_HEADER = {"id", "name", "age", "salary", "hire_date"};
    public static final String[] CSV_ROW_INT = {"1", "John", "30", "50000.00", "2020-01-15"};
    public static final String[] CSV_ROW_STRING = {"2", "Alice", "25", "45000.50", "2021-06-01"};
    public static final String[] CSV_ROW_NULL = {"3", "", "NULL", "0.00", ""};

    // Performance thresholds
    public static final long SLOW_QUERY_THRESHOLD_MS = 1000;
    public static final long LARGE_DATA_THRESHOLD_ROWS = 10000;
    public static final int BLOB_SIZE_10MB = 10 * 1024 * 1024;

    // Timeouts
    public static final int CONNECTION_TIMEOUT_MS = 5000;
    public static final int QUERY_TIMEOUT_MS = 30000;
    public static final int UI_TEST_TIMEOUT_MS = 10000;
}
