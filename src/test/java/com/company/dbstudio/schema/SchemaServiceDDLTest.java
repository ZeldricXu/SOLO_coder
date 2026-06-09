package com.company.dbstudio.schema;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.schema.model.SchemaObject;
import com.company.dbstudio.schema.model.SchemaObject.ObjectType;
import com.company.dbstudio.schema.service.SchemaService;
import com.company.dbstudio.test.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Schema服务 - DDL生成和对比测试")
class SchemaServiceDDLTest {

    private SchemaService schemaService;
    private String testConnectionId;

    @BeforeEach
    void setUp() throws Exception {
        DataSourceRegistry registry = new DataSourceRegistry();

        Connection conn = TestUtils.createH2Connection();
        TestUtils.createTestTable(conn, "users");

        com.company.dbstudio.connection.model.ConnectionConfig config = new com.company.dbstudio.connection.model.ConnectionConfig();
        config.setId("test-h2");
        config.setName("Test H2");
        config.setHost("localhost");
        config.setPort(0);
        config.setDatabase("testdb");
        config.setUsername("sa");
        config.setPassword("");

        registry.registerDataSource(config.getId(), config);
        testConnectionId = config.getId();

        schemaService = new SchemaService();
    }

    @Test
    @DisplayName("从JDBC元数据生成CREATE TABLE语句")
    void generateTableDDL_ShouldGenerateCorrectDDL() throws Exception {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");

        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).isNotEmpty();
        assertThat(ddl).containsIgnoringCase("CREATE TABLE");
        assertThat(ddl).contains("users");
        assertThat(ddl).contains("id");
        assertThat(ddl).contains("name");
        assertThat(ddl).contains("INT");
        assertThat(ddl).contains("VARCHAR");
        assertThat(ddl).contains("PRIMARY KEY");
    }

    @Test
    @DisplayName("生成的DDL包含NOT NULL约束")
    void generateTableDDL_ShouldIncludeNotNullConstraints() throws Exception {
        try (Connection conn = TestUtils.createH2Connection()) {
            TestUtils.executeSql(conn, "DROP TABLE IF EXISTS not_null_test");
            TestUtils.executeSql(conn, """
                CREATE TABLE not_null_test (
                    id INT PRIMARY KEY,
                    required_col VARCHAR(100) NOT NULL,
                    optional_col VARCHAR(100)
                )
                """);
        }

        SchemaObject table = new SchemaObject(ObjectType.TABLE, "not_null_test", "not_null_test");
        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).contains("NOT NULL");
    }

    @Test
    @DisplayName("生成的DDL包含默认值")
    void generateTableDDL_ShouldIncludeDefaultValues() throws Exception {
        try (Connection conn = TestUtils.createH2Connection()) {
            TestUtils.executeSql(conn, "DROP TABLE IF EXISTS default_test");
            TestUtils.executeSql(conn, """
                CREATE TABLE default_test (
                    id INT PRIMARY KEY,
                    status VARCHAR(20) DEFAULT 'active',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }

        SchemaObject table = new SchemaObject(ObjectType.TABLE, "default_test", "default_test");
        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).contains("DEFAULT");
    }

    @Test
    @DisplayName("生成的DDL包含主键定义")
    void generateTableDDL_ShouldIncludePrimaryKey() throws Exception {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).contains("PRIMARY KEY");
        assertThat(ddl).contains("id");
    }

    @Test
    @DisplayName("加载表列表")
    void loadTables_ShouldReturnAllTables() throws Exception {
        Result<List<SchemaObject>> result = schemaService.loadTables(testConnectionId, null);

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> tables = result.getData();

        assertThat(tables).isNotEmpty();
        assertThat(tables).extracting(SchemaObject::getName).contains("users");
        assertThat(tables).allMatch(t -> t.getType() == ObjectType.TABLE || t.getType() == ObjectType.VIEW);
    }

    @Test
    @DisplayName("加载Schema列表")
    void loadSchemas_ShouldReturnSchemas() {
        Result<List<SchemaObject>> result = schemaService.loadSchemas(testConnectionId);

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> schemas = result.getData();

        assertThat(schemas).isNotNull();
    }

    @Test
    @DisplayName("加载表子对象 - 列")
    void loadTableChildren_ShouldReturnColumns() throws Exception {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");

        Result<List<SchemaObject>> result = schemaService.loadTableChildren(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> children = result.getData();

        assertThat(children).isNotEmpty();
        assertThat(children).extracting(SchemaObject::getType)
                .contains(ObjectType.COLUMN);
        assertThat(children).extracting(SchemaObject::getName)
                .contains("id", "name", "age");
    }

    @Test
    @DisplayName("加载表子对象 - 主键")
    void loadTableChildren_ShouldReturnPrimaryKey() throws Exception {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");

        Result<List<SchemaObject>> result = schemaService.loadTableChildren(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        List<SchemaObject> children = result.getData();

        assertThat(children).extracting(SchemaObject::getType)
                .contains(ObjectType.PRIMARY_KEY);
    }

    @Test
    @DisplayName("DDL对比 - 检测差异")
    void compareDDL_ShouldDetectDifferences() {
        String ddl1 = """
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                age INT
            );
            """;

        String ddl2 = """
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                age INT,
                email VARCHAR(255),
                status VARCHAR(20) DEFAULT 'active'
            );
            """;

        Result<List<String>> result = schemaService.compareDDL(ddl1, ddl2);

        assertThat(result.isSuccess()).isTrue();
        List<String> differences = result.getData();

        assertThat(differences).isNotEmpty();
        assertThat(differences).anyMatch(d -> d.startsWith("+"));
        assertThat(differences).anyMatch(d -> d.contains("email"));
        assertThat(differences).anyMatch(d -> d.contains("status"));
    }

    @Test
    @DisplayName("DDL对比 - 相同DDL无差异")
    void compareDDL_IdenticalDDL_ShouldReturnNoDifferences() {
        String ddl = """
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100) NOT NULL
            );
            """;

        Result<List<String>> result = schemaService.compareDDL(ddl, ddl);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isEmpty();
    }

    @Test
    @DisplayName("DDL对比 - 删除列检测")
    void compareDDL_RemovedColumn_ShouldShowAsMinus() {
        String ddl1 = """
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                old_col INT
            );
            """;

        String ddl2 = """
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100)
            );
            """;

        Result<List<String>> result = schemaService.compareDDL(ddl1, ddl2);

        assertThat(result.isSuccess()).isTrue();
        List<String> differences = result.getData();

        assertThat(differences).anyMatch(d -> d.startsWith("-"));
        assertThat(differences).anyMatch(d -> d.contains("old_col"));
    }

    @Test
    @DisplayName("权限不足时优雅降级 - 空Schema列表")
    void loadSchemas_PermissionDenied_ShouldReturnEmptyList() {
        DataSourceRegistry registry = new DataSourceRegistry();
        com.company.dbstudio.connection.model.ConnectionConfig config = new com.company.dbstudio.connection.model.ConnectionConfig();
        config.setId("invalid");
        config.setName("Invalid");
        config.setHost("localhost");
        config.setPort(0);
        config.setDatabase("nonexistent");
        config.setUsername("invalid");
        config.setPassword("wrong");

        SchemaService service = new SchemaService();
        Result<List<SchemaObject>> result = service.loadSchemas("invalid-id");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotEmpty();
    }

    @Test
    @DisplayName("权限不足时优雅降级 - 空表列表")
    void loadTables_PermissionDenied_ShouldReturnEmptyList() {
        SchemaService service = new SchemaService();
        Result<List<SchemaObject>> result = service.loadTables("invalid-id", null);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotEmpty();
    }

    @Test
    @DisplayName("不支持的对象类型返回失败")
    void generateDDL_UnsupportedType_ShouldReturnFailure() {
        SchemaObject column = new SchemaObject(ObjectType.COLUMN, "id", "id");

        Result<String> result = schemaService.generateDDL(testConnectionId, column);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("不支持");
    }

    @Test
    @DisplayName("生成的DDL格式正确")
    void generateTableDDL_ShouldBeWellFormatted() throws Exception {
        SchemaObject table = new SchemaObject(ObjectType.TABLE, "users", "users");
        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).startsWith("CREATE TABLE");
        assertThat(ddl).endsWith(";");
        assertThat(ddl).contains("(");
        assertThat(ddl).contains(")");
    }

    @Test
    @DisplayName("空表也能生成正确DDL")
    void generateTableDDL_EmptyTable_ShouldGenerateCorrectly() throws Exception {
        try (Connection conn = TestUtils.createH2Connection()) {
            TestUtils.createEmptyTable(conn, "empty_table");
        }

        SchemaObject table = new SchemaObject(ObjectType.TABLE, "empty_table", "empty_table");
        Result<String> result = schemaService.generateDDL(testConnectionId, table);

        assertThat(result.isSuccess()).isTrue();
        String ddl = result.getData();

        assertThat(ddl).contains("CREATE TABLE");
        assertThat(ddl).contains("empty_table");
    }
}
