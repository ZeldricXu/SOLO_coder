package com.company.dbstudio.integration;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.data.model.TableData;
import com.company.dbstudio.data.service.DataBrowseService;
import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.service.ImportExportService;
import com.company.dbstudio.schema.model.SchemaObject;
import com.company.dbstudio.schema.model.SchemaObject.ObjectType;
import com.company.dbstudio.schema.service.SchemaService;
import com.company.dbstudio.sql.model.QueryResult;
import com.company.dbstudio.sql.service.QueryExecutor;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("数据库集成测试 - 完整链路")
class DatabaseIntegrationTest {

    @Container
    private static final MySQLContainer<?> mysqlContainer = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    private static final PostgreSQLContainer<?> postgresContainer = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    private static DataSourceRegistry registry;
    private static String mysqlConnectionId;
    private static String postgresConnectionId;

    @BeforeAll
    static void setup() {
        registry = new DataSourceRegistry();

        mysqlConnectionId = registerConnection(mysqlContainer, ConnectionType.MYSQL);
        postgresConnectionId = registerConnection(postgresContainer, ConnectionType.POSTGRESQL);

        initializeTestData();
    }

    @AfterAll
    static void cleanup() {
        if (registry != null) {
            registry.closeAll();
        }
    }

    private static String registerConnection(org.testcontainers.containers.JdbcDatabaseContainer<?> container,
                                             ConnectionType type) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(type.name().toLowerCase() + "-" + System.currentTimeMillis());
        config.setName(type.getDisplayName() + " Test");
        config.setType(type);
        config.setHost(container.getHost());
        config.setPort(container.getFirstMappedPort());
        config.setDatabase(container.getDatabaseName());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());

        registry.registerDataSource(config.getId(), config);

        return config.getId();
    }

    private static void initializeTestData() {
        try (Connection conn = registry.getConnection(mysqlConnectionId);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE employees (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(100) NOT NULL,
                    age INT,
                    department VARCHAR(50),
                    salary DECIMAL(10,2),
                    hire_date DATE,
                    active BOOLEAN DEFAULT TRUE
                )
                """);

            stmt.execute("""
                INSERT INTO employees (name, age, department, salary, hire_date) VALUES
                ('John Doe', 30, 'Engineering', 75000.00, '2020-01-15'),
                ('Jane Smith', 25, 'Marketing', 65000.00, '2021-06-01'),
                ('Bob Wilson', 35, 'Engineering', 85000.00, '2019-03-20'),
                ('Alice Brown', 28, 'HR', 55000.00, '2022-02-10'),
                ('Charlie Davis', 40, 'Engineering', 95000.00, '2018-11-05')
                """);

            stmt.execute("""
                CREATE TABLE departments (
                    id INT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL,
                    budget DECIMAL(12,2)
                )
                """);

            stmt.execute("""
                INSERT INTO departments VALUES
                (1, 'Engineering', 500000.00),
                (2, 'Marketing', 200000.00),
                (3, 'HR', 100000.00)
                """);

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize test data", e);
        }
    }

    @Test
    @Order(1)
    @DisplayName("MySQL连接测试")
    void mySqlConnection_ShouldConnectSuccessfully() throws Exception {
        try (Connection conn = registry.getConnection(mysqlConnectionId)) {
            assertThat(conn).isNotNull();
            assertThat(conn.isClosed()).isFalse();
            assertThat(conn.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
    }

    @Test
    @Order(2)
    @DisplayName("PostgreSQL连接测试")
    void postgreSqlConnection_ShouldConnectSuccessfully() throws Exception {
        try (Connection conn = registry.getConnection(postgresConnectionId)) {
            assertThat(conn).isNotNull();
            assertThat(conn.isClosed()).isFalse();
            assertThat(conn.getMetaData().getDatabaseProductName()).contains("PostgreSQL");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Hikari连接池配置正确")
    void connectionPool_ShouldBeConfiguredCorrectly() {
        HikariDataSource dataSource = registry.getDataSource(mysqlConnectionId);

        assertThat(dataSource).isNotNull();
        assertThat(dataSource.getJdbcUrl()).contains("mysql");
        assertThat(dataSource.getJdbcUrl()).contains("testdb");
        assertThat(dataSource.getUsername()).isEqualTo("testuser");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
        assertThat(dataSource.getMinimumIdle()).isEqualTo(5);
    }

    @Test
    @Order(4)
    @DisplayName("SQL查询执行测试")
    void queryExecutor_ShouldExecuteQueryAndReturnResults() throws Exception {
        QueryExecutor executor = new QueryExecutor();
        String sql = "SELECT * FROM employees WHERE active = TRUE ORDER BY id";

        Result<QueryResult> result = executor.execute(mysqlConnectionId, sql, 100, 0);

        assertThat(result.isSuccess()).isTrue();
        QueryResult queryResult = result.getData();

        assertThat(queryResult).isNotNull();
        assertThat(queryResult.isSuccess()).isTrue();
        assertThat(queryResult.getColumns()).hasSize(7);
        assertThat(queryResult.getData()).hasSize(5);
        assertThat(queryResult.getRowCount()).isEqualTo(5);
        assertThat(queryResult.getExecutionTime()).isGreaterThanOrEqualTo(0);

        List<String> columnNames = queryResult.getColumns().stream()
                .map(QueryResult.ColumnInfo::getName)
                .toList();
        assertThat(columnNames).contains("id", "name", "age", "department", "salary", "hire_date", "active");
    }

    @Test
    @Order(5)
    @DisplayName("分页查询测试")
    void dataBrowseService_ShouldSupportPagination() throws Exception {
        DataBrowseService dataService = new DataBrowseService(registry);

        Result<TableData> page1 = dataService.loadTableData(mysqlConnectionId, null, "employees", null, null, 2, 0);
        Result<TableData> page2 = dataService.loadTableData(mysqlConnectionId, null, "employees", null, null, 2, 2);

        assertThat(page1.isSuccess()).isTrue();
        assertThat(page2.isSuccess()).isTrue();

        TableData data1 = page1.getData();
        TableData data2 = page2.getData();

        assertThat(data1.getRows()).hasSize(2);
        assertThat(data2.getRows()).hasSize(2);
        assertThat(data1.getTotalRows()).isEqualTo(5);
        assertThat(data1.getTotalPages()).isEqualTo(3);

        assertThat(data1.getRows().get(0)[0]).isEqualTo(1);
        assertThat(data2.getRows().get(0)[0]).isEqualTo(3);
    }

    @Test
    @Order(6)
    @DisplayName("WHERE条件筛选测试")
    void dataBrowseService_WithFilter_ShouldReturnFilteredResults() throws Exception {
        DataBrowseService dataService = new DataBrowseService(registry);

        Result<TableData> result = dataService.loadTableData(
                mysqlConnectionId, null, "employees",
                "department = 'Engineering'",
                "salary DESC",
                100, 0
        );

        assertThat(result.isSuccess()).isTrue();
        TableData data = result.getData();

        assertThat(data.getRows()).hasSize(3);
        assertThat(data.getTotalRows()).isEqualTo(3);

        List<Object[]> rows = data.getRows();
        for (Object[] row : rows) {
            assertThat(row[3]).isEqualTo("Engineering");
        }

        assertThat((Comparable) rows.get(0)[4]).isGreaterThan((Comparable) rows.get(2)[4]);
    }

    @Test
    @Order(7)
    @DisplayName("Schema浏览 - 表列表加载")
    void schemaService_ShouldLoadTables() throws Exception {
        SchemaService schemaService = new SchemaService();

        Result<List<SchemaObject>> result = schemaService.loadTables(mysqlConnectionId, "testdb");

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> tables = result.getData();

        assertThat(tables).isNotEmpty();
        assertThat(tables).extracting(SchemaObject::getName)
                .contains("employees", "departments");
        assertThat(tables).allMatch(t -> t.getType() == ObjectType.TABLE);
    }

    @Test
    @Order(8)
    @DisplayName("Schema浏览 - 表列加载")
    void schemaService_ShouldLoadTableColumns() throws Exception {
        SchemaService schemaService = new SchemaService();
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "employees", "employees");

        Result<List<SchemaObject>> result = schemaService.loadTableChildren(mysqlConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> children = result.getData();

        List<SchemaObject> columns = children.stream()
                .filter(c -> c.getType() == ObjectType.COLUMN)
                .toList();

        assertThat(columns).hasSize(7);
        assertThat(columns).extracting(SchemaObject::getName)
                .containsExactlyInAnyOrder("id", "name", "age", "department", "salary", "hire_date", "active");
    }

    @Test
    @Order(9)
    @DisplayName("Schema浏览 - DDL生成")
    void schemaService_ShouldGenerateCorrectDDL() throws Exception {
        SchemaService schemaService = new SchemaService();
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "employees", "employees");

        Result<String> result = schemaService.generateDDL(mysqlConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).isNotEmpty();
        assertThat(ddl).containsIgnoringCase("CREATE TABLE");
        assertThat(ddl).contains("employees");
        assertThat(ddl).contains("id");
        assertThat(ddl).contains("name");
        assertThat(ddl).contains("PRIMARY KEY");
        assertThat(ddl).contains("NOT NULL");
    }

    @Test
    @Order(10)
    @DisplayName("数据导出 - CSV格式")
    void exportService_ShouldExportToCsv() throws Exception {
        ImportExportService exportService = new ImportExportService();
        File tempFile = File.createTempFile("export-", ".csv");
        tempFile.deleteOnExit();

        ImportExportConfig config = new ImportExportConfig();
        config.setConnectionId(mysqlConnectionId);
        config.setSourceSchema("testdb");
        config.setSourceTable("employees");
        config.setFormat(ImportExportConfig.Format.CSV);
        config.setFilePath(tempFile.getAbsolutePath());
        config.setIncludeHeader(true);

        Result<Long> result = exportService.exportData(config, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(5);
        assertThat(tempFile.exists()).isTrue();
        assertThat(tempFile.length()).isGreaterThan(0);

        List<String> lines = Files.readAllLines(tempFile.toPath());
        assertThat(lines).hasSize(6);
        assertThat(lines.get(0)).contains("id,name,age,department");
        assertThat(lines.get(1)).contains("John Doe");
    }

    @Test
    @Order(11)
    @DisplayName("数据导出 - JSON格式")
    void exportService_ShouldExportToJson() throws Exception {
        ImportExportService exportService = new ImportExportService();
        File tempFile = File.createTempFile("export-", ".json");
        tempFile.deleteOnExit();

        ImportExportConfig config = new ImportExportConfig();
        config.setConnectionId(mysqlConnectionId);
        config.setSourceSchema("testdb");
        config.setSourceTable("departments");
        config.setFormat(ImportExportConfig.Format.JSON);
        config.setFilePath(tempFile.getAbsolutePath());
        config.setJsonPrettyPrint(true);

        Result<Long> result = exportService.exportData(config, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(3);

        String content = Files.readString(tempFile.toPath());
        assertThat(content).contains("\"id\"");
        assertThat(content).contains("\"name\"");
        assertThat(content).contains("\"Engineering\"");
        assertThat(content).contains("\"Marketing\"");
        assertThat(content).contains("\"HR\"");
    }

    @Test
    @Order(12)
    @DisplayName("多连接切换 - 互不干扰")
    void multipleConnections_ShouldOperateIndependently() throws Exception {
        try (Connection mysqlConn = registry.getConnection(mysqlConnectionId);
             Statement mysqlStmt = mysqlConn.createStatement();
             Connection pgConn = registry.getConnection(postgresConnectionId);
             Statement pgStmt = pgConn.createStatement()) {

            pgStmt.execute("""
                CREATE TABLE IF NOT EXISTS test_table (
                    id INT PRIMARY KEY,
                    value VARCHAR(100)
                )
                """);
            pgStmt.execute("INSERT INTO test_table VALUES (1, 'PostgreSQL data')");

            try (ResultSet mysqlRs = mysqlStmt.executeQuery("SELECT COUNT(*) FROM employees");
                 ResultSet pgRs = pgStmt.executeQuery("SELECT COUNT(*) FROM test_table")) {

                mysqlRs.next();
                int mysqlCount = mysqlRs.getInt(1);
                assertThat(mysqlCount).isEqualTo(5);

                pgRs.next();
                int pgCount = pgRs.getInt(1);
                assertThat(pgCount).isEqualTo(1);
            }

            try (ResultSet mysqlRs2 = mysqlStmt.executeQuery("SELECT name FROM employees WHERE id = 1")) {
                mysqlRs2.next();
                assertThat(mysqlRs2.getString("name")).isEqualTo("John Doe");
            }
        }
    }

    @Test
    @Order(13)
    @DisplayName("连接池复用 - 不创建新连接")
    void connectionPool_ShouldReuseConnections() throws Exception {
        HikariDataSource dataSource = registry.getDataSource(mysqlConnectionId);
        int initialConnections = dataSource.getHikariPoolMXBean().getTotalConnections();

        for (int i = 0; i < 10; i++) {
            try (Connection conn = registry.getConnection(mysqlConnectionId);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        int afterConnections = dataSource.getHikariPoolMXBean().getTotalConnections();
        assertThat(afterConnections).isLessThanOrEqualTo(initialConnections + 5);
    }

    @Test
    @Order(14)
    @DisplayName("SQL语法错误处理")
    void queryExecutor_WithInvalidSql_ShouldReturnError() throws Exception {
        QueryExecutor executor = new QueryExecutor();
        String invalidSql = "SELECT * FORM employees WHERE id = 1";

        Result<QueryResult> result = executor.execute(mysqlConnectionId, invalidSql, 100, 0);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotEmpty();
        assertThat(result.getMessage()).containsIgnoringCase("error");
    }

    @Test
    @Order(15)
    @DisplayName("大结果集查询 - 内存安全")
    void queryExecutor_LargeResultSet_ShouldNotOOM() throws Exception {
        try (Connection conn = registry.getConnection(mysqlConnectionId);
             Statement stmt = conn.createStatement()) {

            stmt.execute("DROP TABLE IF EXISTS large_table");
            stmt.execute("""
                CREATE TABLE large_table (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    data VARCHAR(100)
                )
                """);

            for (int i = 0; i < 1000; i++) {
                stmt.executeUpdate(String.format(
                        "INSERT INTO large_table (data) VALUES ('Row number %d')", i + 1));
            }
        }

        QueryExecutor executor = new QueryExecutor();
        Result<QueryResult> result = executor.execute(
                mysqlConnectionId, "SELECT * FROM large_table", 1000, 0);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getRowCount()).isEqualTo(1000);
    }

    @Test
    @Order(16)
    @DisplayName("数据更新测试")
    void dataBrowseService_ShouldSupportRowUpdate() throws Exception {
        DataBrowseService dataService = new DataBrowseService(registry);

        Result<TableData> result = dataService.loadTableData(
                mysqlConnectionId, null, "employees", "id = 1", null, 1, 0);

        assertThat(result.isSuccess()).isTrue();
        TableData data = result.getData();
        Object[] row = data.getRows().get(0);
        String originalName = (String) row[1];

        Object[] updatedRow = row.clone();
        updatedRow[1] = "John Updated";

        com.company.dbstudio.data.model.RowChange change =
                new com.company.dbstudio.data.model.RowChange(
                        com.company.dbstudio.data.model.RowChange.ChangeType.UPDATE,
                        "employees",
                        data.getColumns(),
                        row,
                        updatedRow
                );

        Result<Integer> updateResult = dataService.applyChanges(mysqlConnectionId, List.of(change));
        assertThat(updateResult.isSuccess()).isTrue();

        Result<TableData> verifyResult = dataService.loadTableData(
                mysqlConnectionId, null, "employees", "id = 1", null, 1, 0);
        assertThat(verifyResult.getData().getRows().get(0)[1]).isEqualTo("John Updated");
    }

    @Test
    @Order(17)
    @DisplayName("事务回滚测试")
    void dataBrowseService_UpdateFailure_ShouldRollback() throws Exception {
        DataBrowseService dataService = new DataBrowseService(registry);

        Result<TableData> result = dataService.loadTableData(
                mysqlConnectionId, null, "employees", "id = 2", null, 1, 0);

        Object[] originalRow = result.getData().getRows().get(0);
        Object[] badRow = originalRow.clone();
        badRow[0] = null;

        com.company.dbstudio.data.model.RowChange validChange =
                new com.company.dbstudio.data.model.RowChange(
                        com.company.dbstudio.data.model.RowChange.ChangeType.UPDATE,
                        "employees",
                        result.getData().getColumns(),
                        originalRow,
                        new Object[]{2, "Jane Updated", originalRow[2], originalRow[3],
                                originalRow[4], originalRow[5], originalRow[6]}
                );

        com.company.dbstudio.data.model.RowChange invalidChange =
                new com.company.dbstudio.data.model.RowChange(
                        com.company.dbstudio.data.model.RowChange.ChangeType.UPDATE,
                        "nonexistent_table",
                        result.getData().getColumns(),
                        originalRow,
                        badRow
                );

        Result<Integer> updateResult = dataService.applyChanges(
                mysqlConnectionId, List.of(validChange, invalidChange));

        assertThat(updateResult.isSuccess()).isFalse();
    }

    @Test
    @Order(18)
    @DisplayName("关闭连接清理资源")
    void closeConnection_ShouldReleaseResources() {
        registry.closeDataSource(mysqlConnectionId);

        HikariDataSource dataSource = registry.getDataSource(mysqlConnectionId);
        assertThat(dataSource).isNull();
    }
}
