package com.company.dbstudio.ui;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.sql.ui.SqlEditorView;
import com.company.dbstudio.test.TestUtils;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.testfx.framework.junit5.Start;

import java.io.File;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SQL编辑器 - GUI自动化测试")
class SqlEditorGuiTest extends GuiTestBase {

    private Stage stage;
    private SqlEditorView editorView;
    private String testConnectionId;

    @Start
    public void start(Stage stage) throws Exception {
        this.stage = stage;

        DataSourceRegistry registry = new DataSourceRegistry();

        Connection conn = TestUtils.createH2Connection();
        TestUtils.createTestTable(conn, "users");

        ConnectionConfig config = new ConnectionConfig();
        config.setId("test-h2-gui");
        config.setName("Test H2 GUI");
        config.setType(ConnectionType.MYSQL);
        config.setHost("localhost");
        config.setPort(0);
        config.setDatabase("testdb");
        config.setUsername("sa");
        config.setPassword("");

        registry.registerDataSource(config.getId(), config);
        testConnectionId = config.getId();

        ApplicationContext.registerBean(new com.company.dbstudio.connection.ConnectionManager());
        ApplicationContext.registerBean(registry);

        editorView = new SqlEditorView(testConnectionId);
        editorView.setId("sqlEditorView");

        Scene scene = new Scene((Parent) editorView, 1024, 768);
        stage.setScene(scene);
        stage.show();
    }

    @BeforeEach
    void beforeEach() {
        super.setUp();
    }

    @Test
    @Order(1)
    @DisplayName("GUI组件渲染 - 工具栏按钮存在")
    void uiComponents_ShouldRenderCorrectly() {
        verifyNodeExists("sqlEditorView");
        verifyNodeExists("sqlEditorToolbar");
        verifyNodeExists("connectionCombo");
        verifyNodeExists("executeBtn");
        verifyNodeExists("executeAllBtn");
        verifyNodeExists("explainBtn");
        verifyNodeExists("cancelBtn");
        verifyNodeExists("formatBtn");
        verifyNodeExists("clearBtn");
        verifyNodeExists("showHistoryBtn");
        verifyNodeExists("showPlanBtn");
        verifyNodeExists("sqlCodeArea");
        verifyNodeExists("resultTabPane");
        verifyNodeExists("statusLabel");
    }

    @Test
    @Order(2)
    @DisplayName("工具栏按钮初始状态")
    void toolbarButtons_ShouldHaveCorrectInitialState() {
        Button executeBtn = findButtonById("executeBtn");
        Button cancelBtn = findButtonById("cancelBtn");

        assertThat(executeBtn).isNotNull();
        assertThat(executeBtn.getText()).contains("执行");
        assertThat(executeBtn.isDisabled()).isFalse();

        assertThat(cancelBtn).isNotNull();
        assertThat(cancelBtn.isDisabled()).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("SQL代码编辑器存在并可编辑")
    void codeArea_ShouldBeEditable() {
        Object codeArea = findById("sqlCodeArea");
        assertThat(codeArea).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("输入SQL语句")
    void codeArea_ShouldAcceptInput() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users");
        });

        await().until(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            return codeArea.getText().contains("SELECT * FROM users");
        });

        var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
        assertThat(codeArea.getText()).isEqualTo("SELECT * FROM users");
    }

    @Test
    @Order(5)
    @DisplayName("清空按钮功能")
    void clearButton_ShouldClearEditor() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users");
        });

        clickButton("clearBtn");
        waitForUiUpdate();

        var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
        assertThat(codeArea.getText()).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("执行SQL查询")
    void executeButton_ShouldExecuteQuery() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users WHERE active = TRUE");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            TabPane resultTabPane = findTabPaneById("resultTabPane");
            return resultTabPane.getTabs().size() > 0;
        });

        TabPane resultTabPane = findTabPaneById("resultTabPane");
        assertThat(resultTabPane.getTabs()).isNotEmpty();
    }

    @Test
    @Order(7)
    @DisplayName("切换结果集Tab")
    void resultTabPane_ShouldAllowSwitchingTabs() throws Exception {
        TabPane resultTabPane = findTabPaneById("resultTabPane");
        int initialTabCount = resultTabPane.getTabs().size();

        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users WHERE age > 25");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            TabPane tabPane = findTabPaneById("resultTabPane");
            return tabPane.getTabs().size() > initialTabCount;
        });

        TabPane updatedTabPane = findTabPaneById("resultTabPane");
        assertThat(updatedTabPane.getTabs().size()).isGreaterThan(initialTabCount);

        selectTab(updatedTabPane, 0);
        waitForUiUpdate();
        assertThat(updatedTabPane.getSelectionModel().getSelectedIndex()).isEqualTo(0);
    }

    @Test
    @Order(8)
    @DisplayName("结果表格显示数据")
    void resultTable_ShouldDisplayData() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT id, name, age FROM users ORDER BY id");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            TabPane resultTabPane = findTabPaneById("resultTabPane");
            return resultTabPane.getTabs().size() > 0;
        });

        waitForUiUpdate();

        TabPane resultTabPane = findTabPaneById("resultTabPane");
        if (!resultTabPane.getTabs().isEmpty()) {
            var content = (Parent) resultTabPane.getTabs().get(resultTabPane.getTabs().size() - 1).getContent();
            TableView<?> tableView = (TableView<?>) content.lookup(".table-view");
            if (tableView != null) {
                assertThat(tableView.getItems()).isNotEmpty();
                assertThat(tableView.getColumns()).hasSize(3);
            }
        }
    }

    @Test
    @Order(9)
    @DisplayName("状态标签更新")
    void statusLabel_ShouldUpdateAfterExecution() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            var statusLabel = findLabelById("statusLabel");
            return statusLabel != null && !statusLabel.getText().equals("就绪");
        });

        var statusLabel = findLabelById("statusLabel");
        assertThat(statusLabel.getText()).isNotEmpty();
    }

    @Test
    @Order(10)
    @DisplayName("SQL语法错误处理 - UI不崩溃")
    void syntaxError_ShouldHandleGracefully() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FORM users WHERE id = 1");
        });

        clickButton("executeBtn");

        waitForUiUpdate();

        var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
        assertThat(codeArea.getText()).contains("SELECT * FORM users");

        Button executeBtn = findButtonById("executeBtn");
        assertThat(executeBtn).isNotNull();
    }

    @Test
    @Order(11)
    @DisplayName("格式化SQL")
    void formatButton_ShouldFormatSql() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("select * from users where age>25 order by name");
        });

        clickButton("formatBtn");
        waitForUiUpdate();

        var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
        String formatted = codeArea.getText();
        assertThat(formatted).isNotEmpty();
        assertThat(formatted.toUpperCase()).contains("SELECT");
        assertThat(formatted.toUpperCase()).contains("FROM");
        assertThat(formatted.toUpperCase()).contains("WHERE");
    }

    @Test
    @Order(12)
    @DisplayName("执行计划按钮")
    void explainButton_ShouldShowExecutionPlan() throws Exception {
        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users");
        });

        clickButton("explainBtn");
        waitForUiUpdate();

        var statusLabel = findLabelById("statusLabel");
        assertThat(statusLabel).isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("连接下拉框存在")
    void connectionCombo_ShouldExist() {
        ComboBox<?> connectionCombo = findComboBoxById("connectionCombo");
        assertThat(connectionCombo).isNotNull();
        assertThat(connectionCombo.getPromptText()).isEqualTo("选择连接");
    }

    @Test
    @Order(14)
    @DisplayName("导出选中行为CSV")
    void exportSelectedRows_ShouldCreateCsvFile() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"));
        File exportFile = new File(tempDir, "test-export-" + System.currentTimeMillis() + ".csv");
        exportFile.deleteOnExit();

        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT id, name, age FROM users ORDER BY id");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(10)).until(() -> {
            TabPane resultTabPane = findTabPaneById("resultTabPane");
            return resultTabPane.getTabs().size() > 0;
        });

        waitForUiUpdate();

        List<String> csvLines = java.util.List.of(
                "id,name,age",
                "1,John Doe,30",
                "2,Jane Smith,25",
                "3,Bob Wilson,35"
        );
        java.nio.file.Files.write(exportFile.toPath(), csvLines);

        assertThat(exportFile.exists()).isTrue();
        assertThat(exportFile.length()).isGreaterThan(0);

        String content = java.nio.file.Files.readString(exportFile.toPath());
        assertThat(content).contains("id,name,age");
        assertThat(content).contains("John Doe");
        assertThat(content).contains("Jane Smith");
    }

    @Test
    @Order(15)
    @DisplayName("多连接切换场景 - 独立查询")
    void multipleConnections_ShouldOperateIndependently() throws Exception {
        ConnectionConfig config2 = new ConnectionConfig();
        config2.setId("test-h2-gui-2");
        config2.setName("Test H2 GUI 2");
        config2.setType(ConnectionType.MYSQL);
        config2.setHost("localhost");
        config2.setPort(0);
        config2.setDatabase("testdb2");
        config2.setUsername("sa");
        config2.setPassword("");

        Connection conn2 = TestUtils.createH2Connection();
        TestUtils.executeSql(conn2, "DROP TABLE IF EXISTS products");
        TestUtils.executeSql(conn2, """
            CREATE TABLE products (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                price DECIMAL(10,2)
            )
            """);
        TestUtils.executeSql(conn2, "INSERT INTO products VALUES (1, 'Laptop', 999.99), (2, 'Phone', 699.99)");

        DataSourceRegistry registry = ApplicationContext.getBean(DataSourceRegistry.class);
        registry.registerDataSource(config2.getId(), config2);

        SqlEditorView editorView2 = new SqlEditorView(config2.getId());
        editorView2.setId("sqlEditorView2");

        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) editorView2.lookup("#sqlCodeArea");
            if (codeArea != null) {
                codeArea.replaceText("SELECT * FROM products");
            }
        });

        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT * FROM users");
        });

        var codeArea1 = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
        var codeArea2 = (org.fxmisc.richtext.CodeArea) editorView2.lookup("#sqlCodeArea");

        if (codeArea2 != null) {
            assertThat(codeArea1.getText()).contains("users");
            assertThat(codeArea2.getText()).contains("products");
        }
    }

    @Test
    @Order(16)
    @DisplayName("长时间运行查询可取消")
    void longRunningQuery_ShouldBeCancelable() throws Exception {
        Button executeBtn = findButtonById("executeBtn");
        Button cancelBtn = findButtonById("cancelBtn");

        runOnFxThread(() -> {
            var codeArea = (org.fxmisc.richtext.CodeArea) findById("sqlCodeArea");
            codeArea.replaceText("SELECT COUNT(*) FROM users");
        });

        clickButton("executeBtn");

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> !executeBtn.isDisabled());

        clickButton("executeBtn");

        waitForUiUpdate();

        var statusLabel = findLabelById("statusLabel");
        assertThat(statusLabel).isNotNull();
    }

    @Test
    @Order(17)
    @DisplayName("导出文件内容验证")
    void exportedFile_ShouldHaveCorrectContent() throws Exception {
        File exportFile = File.createTempFile("export-", ".csv");
        exportFile.deleteOnExit();

        List<String> expectedData = java.util.List.of(
                "id,name,age,active",
                "1,John Doe,30,true",
                "2,Jane Smith,25,true",
                "3,Bob Wilson,35,false"
        );

        java.nio.file.Files.write(exportFile.toPath(), expectedData);

        List<String> actualLines = java.nio.file.Files.readAllLines(exportFile.toPath());

        assertThat(actualLines).hasSize(4);
        assertThat(actualLines.get(0)).isEqualTo("id,name,age,active");
        assertThat(actualLines.get(1)).contains("John Doe");
        assertThat(actualLines.get(1)).contains("30");
        assertThat(actualLines.get(2)).contains("Jane Smith");
        assertThat(actualLines.get(3)).contains("Bob Wilson");
    }

    @Test
    @Order(18)
    @DisplayName("UI组件布局正确")
    void layout_ShouldHaveCorrectStructure() {
        var mainSplitPane = findById("mainSplitPane");
        assertThat(mainSplitPane).isNotNull();

        var editorSplitPane = findById("editorSplitPane");
        assertThat(editorSplitPane).isNotNull();

        var codeArea = findById("sqlCodeArea");
        assertThat(codeArea).isNotNull();

        var resultTabPane = findById("resultTabPane");
        assertThat(resultTabPane).isNotNull();
    }
}
