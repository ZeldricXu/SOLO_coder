package com.company.dbstudio.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

public class TestUtils {

    public static final String H2_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";
    public static final String H2_USER = "sa";
    public static final String H2_PASSWORD = "";

    public static File createTempFile(String suffix, String content) throws IOException {
        return createTempFile(suffix, content, StandardCharsets.UTF_8);
    }

    public static File createTempFile(String suffix, String content, Charset charset) throws IOException {
        Path tempFile = Files.createTempFile("dbstudio-test-", suffix);
        Files.writeString(tempFile, content, charset);
        tempFile.toFile().deleteOnExit();
        return tempFile.toFile();
    }

    public static File createTempCsvFile(List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(String.join(",", row)).append("\n");
        }
        return createTempFile(".csv", sb.toString());
    }

    public static File createTempJsonFile(List<Object> data) throws IOException {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < data.size(); i++) {
            sb.append("  ").append(data.get(i));
            if (i < data.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return createTempFile(".json", sb.toString());
    }

    public static File createLargeTextFile(int sizeMB, String content) throws IOException {
        Path tempFile = Files.createTempFile("dbstudio-large-", ".txt");
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            int iterations = (sizeMB * 1024 * 1024) / contentBytes.length + 1;
            for (int i = 0; i < iterations; i++) {
                baos.write(contentBytes);
            }
            Files.write(tempFile, baos.toByteArray());
        }
        tempFile.toFile().deleteOnExit();
        return tempFile.toFile();
    }

    public static String readFileContent(File file) throws IOException {
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    public static InputStream stringToStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    public static Connection createH2Connection() throws Exception {
        return DriverManager.getConnection(H2_URL, H2_USER, H2_PASSWORD);
    }

    public static void executeSql(Connection conn, String... sqls) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            for (String sql : sqls) {
                stmt.execute(sql);
            }
        }
    }

    public static void createTestTable(Connection conn, String tableName) throws Exception {
        String sql = String.format("""
            CREATE TABLE %s (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                age INT,
                salary DECIMAL(10,2),
                hire_date DATE,
                active BOOLEAN,
                description CLOB,
                photo BLOB
            )
            """, tableName);
        executeSql(conn, sql);

        String insertSql = String.format("""
            INSERT INTO %s VALUES 
            (1, 'John Doe', 30, 50000.00, '2020-01-15', TRUE, 'Software Engineer', NULL),
            (2, 'Jane Smith', 25, 45000.00, '2021-06-01', TRUE, 'Data Analyst', NULL),
            (3, 'Bob Wilson', 35, 60000.00, '2019-03-20', FALSE, 'Manager', NULL)
            """, tableName);
        executeSql(conn, insertSql);
    }

    public static void createEmptyTable(Connection conn, String tableName) throws Exception {
        String sql = String.format("""
            CREATE TABLE %s (
                id INT PRIMARY KEY,
                name VARCHAR(100)
            )
            """, tableName);
        executeSql(conn, sql);
    }

    public static void createNarrowTable(Connection conn, String tableName) throws Exception {
        String sql = String.format("CREATE TABLE %s (value INT)", tableName);
        executeSql(conn, sql);
        executeSql(conn, String.format("INSERT INTO %s VALUES (42)", tableName));
    }

    public static boolean fileContainsText(File file, String text) throws IOException {
        String content = readFileContent(file);
        return content.contains(text);
    }

    public static long countFileLines(File file) throws IOException {
        return Files.lines(file.toPath()).count();
    }

    public static List<String> detectCharsetAliases() {
        return Arrays.asList(
                "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE",
                "GBK", "GB2312", "ISO-8859-1", "WINDOWS-1252"
        );
    }
}
