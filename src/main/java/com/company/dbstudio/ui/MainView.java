package com.company.dbstudio.ui;

import com.company.dbstudio.connection.ConnectionConfig;
import com.company.dbstudio.connection.ConnectionManager;
import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.EventBus;
import com.company.dbstudio.core.exception.GlobalExceptionHandler;
import com.company.dbstudio.data.ui.DataEditorView;
import com.company.dbstudio.etl.ui.ImportExportView;
import com.company.dbstudio.performance.service.PerformanceAnalyzerService;
import com.company.dbstudio.performance.ui.PerformanceAnalyzerView;
import com.company.dbstudio.schema.ui.SchemaBrowserView;
import com.company.dbstudio.sql.service.ExecutionPlanParser;
import com.company.dbstudio.sql.service.QueryExecutor;
import com.company.dbstudio.sql.service.SqlParserService;
import com.company.dbstudio.sql.ui.SqlEditorView;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

public class MainView {

    private final BorderPane root;
    private final ConnectionManager connectionManager;
    private final DialogManager dialogManager;

    private SplitPane mainSplit;
    private TreeView<String> connectionTree;
    private TabPane workspaceTabPane;
    private Label statusLabel;

    public MainView() {
        this.root = new BorderPane();
        this.connectionManager = ApplicationContext.getBean(ConnectionManager.class);
        this.dialogManager = ApplicationContext.getBean(DialogManager.class);

        registerServices();
        initializeUI();
        setupEventHandlers();
        loadConnections();
    }

    private void registerServices() {
        ApplicationContext.registerBean(new SqlParserService());
        ApplicationContext.registerBean(new QueryExecutor());
        ApplicationContext.registerBean(new ExecutionPlanParser());
        ApplicationContext.registerBean(new com.company.dbstudio.data.service.DataBrowseService(
                ApplicationContext.getBean(DataSourceRegistry.class)
        ));
        ApplicationContext.registerBean(new com.company.dbstudio.schema.service.SchemaService(
                ApplicationContext.getBean(DataSourceRegistry.class)
        ));
        ApplicationContext.registerBean(new com.company.dbstudio.etl.service.ImportExportService(
                ApplicationContext.getBean(DataSourceRegistry.class)
        ));
        ApplicationContext.registerBean(new PerformanceAnalyzerService(
                ApplicationContext.getBean(DataSourceRegistry.class),
                ApplicationContext.getBean(ExecutionPlanParser.class),
                ApplicationContext.getBean(SqlParserService.class)
        ));
    }

    private void initializeUI() {
        root.setTop(createMenuBar());

        mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.25);
        root.setCenter(mainSplit);

        VBox leftPanel = createLeftPanel();
        VBox rightPanel = createRightPanel();

        mainSplit.getItems().addAll(leftPanel, rightPanel);
        VBox.setVgrow(mainSplit, Priority.ALWAYS);

        root.setBottom(createStatusBar());
    }

    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        Menu fileMenu = new Menu("文件");
        MenuItem newConnItem = new MenuItem("新建连接...");
        newConnItem.setOnAction(e -> showNewConnectionDialog());
        MenuItem exitItem = new MenuItem("退出");
        exitItem.setOnAction(e -> Platform.exit());
        fileMenu.getItems().addAll(newConnItem, new SeparatorMenuItem(), exitItem);

        Menu viewMenu = new Menu("视图");
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refreshConnections());
        viewMenu.getItems().add(refreshItem);

        Menu toolsMenu = new Menu("工具");
        MenuItem exportItem = new MenuItem("数据导入导出...");
        exportItem.setOnAction(e -> openImportExportView());
        MenuItem analyzeItem = new MenuItem("查询性能分析...");
        analyzeItem.setOnAction(e -> openPerformanceAnalyzer());
        toolsMenu.getItems().addAll(exportItem, analyzeItem);

        Menu helpMenu = new Menu("帮助");
        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> showAboutDialog());
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, viewMenu, toolsMenu, helpMenu);
        return menuBar;
    }

    private VBox createLeftPanel() {
        VBox leftPanel = new VBox(5);
        leftPanel.setStyle("-fx-padding: 5;");

        TitledPane connectionsPane = new TitledPane();
        connectionsPane.setText("连接管理");
        connectionsPane.setCollapsible(false);

        VBox connectionsContent = new VBox(5);

        ToolBar toolBar = new ToolBar();
        Button addBtn = new Button("+");
        addBtn.setTooltip(new Tooltip("新建连接"));
        addBtn.setOnAction(e -> showNewConnectionDialog());

        Button editBtn = new Button("✎");
        editBtn.setTooltip(new Tooltip("编辑连接"));
        editBtn.setOnAction(e -> editSelectedConnection());

        Button deleteBtn = new Button("-");
        deleteBtn.setTooltip(new Tooltip("删除连接"));
        deleteBtn.setOnAction(e -> deleteSelectedConnection());

        Button connectBtn = new Button("▶");
        connectBtn.setTooltip(new Tooltip("连接"));
        connectBtn.setOnAction(e -> connectSelectedConnection());

        Button refreshBtn = new Button("⟳");
        refreshBtn.setTooltip(new Tooltip("刷新"));
        refreshBtn.setOnAction(e -> refreshConnections());

        toolBar.getItems().addAll(addBtn, editBtn, deleteBtn, connectBtn, refreshBtn);

        connectionTree = new TreeView<>();
        connectionTree.setShowRoot(false);
        TreeItem<String> rootItem = new TreeItem<>("root");
        connectionTree.setRoot(rootItem);

        connectionTree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null
                        && !selected.getValue().startsWith("📁")
                        && selected.getParent() != rootItem) {
                    openConnectionInEditor(selected);
                }
            }
        });

        connectionsContent.getChildren().addAll(toolBar, connectionTree);
        VBox.setVgrow(connectionTree, Priority.ALWAYS);
        connectionsPane.setContent(connectionsContent);

        TitledPane recentPane = new TitledPane();
        recentPane.setText("最近使用");
        recentPane.setCollapsible(true);
        recentPane.setExpanded(false);
        ListView<String> recentList = new ListView<>();
        recentList.getItems().addAll("(暂无记录)");
        recentPane.setContent(recentList);

        TitledPane favoritePane = new TitledPane();
        favoritePane.setText("我的收藏");
        favoritePane.setCollapsible(true);
        favoritePane.setExpanded(false);
        ListView<String> favoriteList = new ListView<>();
        favoriteList.getItems().addAll("(暂无记录)");
        favoritePane.setContent(favoriteList);

        leftPanel.getChildren().addAll(connectionsPane, recentPane, favoritePane);
        VBox.setVgrow(connectionsPane, Priority.ALWAYS);

        return leftPanel;
    }

    private VBox createRightPanel() {
        VBox rightPanel = new VBox(5);
        rightPanel.setStyle("-fx-padding: 5;");

        workspaceTabPane = new TabPane();
        workspaceTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        Tab welcomeTab = new Tab("欢迎");
        welcomeTab.setClosable(false);
        welcomeTab.setContent(createWelcomePane());
        workspaceTabPane.getTabs().add(welcomeTab);

        rightPanel.getChildren().add(workspaceTabPane);
        VBox.setVgrow(workspaceTabPane, Priority.ALWAYS);

        return rightPanel;
    }

    private BorderPane createWelcomePane() {
        BorderPane welcomePane = new BorderPane();
        welcomePane.setStyle("-fx-padding: 50; -fx-background-color: #f8f9fa;");

        VBox content = new VBox(20);
        content.setStyle("-fx-alignment: center;");

        Label title = new Label("欢迎使用 DBStudio");
        title.setStyle("-fx-font-size: 32; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        Label subtitle = new Label("轻量级、可扩展的数据库桌面管理工具");
        subtitle.setStyle("-fx-font-size: 16; -fx-text-fill: #7f8c8d;");

        VBox quickActions = new VBox(10);
        quickActions.setStyle("-fx-padding: 30;");

        Hyperlink newConnLink = new Hyperlink("➕ 创建新的数据库连接");
        newConnLink.setStyle("-fx-font-size: 14;");
        newConnLink.setOnAction(e -> showNewConnectionDialog());

        Hyperlink browseLink = new Hyperlink("📖 浏览数据库表结构");
        browseLink.setStyle("-fx-font-size: 14;");

        Hyperlink queryLink = new Hyperlink("📝 打开SQL编辑器");
        queryLink.setStyle("-fx-font-size: 14;");

        Hyperlink importLink = new Hyperlink("📤 数据导入/导出");
        importLink.setStyle("-fx-font-size: 14;");
        importLink.setOnAction(e -> openImportExportView());

        Hyperlink analyzeLink = new Hyperlink("📊 查询性能分析");
        analyzeLink.setStyle("-fx-font-size: 14;");
        analyzeLink.setOnAction(e -> openPerformanceAnalyzer());

        quickActions.getChildren().addAll(newConnLink, browseLink, queryLink, importLink, analyzeLink);

        Label footer = new Label("DBStudio v1.0.0 | © 2024 Company DBA Team");
        footer.setStyle("-fx-font-size: 11; -fx-text-fill: #95a5a6;");

        content.getChildren().addAll(title, subtitle, quickActions, footer);
        welcomePane.setCenter(content);

        return welcomePane;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #e9ecef; -fx-border-color: #dee2e6; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("就绪");
        statusBar.getChildren().add(statusLabel);

        Label memoryLabel = new Label("内存: 0MB/0MB");
        memoryLabel.setStyle("-fx-padding-left: 20;");
        statusBar.getChildren().add(memoryLabel);

        return statusBar;
    }

    private void setupEventHandlers() {
        EventBus.getInstance().subscribe("connection.connected", event -> {
            Platform.runLater(() -> {
                ConnectionConfig config = (ConnectionConfig) event.getData();
                statusLabel.setText("已连接: " + config.getName());
            });
        });

        EventBus.getInstance().subscribe("connection.disconnected", event -> {
            Platform.runLater(() -> {
                statusLabel.setText("连接已断开");
            });
        });
    }

    private void loadConnections() {
        try {
            List<ConnectionConfig> connections = connectionManager.getAllConnections();
            TreeItem<String> rootItem = connectionTree.getRoot();
            rootItem.getChildren().clear();

            TreeItem<String> allConnections = new TreeItem<>("📁 所有连接");
            allConnections.setExpanded(true);

            for (ConnectionConfig config : connections) {
                String icon = config.isConnected() ? "✅ " : "⚪ ";
                TreeItem<String> connItem = new TreeItem<>(icon + config.getName());
                connItem.setUserData(config.getId());
                allConnections.getChildren().add(connItem);
            }

            rootItem.getChildren().add(allConnections);

            if (!connections.isEmpty()) {
                TreeItem<String> favorites = new TreeItem<>("📁 收藏");
                for (ConnectionConfig config : connections) {
                    if (config.isFavorite()) {
                        String icon = config.isConnected() ? "✅ " : "⚪ ";
                        TreeItem<String> connItem = new TreeItem<>(icon + config.getName());
                        connItem.setUserData(config.getId());
                        favorites.getChildren().add(connItem);
                    }
                }
                if (!favorites.getChildren().isEmpty()) {
                    rootItem.getChildren().add(favorites);
                }
            }
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
        }
    }

    private void refreshConnections() {
        loadConnections();
        statusLabel.setText("已刷新");
    }

    private void showNewConnectionDialog() {
        NewConnectionDialog dialog = new NewConnectionDialog();
        Optional<ConnectionConfig> result = dialog.showAndWait();
        result.ifPresent(config -> {
            try {
                connectionManager.saveConnection(config);
                loadConnections();
                statusLabel.setText("连接已创建: " + config.getName());
            } catch (Exception e) {
                GlobalExceptionHandler.handleException(e);
                dialogManager.showError("创建失败", e.getMessage());
            }
        });
    }

    private void editSelectedConnection() {
        TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getUserData() == null) {
            dialogManager.showWarning("提示", "请先选择一个连接");
            return;
        }
        String connId = (String) selected.getUserData();
        ConnectionConfig config = connectionManager.getConnection(connId);
        if (config != null) {
            NewConnectionDialog dialog = new NewConnectionDialog(config);
            Optional<ConnectionConfig> result = dialog.showAndWait();
            result.ifPresent(updated -> {
                try {
                    connectionManager.saveConnection(updated);
                    loadConnections();
                    statusLabel.setText("连接已更新: " + updated.getName());
                } catch (Exception e) {
                    GlobalExceptionHandler.handleException(e);
                    dialogManager.showError("更新失败", e.getMessage());
                }
            });
        }
    }

    private void deleteSelectedConnection() {
        TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getUserData() == null) {
            dialogManager.showWarning("提示", "请先选择一个连接");
            return;
        }
        String connId = (String) selected.getUserData();
        if (dialogManager.showConfirmation("确认删除", "确定要删除该连接吗？")) {
            try {
                connectionManager.deleteConnection(connId);
                loadConnections();
                statusLabel.setText("连接已删除");
            } catch (Exception e) {
                GlobalExceptionHandler.handleException(e);
                dialogManager.showError("删除失败", e.getMessage());
            }
        }
    }

    private void connectSelectedConnection() {
        TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getUserData() == null) {
            dialogManager.showWarning("提示", "请先选择一个连接");
            return;
        }
        String connId = (String) selected.getUserData();
        try {
            connectionManager.connect(connId);
            loadConnections();
            statusLabel.setText("连接成功");
            openConnectionInEditor(selected);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("连接失败", e.getMessage());
        }
    }

    private void openConnectionInEditor(TreeItem<String> selected) {
        String connId = (String) selected.getUserData();
        String connName = selected.getValue().replaceAll("^[\\u2705\\u26AA]\\s*", "");

        openSqlEditor(connId, connName);
    }

    private void openSqlEditor(String connectionId, String connectionName) {
        try {
            SqlEditorView editorView = new SqlEditorView(connectionId);
            Tab tab = new Tab("SQL编辑器 - " + connectionName, editorView);
            workspaceTabPane.getTabs().add(tab);
            workspaceTabPane.getSelectionModel().select(tab);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("打开编辑器失败", e.getMessage());
        }
    }

    private void openSchemaBrowser(String connectionId, String connectionName) {
        try {
            SchemaBrowserView schemaView = new SchemaBrowserView(connectionId);
            Tab tab = new Tab("Schema浏览 - " + connectionName, schemaView);
            workspaceTabPane.getTabs().add(tab);
            workspaceTabPane.getSelectionModel().select(tab);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("打开Schema浏览器失败", e.getMessage());
        }
    }

    private void openDataEditor(String connectionId, String connectionName) {
        try {
            DataEditorView dataView = new DataEditorView(connectionId);
            Tab tab = new Tab("数据编辑 - " + connectionName, dataView);
            workspaceTabPane.getTabs().add(tab);
            workspaceTabPane.getSelectionModel().select(tab);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("打开数据编辑器失败", e.getMessage());
        }
    }

    private void openImportExportView() {
        TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getUserData() == null) {
            dialogManager.showWarning("提示", "请先选择一个连接");
            return;
        }
        String connId = (String) selected.getUserData();
        ConnectionConfig config = connectionManager.getConnection(connId);
        if (config == null || !config.isConnected()) {
            dialogManager.showWarning("提示", "请先连接到数据库");
            return;
        }

        try {
            ImportExportView importExportView = new ImportExportView(connId);
            Tab tab = new Tab("数据导入导出", importExportView);
            workspaceTabPane.getTabs().add(tab);
            workspaceTabPane.getSelectionModel().select(tab);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("打开导入导出失败", e.getMessage());
        }
    }

    private void openPerformanceAnalyzer() {
        TreeItem<String> selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getUserData() == null) {
            dialogManager.showWarning("提示", "请先选择一个连接");
            return;
        }
        String connId = (String) selected.getUserData();
        ConnectionConfig config = connectionManager.getConnection(connId);
        if (config == null || !config.isConnected()) {
            dialogManager.showWarning("提示", "请先连接到数据库");
            return;
        }

        try {
            PerformanceAnalyzerView analyzerView = new PerformanceAnalyzerView(connId);
            Tab tab = new Tab("性能分析", analyzerView);
            workspaceTabPane.getTabs().add(tab);
            workspaceTabPane.getSelectionModel().select(tab);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(e);
            dialogManager.showError("打开性能分析失败", e.getMessage());
        }
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于 DBStudio");
        alert.setHeaderText("DBStudio v1.0.0");
        alert.setContentText(
                "轻量级、可扩展的数据库桌面管理工具\n\n" +
                "技术栈:\n" +
                "  • Java 21 + JavaFX 21\n" +
                "  • HikariCP 5.1.0 (连接池)\n" +
                "  • JSqlParser 4.9 (SQL解析)\n" +
                "  • Apache POI + Parquet (数据导出)\n\n" +
                "© 2024 Company DBA Team"
        );
        alert.showAndWait();
    }

    public BorderPane getRoot() {
        return root;
    }
}
