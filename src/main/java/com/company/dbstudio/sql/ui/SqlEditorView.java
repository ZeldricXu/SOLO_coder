package com.company.dbstudio.sql.ui;

import com.company.dbstudio.connection.ConnectionManager;
import com.company.dbstudio.connection.model.ConnectionInfo;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.IndexSuggestion;
import com.company.dbstudio.sql.model.QueryResult;
import com.company.dbstudio.sql.service.QueryExecutor;
import com.company.dbstudio.sql.service.SqlParserService;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;

public class SqlEditorView extends BorderPane {
    private final ConnectionManager connectionManager;
    private final QueryExecutor queryExecutor;
    private final SqlParserService parserService;
    
    private final SqlCodeArea codeArea;
    private final ResultTabPane resultTabPane;
    private final ExecutionPlanView executionPlanView;
    private final QueryHistoryView historyView;
    
    private final SplitPane editorSplitPane;
    private final SplitPane mainSplitPane;
    private final Label statusLabel;
    private final ProgressIndicator progressIndicator;
    
    private String currentConnectionId;
    private volatile boolean isExecuting;

    public SqlEditorView() {
        this.connectionManager = ApplicationContext.getBean(ConnectionManager.class);
        this.queryExecutor = new QueryExecutor();
        this.parserService = SqlParserService.getInstance();
        
        this.codeArea = new SqlCodeArea();
        this.resultTabPane = new ResultTabPane();
        this.executionPlanView = new ExecutionPlanView();
        this.historyView = new QueryHistoryView();
        
        this.editorSplitPane = new SplitPane();
        this.mainSplitPane = new SplitPane();
        this.statusLabel = new Label("就绪");
        this.progressIndicator = new ProgressIndicator();
        
        initializeUI();
        setupEventHandlers();
    }

    private void initializeUI() {
        progressIndicator.setVisible(false);
        progressIndicator.setPrefSize(20, 20);
        
        HBox statusBox = new HBox(10, statusLabel, progressIndicator);
        statusBox.setStyle("-fx-padding: 5px 10px; -fx-background-color: #f8f9fa;");
        
        setTop(createToolbar());
        
        VBox editorBox = new VBox(codeArea, resultTabPane);
        VBox.setVgrow(codeArea, Priority.ALWAYS);
        VBox.setVgrow(resultTabPane, Priority.ALWAYS);
        editorSplitPane.getItems().addAll(editorBox, executionPlanView);
        editorSplitPane.setOrientation(Orientation.VERTICAL);
        editorSplitPane.setDividerPositions(0.6);
        
        mainSplitPane.getItems().addAll(editorSplitPane, historyView);
        mainSplitPane.setOrientation(Orientation.HORIZONTAL);
        mainSplitPane.setDividerPositions(0.7);
        
        setCenter(mainSplitPane);
        setBottom(statusBox);
        
        codeArea.setText("-- 在此输入SQL语句\n-- 按 Ctrl+Enter 执行当前语句\n-- 按 Ctrl+Shift+Enter 执行全部语句\n-- 按 Ctrl+E 查看执行计划\n\nSELECT * FROM your_table LIMIT 10;");
        
        updateConnectionStatus();
    }

    private ToolBar createToolbar() {
        ToolBar toolBar = new ToolBar();
        
        ComboBox<String> connectionCombo = new ComboBox<>();
        connectionCombo.setPromptText("选择连接");
        connectionCombo.setPrefWidth(200);
        refreshConnectionList(connectionCombo);
        
        connectionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                currentConnectionId = newVal.split(" - ")[0];
                updateTableAndColumnNames();
            }
        });
        
        Button executeBtn = new Button("▶ 执行");
        executeBtn.setOnAction(e -> executeCurrentStatement());
        
        Button executeAllBtn = new Button("▶▶ 全部执行");
        executeAllBtn.setOnAction(e -> executeAll());
        
        Button explainBtn = new Button("📊 执行计划");
        explainBtn.setOnAction(e -> explainPlan());
        
        Button cancelBtn = new Button("⏹ 取消");
        cancelBtn.setOnAction(e -> cancelExecution());
        cancelBtn.setDisable(true);
        
        Button formatBtn = new Button("🖹 格式化");
        formatBtn.setOnAction(e -> formatSql());
        
        Button clearBtn = new Button("🗑 清空");
        clearBtn.setOnAction(e -> clearEditor());
        
        Button showHistoryBtn = new Button("📜 历史");
        showHistoryBtn.setOnAction(e -> toggleHistoryView());
        
        Button showPlanBtn = new Button("📈 执行计划视图");
        showPlanBtn.setOnAction(e -> togglePlanView());
        
        toolBar.getItems().addAll(
                new Label("连接:"), connectionCombo,
                new Separator(),
                executeBtn, executeAllBtn, explainBtn, cancelBtn,
                new Separator(),
                formatBtn, clearBtn,
                new Separator(),
                showHistoryBtn, showPlanBtn
        );
        
        return toolBar;
    }

    private void setupEventHandlers() {
        codeArea.addEventHandler(SqlCodeArea.SqlEditorEvent.EXECUTE_STATEMENT, event -> {
            executeQuery(event.getSql(), false);
        });
        
        codeArea.addEventHandler(SqlCodeArea.SqlEditorEvent.EXECUTE_ALL, event -> {
            executeQuery(event.getSql(), false);
        });
        
        codeArea.addEventHandler(SqlCodeArea.SqlEditorEvent.EXPLAIN_PLAN, event -> {
            executeQuery(event.getSql(), true);
        });
        
        historyView.setOnSelectHandler(history -> {
            if (history != null && history.getSql() != null) {
                codeArea.setText(history.getSql());
            }
        });
        
        historyView.setOnExecuteHandler(history -> {
            if (history != null && history.getSql() != null) {
                executeQuery(history.getSql(), false);
            }
        });
        
        connectionManager.addConnectionListener((connectionId, connected) -> {
            Platform.runLater(() -> {
                ToolBar toolBar = (ToolBar) getTop();
                for (javafx.scene.Node node : toolBar.getItems()) {
                    if (node instanceof ComboBox) {
                        @SuppressWarnings("unchecked")
                        ComboBox<String> combo = (ComboBox<String>) node;
                        if (combo.getPromptText() != null && combo.getPromptText().contains("连接")) {
                            refreshConnectionList(combo);
                        }
                    }
                }
                updateConnectionStatus();
            });
        });
    }

    private void refreshConnectionList(ComboBox<String> combo) {
        String currentValue = combo.getValue();
        combo.getItems().clear();
        
        List<ConnectionInfo> connections = connectionManager.getActiveConnections();
        for (ConnectionInfo info : connections) {
            String display = info.getConfig().getId() + " - " + info.getConfig().getName();
            combo.getItems().add(display);
            if (currentConnectionId != null && currentConnectionId.equals(info.getConfig().getId())) {
                combo.setValue(display);
            }
        }
        
        if (combo.getItems().size() == 1) {
            combo.setValue(combo.getItems().get(0));
        }
    }

    private void updateTableAndColumnNames() {
        if (currentConnectionId != null) {
            Result<List<String>> tablesResult = queryExecutor.getTableColumns(currentConnectionId, "");
            if (tablesResult.isSuccess()) {
                codeArea.setTableNames(tablesResult.getData());
            }
        }
    }

    private void updateConnectionStatus() {
        Optional<ConnectionInfo> currentConn = connectionManager.getCurrentConnection();
        if (currentConn.isPresent()) {
            ConnectionInfo info = currentConn.get();
            statusLabel.setText("已连接: " + info.getConfig().getName() + 
                              " | 活跃查询: " + info.getActiveQueries() +
                              " | 最后使用: " + info.getLastUsedTime());
            currentConnectionId = info.getConfig().getId();
        } else {
            statusLabel.setText("未连接 - 请选择或创建一个数据库连接");
        }
    }

    private void executeCurrentStatement() {
        String sql = codeArea.getCurrentStatement();
        executeQuery(sql, false);
    }

    private void executeAll() {
        String sql = codeArea.getText();
        executeQuery(sql, false);
    }

    private void explainPlan() {
        String sql = codeArea.getCurrentStatement();
        executeQuery(sql, true);
    }

    private void executeQuery(String sql, boolean withPlan) {
        if (isExecuting) {
            showWarning("已有查询正在执行，请等待完成或取消。");
            return;
        }
        
        if (currentConnectionId == null) {
            Optional<ConnectionInfo> currentConn = connectionManager.getCurrentConnection();
            if (currentConn.isPresent()) {
                currentConnectionId = currentConn.get().getConfig().getId();
            } else {
                showError("请先选择一个数据库连接。");
                return;
            }
        }
        
        if (StringUtils.isEmpty(sql)) {
            showWarning("SQL语句不能为空。");
            return;
        }
        
        isExecuting = true;
        progressIndicator.setVisible(true);
        statusLabel.setText("正在执行查询...");
        setToolbarButtonsDisabled(true);
        
        queryExecutor.executeQueryAsync(currentConnectionId, sql, withPlan, 1000, result -> {
            isExecuting = false;
            progressIndicator.setVisible(false);
            setToolbarButtonsDisabled(false);
            
            if (result.isSuccess()) {
                QueryResult queryResult = result.getData();
                resultTabPane.addResultTab(queryResult);
                
                if (withPlan && queryResult.getExecutionPlan() != null) {
                    executionPlanView.setPlan(queryResult.getExecutionPlan());
                    
                    List<IndexSuggestion> suggestions = parserService.analyzeForIndexSuggestions(
                            sql, queryResult.getExecutionPlan());
                    executionPlanView.setSuggestions(suggestions);
                }
                
                statusLabel.setText("查询完成 - " + 
                    StringUtils.formatDuration(queryResult.getExecutionTime()) +
                    (queryResult.getRowCount() > 0 ? " | " + queryResult.getRowCount() + " 行" : "") +
                    (queryResult.getAffectedRows() > 0 ? " | 影响 " + queryResult.getAffectedRows() + " 行" : ""));
            } else {
                QueryResult errorResult = new QueryResult(sql, currentConnectionId);
                errorResult.setErrorMessage(result.getError());
                errorResult.setHasError(true);
                resultTabPane.addResultTab(errorResult);
                
                statusLabel.setText("查询失败: " + result.getError());
            }
        });
    }

    private void cancelExecution() {
        if (isExecuting) {
            queryExecutor.cancel();
            statusLabel.setText("正在取消查询...");
        }
    }

    private void setToolbarButtonsDisabled(boolean disabled) {
        ToolBar toolBar = (ToolBar) getTop();
        for (javafx.scene.Node node : toolBar.getItems()) {
            if (node instanceof Button btn) {
                String text = btn.getText();
                if (text != null && text.contains("取消")) {
                    btn.setDisable(!disabled);
                } else if (text != null && (text.contains("执行") || text.contains("计划"))) {
                    btn.setDisable(disabled);
                }
            }
        }
    }

    private void formatSql() {
        String sql = codeArea.getText();
        if (!StringUtils.isEmpty(sql)) {
            List<String> formatted = parserService.formatSql(sql);
            codeArea.setText(String.join(";\n\n", formatted) + ";");
        }
    }

    private void clearEditor() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认清空");
        alert.setHeaderText("确定要清空编辑器内容吗？");
        alert.setContentText("此操作不可恢复。");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                codeArea.clear();
            }
        });
    }

    private void toggleHistoryView() {
        if (mainSplitPane.getItems().size() == 2) {
            mainSplitPane.getItems().remove(historyView);
        } else {
            mainSplitPane.getItems().add(historyView);
            mainSplitPane.setDividerPositions(0.7);
        }
    }

    private void togglePlanView() {
        if (editorSplitPane.getItems().size() == 2) {
            editorSplitPane.getItems().remove(executionPlanView);
        } else {
            editorSplitPane.getItems().add(executionPlanView);
            editorSplitPane.setDividerPositions(0.6);
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("警告");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public SqlCodeArea getCodeArea() {
        return codeArea;
    }

    public ResultTabPane getResultTabPane() {
        return resultTabPane;
    }

    public ExecutionPlanView getExecutionPlanView() {
        return executionPlanView;
    }

    public QueryHistoryView getHistoryView() {
        return historyView;
    }

    public void setCurrentConnectionId(String connectionId) {
        this.currentConnectionId = connectionId;
        updateTableAndColumnNames();
        updateConnectionStatus();
    }

    public String getCurrentConnectionId() {
        return currentConnectionId;
    }
}
