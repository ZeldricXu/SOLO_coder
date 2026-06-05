package com.company.dbstudio.performance.ui;

import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.performance.model.SlowQueryInfo;
import com.company.dbstudio.performance.service.PerformanceAnalyzerService;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.IndexSuggestion;
import com.company.dbstudio.sql.ui.ExecutionPlanView;
import com.company.dbstudio.sql.ui.SqlCodeArea;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class PerformanceAnalyzerView extends BorderPane {

    private final String connectionId;
    private final PerformanceAnalyzerService performanceAnalyzerService;

    private SplitPane mainSplit;
    private TableView<SlowQueryInfo> slowQueryTable;
    private TextField sqlInputField;
    private TextArea tipsArea;
    private TabPane analysisTabPane;
    private Tab executionPlanTab;
    private Tab indexSuggestionsTab;
    private Tab slowQueriesTab;
    private TextField minDurationField;
    private TextField limitField;
    private Button analyzeBtn;
    private Button loadSlowQueriesBtn;
    private Label statusLabel;
    private SqlCodeArea sqlCodeArea;

    public PerformanceAnalyzerView(String connectionId) {
        this.connectionId = connectionId;
        this.performanceAnalyzerService = ApplicationContext.getBean(PerformanceAnalyzerService.class);

        initializeUI();
    }

    private void initializeUI() {
        setPadding(new Insets(10));

        mainSplit = new SplitPane();
        mainSplit.setOrientation(Orientation.VERTICAL);
        setCenter(mainSplit);

        VBox topPanel = createTopPanel();
        VBox bottomPanel = createBottomPanel();

        mainSplit.getItems().addAll(topPanel, bottomPanel);
        mainSplit.setDividerPositions(0.4);

        setBottom(createStatusBar());
    }

    private VBox createTopPanel() {
        VBox topPanel = new VBox(10);
        topPanel.setPadding(new Insets(0, 0, 10, 0));

        HBox controlBar = new HBox(15);
        controlBar.setAlignment(Pos.CENTER_LEFT);

        Label minDurationLabel = new Label("最小耗时(ms):");
        minDurationField = new TextField("1000");
        minDurationField.setPrefWidth(100);

        Label limitLabel = new Label("查询条数:");
        limitField = new TextField("50");
        limitField.setPrefWidth(80);

        loadSlowQueriesBtn = new Button("加载慢查询");
        loadSlowQueriesBtn.setOnAction(e -> loadSlowQueries());

        Button clearBtn = new Button("清空");
        clearBtn.setOnAction(e -> slowQueryTable.getItems().clear());

        controlBar.getChildren().addAll(
                minDurationLabel, minDurationField, limitLabel, limitField,
                loadSlowQueriesBtn, clearBtn
        );

        slowQueryTable = new TableView<>();
        slowQueryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<SlowQueryInfo, String> sqlCol = new TableColumn<>("SQL");
        sqlCol.setCellValueFactory(p -> new SimpleStringProperty(truncate(p.getValue().getSql(), 80)));
        sqlCol.setPrefWidth(400);

        TableColumn<SlowQueryInfo, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getQueryType()));
        typeCol.setPrefWidth(80);

        TableColumn<SlowQueryInfo, String> durationCol = new TableColumn<>("耗时");
        durationCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getExecutionTimeDisplay()));
        durationCol.setPrefWidth(100);

        TableColumn<SlowQueryInfo, Long> rowsExaminedCol = new TableColumn<>("扫描行数");
        rowsExaminedCol.setCellValueFactory(p -> new SimpleLongProperty(p.getValue().getRowsExamined()).asObject());
        rowsExaminedCol.setPrefWidth(100);

        TableColumn<SlowQueryInfo, Long> rowsSentCol = new TableColumn<>("返回行数");
        rowsSentCol.setCellValueFactory(p -> new SimpleLongProperty(p.getValue().getRowsSent()).asObject());
        rowsSentCol.setPrefWidth(100);

        TableColumn<SlowQueryInfo, String> dbCol = new TableColumn<>("数据库");
        dbCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().getDatabase()));
        dbCol.setPrefWidth(100);

        TableColumn<SlowQueryInfo, String> timeCol = new TableColumn<>("执行时间");
        timeCol.setCellValueFactory(p -> new SimpleStringProperty(
                p.getValue().getExecuteTime() != null ?
                p.getValue().getExecuteTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "-"
        ));
        timeCol.setPrefWidth(150);

        slowQueryTable.getColumns().addAll(sqlCol, typeCol, durationCol, rowsExaminedCol, rowsSentCol, dbCol, timeCol);

        slowQueryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getSql() != null) {
                sqlInputField.setText(newVal.getSql());
                sqlCodeArea.replaceText(newVal.getSql());
            }
        });

        slowQueryTable.setRowFactory(tv -> {
            TableRow<SlowQueryInfo> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SlowQueryInfo query = row.getItem();
                    if (query != null && query.getSql() != null) {
                        sqlInputField.setText(query.getSql());
                        sqlCodeArea.replaceText(query.getSql());
                        analyzeQuery();
                    }
                }
            });
            return row;
        });

        topPanel.getChildren().addAll(controlBar, slowQueryTable);
        VBox.setVgrow(slowQueryTable, Priority.ALWAYS);

        return topPanel;
    }

    private VBox createBottomPanel() {
        VBox bottomPanel = new VBox(10);

        VBox sqlInputPanel = new VBox(5);
        Label sqlLabel = new Label("待分析SQL:");
        sqlLabel.setStyle("-fx-font-weight: bold;");

        HBox sqlToolbar = new HBox(10);
        sqlInputField = new TextField();
        sqlInputField.setPromptText("输入或选择要分析的SQL语句...");
        HBox.setHgrow(sqlInputField, Priority.ALWAYS);

        analyzeBtn = new Button("分析查询");
        analyzeBtn.setOnAction(e -> analyzeQuery());

        Button explainBtn = new Button("查看执行计划");
        explainBtn.setOnAction(e -> showExecutionPlan());

        Button suggestBtn = new Button("索引建议");
        suggestBtn.setOnAction(e -> generateIndexSuggestions());

        sqlToolbar.getChildren().addAll(sqlInputField, analyzeBtn, explainBtn, suggestBtn);

        sqlCodeArea = new SqlCodeArea();
        sqlCodeArea.setPrefHeight(100);

        sqlInputPanel.getChildren().addAll(sqlLabel, sqlToolbar, sqlCodeArea);

        analysisTabPane = new TabPane();

        slowQueriesTab = new Tab("慢查询分析", createSlowQueryAnalysisPanel());
        slowQueriesTab.setClosable(false);

        executionPlanTab = new Tab("执行计划", new Label("请先选择或输入SQL并点击分析"));
        executionPlanTab.setClosable(false);

        indexSuggestionsTab = new Tab("索引建议", new Label("请先点击索引建议按钮"));
        indexSuggestionsTab.setClosable(false);

        Tab optimizationTipsTab = new Tab("优化提示", createOptimizationTipsPanel());
        optimizationTipsTab.setClosable(false);

        analysisTabPane.getTabs().addAll(slowQueriesTab, executionPlanTab, indexSuggestionsTab, optimizationTipsTab);

        bottomPanel.getChildren().addAll(sqlInputPanel, analysisTabPane);
        VBox.setVgrow(analysisTabPane, Priority.ALWAYS);

        return bottomPanel;
    }

    private BorderPane createSlowQueryAnalysisPanel() {
        BorderPane panel = new BorderPane();
        panel.setPadding(new Insets(10));

        VBox statsBox = new VBox(10);
        statsBox.setPadding(new Insets(10));
        statsBox.setStyle("-fx-border-color: #e0e0e0; -fx-border-radius: 5;");

        Label statsTitle = new Label("慢查询统计");
        statsTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");

        Label totalLabel = new Label("总慢查询数: 0");
        totalLabel.setId("totalLabel");
        Label avgDurationLabel = new Label("平均耗时: -");
        avgDurationLabel.setId("avgDurationLabel");
        Label maxDurationLabel = new Label("最大耗时: -");
        maxDurationLabel.setId("maxDurationLabel");
        Label selectCountLabel = new Label("SELECT查询: 0");
        selectCountLabel.setId("selectCountLabel");
        Label dmlCountLabel = new Label("DML操作: 0");
        dmlCountLabel.setId("dmlCountLabel");

        statsBox.getChildren().addAll(statsTitle, totalLabel, avgDurationLabel, maxDurationLabel,
                selectCountLabel, dmlCountLabel);

        tipsArea = new TextArea();
        tipsArea.setEditable(false);
        tipsArea.setWrapText(true);
        tipsArea.setPrefHeight(200);
        tipsArea.setText("加载慢查询后将显示分析结果和建议...");

        VBox content = new VBox(10, statsBox, new Label("分析建议:"), tipsArea);
        panel.setCenter(content);

        return panel;
    }

    private BorderPane createOptimizationTipsPanel() {
        BorderPane panel = new BorderPane();
        panel.setPadding(new Insets(10));

        TextArea tipsTextArea = new TextArea();
        tipsTextArea.setEditable(false);
        tipsTextArea.setWrapText(true);
        tipsTextArea.setId("tipsTextArea");
        tipsTextArea.setText("请先输入SQL并点击分析查询按钮以获取优化建议...");

        panel.setCenter(tipsTextArea);
        return panel;
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(5, 0, 0, 0));

        statusLabel = new Label("就绪");

        ProgressIndicator progress = new ProgressIndicator();
        progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        progress.setVisible(false);
        progress.setPrefSize(16, 16);

        statusBar.getChildren().addAll(progress, statusLabel);

        statusLabel.setId("statusLabel");
        progress.setId("progressIndicator");

        return statusBar;
    }

    private void loadSlowQueries() {
        try {
            long minDuration = Long.parseLong(minDurationField.getText().trim());
            int limit = Integer.parseInt(limitField.getText().trim());

            setLoading(true, "正在加载慢查询...");
            performanceAnalyzerService.getSlowQueriesAsync(connectionId, minDuration, limit,
                    result -> Platform.runLater(() -> {
                        setLoading(false, null);
                        if (result.isSuccess()) {
                            List<SlowQueryInfo> queries = result.getData();
                            slowQueryTable.getItems().clear();
                            slowQueryTable.getItems().addAll(queries);
                            updateSlowQueryStats(queries);
                            statusLabel.setText("已加载 " + queries.size() + " 条慢查询");
                        } else {
                            showError("加载慢查询失败", result.getMessage());
                            statusLabel.setText("加载失败: " + result.getMessage());
                        }
                    })
            );
        } catch (NumberFormatException e) {
            showAlert(Alert.AlertType.WARNING, "输入错误", "请输入有效的数字");
        }
    }

    private void analyzeQuery() {
        String sql = getCurrentSql();
        if (sql.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先输入或选择SQL语句");
            return;
        }

        setLoading(true, "正在分析查询...");

        performanceAnalyzerService.analyzeExecutionPlan(connectionId, sql,
                result -> Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        ExecutionPlan plan = result.getData();
                        updateExecutionPlanView(plan);
                        generateOptimizationTips(plan, sql);
                        statusLabel.setText("分析完成");
                    } else {
                        showError("分析失败", result.getMessage());
                        statusLabel.setText("分析失败: " + result.getMessage());
                    }
                    setLoading(false, null);
                })
        );
    }

    private void showExecutionPlan() {
        String sql = getCurrentSql();
        if (sql.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先输入或选择SQL语句");
            return;
        }

        setLoading(true, "正在获取执行计划...");

        performanceAnalyzerService.analyzeExecutionPlan(connectionId, sql,
                result -> Platform.runLater(() -> {
                    setLoading(false, null);
                    if (result.isSuccess()) {
                        ExecutionPlanView planView = new ExecutionPlanView(result.getData(), connectionId, sql);
                        executionPlanTab.setContent(planView);
                        analysisTabPane.getSelectionModel().select(executionPlanTab);
                        statusLabel.setText("执行计划加载完成");
                    } else {
                        showError("获取执行计划失败", result.getMessage());
                        statusLabel.setText("获取失败: " + result.getMessage());
                    }
                })
        );
    }

    private void generateIndexSuggestions() {
        String sql = getCurrentSql();
        if (sql.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先输入或选择SQL语句");
            return;
        }

        setLoading(true, "正在生成索引建议...");

        performanceAnalyzerService.generateIndexSuggestionsAsync(connectionId, sql,
                result -> Platform.runLater(() -> {
                    setLoading(false, null);
                    if (result.isSuccess()) {
                        updateIndexSuggestionsView(result.getData());
                        analysisTabPane.getSelectionModel().select(indexSuggestionsTab);
                        statusLabel.setText("索引建议生成完成，共 " + result.getData().size() + " 条");
                    } else {
                        showError("生成索引建议失败", result.getMessage());
                        statusLabel.setText("生成失败: " + result.getMessage());
                    }
                })
        );
    }

    private void generateOptimizationTips(ExecutionPlan plan, String sql) {
        Result<List<String>> tipsResult = performanceAnalyzerService.generateOptimizationTips(plan, sql);
        if (tipsResult.isSuccess()) {
            StringBuilder sb = new StringBuilder();
            for (String tip : tipsResult.getData()) {
                sb.append(tip).append("\n\n");
            }
            TextArea tipsTextArea = (TextArea) analysisTabPane.getTabs().get(3).getContent().lookup("#tipsTextArea");
            if (tipsTextArea != null) {
                tipsTextArea.setText(sb.toString());
            }
        }
    }

    private void updateSlowQueryStats(List<SlowQueryInfo> queries) {
        if (queries == null || queries.isEmpty()) return;

        BorderPane statsPanel = (BorderPane) slowQueriesTab.getContent();
        VBox statsBox = (VBox) ((VBox) statsPanel.getCenter()).getChildren().get(0);

        long totalDuration = 0;
        long maxDuration = 0;
        int selectCount = 0;
        int dmlCount = 0;

        for (SlowQueryInfo q : queries) {
            totalDuration += q.getExecutionTimeMs();
            maxDuration = Math.max(maxDuration, q.getExecutionTimeMs());
            if ("SELECT".equals(q.getQueryType())) {
                selectCount++;
            } else if ("INSERT".equals(q.getQueryType()) || "UPDATE".equals(q.getQueryType()) || "DELETE".equals(q.getQueryType())) {
                dmlCount++;
            }
        }

        ((Label) statsBox.lookup("#totalLabel")).setText("总慢查询数: " + queries.size());
        ((Label) statsBox.lookup("#avgDurationLabel")).setText(String.format("平均耗时: %.2fms", (double) totalDuration / queries.size()));
        ((Label) statsBox.lookup("#maxDurationLabel")).setText("最大耗时: " + formatDuration(maxDuration));
        ((Label) statsBox.lookup("#selectCountLabel")).setText("SELECT查询: " + selectCount);
        ((Label) statsBox.lookup("#dmlCountLabel")).setText("DML操作: " + dmlCount);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 慢查询分析建议 ===\n\n");
        if (queries.size() > 20) {
            sb.append("⚠️ 慢查询数量较多，建议开启慢查询日志监控\n\n");
        }
        if (maxDuration > 10000) {
            sb.append("⚠️ 存在超10秒的慢查询，建议优先优化\n\n");
        }
        if (dmlCount > 0) {
            sb.append("💡 DML操作较慢，考虑批量操作或优化索引\n\n");
        }
        sb.append("💡 建议：\n");
        sb.append("  1. 检查高频查询是否缺少合适的索引\n");
        sb.append("  2. 避免SELECT *，只查询需要的列\n");
        sb.append("  3. 大表查询考虑分页\n");
        sb.append("  4. 复杂查询考虑拆分为多个简单查询\n");

        tipsArea.setText(sb.toString());
    }

    private void updateExecutionPlanView(ExecutionPlan plan) {
        ExecutionPlanView planView = new ExecutionPlanView(plan, connectionId, getCurrentSql());
        executionPlanTab.setContent(planView);
    }

    private void updateIndexSuggestionsView(List<IndexSuggestion> suggestions) {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        if (suggestions.isEmpty()) {
            content.getChildren().add(new Label("✅ 未发现需要添加索引的建议"));
            indexSuggestionsTab.setContent(content);
            return;
        }

        Label title = new Label("索引建议 (" + suggestions.size() + " 条)");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        content.getChildren().add(title);

        for (IndexSuggestion suggestion : suggestions) {
            VBox itemBox = new VBox(5);
            itemBox.setPadding(new Insets(10));
            itemBox.setStyle("-fx-border-color: #e0e0e0; -fx-border-radius: 5; -fx-background-color: #fafafa;");

            HBox header = new HBox(10);
            Label tableLabel = new Label("表: " + suggestion.getTableName());
            tableLabel.setStyle("-fx-font-weight: bold;");
            Label priorityLabel = new Label("优先级: " + suggestion.getPriority());
            priorityLabel.setStyle("-fx-text-fill: " +
                    (suggestion.getPriority() >= 8 ? "red" : suggestion.getPriority() >= 5 ? "orange" : "green") + ";");
            header.getChildren().addAll(tableLabel, priorityLabel);

            Label colsLabel = new Label("列: " + String.join(", ", suggestion.getColumns()));
            Label descLabel = new Label("说明: " + suggestion.getDescription());

            TextArea ddlArea = new TextArea(suggestion.getCreateIndexDDL());
            ddlArea.setEditable(false);
            ddlArea.setPrefRowCount(2);
            ddlArea.setStyle("-fx-font-family: monospace;");

            Button copyBtn = new Button("复制DDL");
            copyBtn.setOnAction(e -> {
                javafx.scene.input.Clipboard clipboard =
                        javafx.scene.input.Clipboard.getSystemClipboard();
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(suggestion.getCreateIndexDDL());
                clipboard.setContent(cc);
                statusLabel.setText("DDL已复制到剪贴板");
            });

            itemBox.getChildren().addAll(header, colsLabel, descLabel, ddlArea, copyBtn);
            content.getChildren().add(itemBox);
        }

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        indexSuggestionsTab.setContent(scrollPane);
    }

    private String getCurrentSql() {
        String sql = sqlCodeArea.getText().trim();
        if (sql.isEmpty()) {
            sql = sqlInputField.getText().trim();
        }
        return sql;
    }

    private String truncate(String str, int maxLen) {
        if (str == null) return "-";
        str = str.replaceAll("\\s+", " ").trim();
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.2fs", ms / 1000.0);
        return String.format("%dm%ds", ms / 60000, (ms % 60000) / 1000);
    }

    private void setLoading(boolean loading, String status) {
        Platform.runLater(() -> {
            analyzeBtn.setDisable(loading);
            loadSlowQueriesBtn.setDisable(loading);
            if (status != null) {
                statusLabel.setText(status);
            }
            ProgressIndicator progress = (ProgressIndicator) lookup("#progressIndicator");
            if (progress != null) {
                progress.setVisible(loading);
            }
        });
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.initOwner(getScene() != null ? getScene().getWindow() : null);
            alert.show();
        });
    }

    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.initOwner(getScene() != null ? getScene().getWindow() : null);
            alert.show();
        });
    }
}
