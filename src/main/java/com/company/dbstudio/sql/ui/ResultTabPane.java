package com.company.dbstudio.sql.ui;

import com.company.dbstudio.core.util.DateUtils;
import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.sql.model.QueryResult;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.time.LocalDateTime;
import java.util.List;

public class ResultTabPane extends TabPane {
    private int tabCounter = 0;

    public ResultTabPane() {
        setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        getStyleClass().add("result-tab-pane");
        setPrefHeight(300);
    }

    public Tab addResultTab(QueryResult result) {
        tabCounter++;
        String tabTitle = generateTabTitle(result);
        Tab tab = new Tab(tabTitle);
        tab.setClosable(true);
        
        BorderPane content = createResultContent(result);
        tab.setContent(content);
        
        getTabs().add(tab);
        getSelectionModel().select(tab);
        
        return tab;
    }

    private String generateTabTitle(QueryResult result) {
        String type = result.getQueryType() != null ? result.getQueryType() : "Query";
        String time = DateUtils.formatNow();
        return String.format("%s #%d (%s)", type, tabCounter, time);
    }

    private BorderPane createResultContent(QueryResult result) {
        BorderPane borderPane = new BorderPane();
        
        ToolBar toolBar = createToolBar(result);
        borderPane.setTop(toolBar);
        
        if (result.getHasError()) {
            TextArea errorArea = createErrorArea(result.getErrorMessage());
            borderPane.setCenter(errorArea);
        } else if (result.isResultSet()) {
            TableView<ObservableList<Object>> tableView = createTableView(result);
            borderPane.setCenter(tableView);
        } else {
            TextArea infoArea = createInfoArea(result);
            borderPane.setCenter(infoArea);
        }
        
        Label statusBar = createStatusBar(result);
        borderPane.setBottom(statusBar);
        
        return borderPane;
    }

    private ToolBar createToolBar(QueryResult result) {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("result-toolbar");
        
        Button exportBtn = new Button("导出");
        exportBtn.setOnAction(e -> exportResult(result));
        
        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> copyToClipboard(result));
        
        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshResult());
        
        Button maximizeBtn = new Button("最大化");
        maximizeBtn.setOnAction(e -> toggleMaximize());
        
        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> closeCurrentTab());
        
        toolBar.getItems().addAll(exportBtn, copyBtn, refreshBtn, new Separator(), maximizeBtn, closeBtn);
        
        return toolBar;
    }

    private TableView<ObservableList<Object>> createTableView(QueryResult result) {
        TableView<ObservableList<Object>> tableView = new TableView<>();
        tableView.getStyleClass().add("result-table");
        tableView.setEditable(false);
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT);
        
        List<QueryResult.ColumnInfo> columns = result.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            QueryResult.ColumnInfo colInfo = columns.get(i);
            
            TableColumn<ObservableList<Object>, Object> column = new TableColumn<>(colInfo.getLabel());
            column.setCellValueFactory(param -> {
                ObservableList<Object> row = param.getValue();
                if (colIndex < row.size()) {
                    return new SimpleObjectProperty<>(row.get(colIndex));
                }
                return new SimpleObjectProperty<>(null);
            });
            
            String headerText = String.format("%s\n%s", colInfo.getLabel(), colInfo.getFullTypeName());
            column.setText(headerText);
            column.setTooltip(new Tooltip(colInfo.getFullTypeName() + (colInfo.isNullable() ? " (nullable)" : "")));
            column.setMinWidth(80);
            column.setPrefWidth(150);
            
            tableView.getColumns().add(column);
        }
        
        tableView.setItems(result.getData());
        
        return tableView;
    }

    private TextArea createErrorArea(String errorMessage) {
        TextArea textArea = new TextArea();
        textArea.getStyleClass().add("error-text-area");
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setText("❌ 执行错误:\n\n" + errorMessage);
        textArea.setStyle("-fx-text-fill: #ff6b6b; -fx-font-family: 'Monaco'; -fx-font-size: 12px;");
        return textArea;
    }

    private TextArea createInfoArea(QueryResult result) {
        TextArea textArea = new TextArea();
        textArea.getStyleClass().add("info-text-area");
        textArea.setEditable(false);
        textArea.setWrapText(true);
        
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 执行成功\n\n");
        sb.append("查询类型: ").append(result.getQueryType()).append("\n");
        sb.append("影响行数: ").append(result.getAffectedRows()).append("\n");
        sb.append("执行时间: ").append(StringUtils.formatDuration(result.getExecutionTime()))
          .append("\n");
        if (result.getExecutionPlan() != null) {
            sb.append("\n执行计划:\n").append(result.getExecutionPlan().toTreeString());
        }
        
        textArea.setText(sb.toString());
        textArea.setStyle("-fx-text-fill: #51cf66; -fx-font-family: 'Monaco'; -fx-font-size: 12px;");
        return textArea;
    }

    private Label createStatusBar(QueryResult result) {
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-bar");
        statusLabel.setPadding(new Insets(5, 10, 5, 10));
        
        StringBuilder sb = new StringBuilder();
        if (result.getHasError()) {
            sb.append("❌ 执行失败");
        } else {
            sb.append("✅ 执行成功");
        }
        
        if (result.getExecutionTime() != null) {
            sb.append(" | 耗时: ").append(StringUtils.formatDuration(result.getExecutionTime()));
        }
        if (result.getRowCount() != null && result.getRowCount() > 0) {
            sb.append(" | 行数: ").append(result.getRowCount());
        }
        if (result.getAffectedRows() != null && result.getAffectedRows() > 0) {
            sb.append(" | 影响: ").append(result.getAffectedRows());
        }
        if (result.getExecutionPlan() != null) {
            sb.append(" | 成本: ").append(String.format("%.2f", result.getExecutionPlan().getTotalCost()));
        }
        sb.append(" | 时间: ").append(DateUtils.formatNow());
        
        statusLabel.setText(sb.toString());
        return statusLabel;
    }

    private void exportResult(QueryResult result) {
        fireEvent(new ResultTabEvent(ResultTabEvent.EXPORT_RESULT, result));
    }

    private void copyToClipboard(QueryResult result) {
        fireEvent(new ResultTabEvent(ResultTabEvent.COPY_RESULT, result));
    }

    private void refreshResult() {
        fireEvent(new ResultTabEvent(ResultTabEvent.REFRESH_RESULT, null));
    }

    private void toggleMaximize() {
        fireEvent(new ResultTabEvent(ResultTabEvent.TOGGLE_MAXIMIZE, null));
    }

    private void closeCurrentTab() {
        Tab selectedTab = getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            getTabs().remove(selectedTab);
        }
    }

    public void closeAllTabs() {
        getTabs().clear();
        tabCounter = 0;
    }

    public void closeOtherTabs() {
        Tab selectedTab = getSelectionModel().getSelectedItem();
        if (selectedTab != null) {
            getTabs().retainAll(selectedTab);
        }
    }

    public static class ResultTabEvent extends javafx.event.Event {
        public static final javafx.event.EventType<ResultTabEvent> EXPORT_RESULT = 
                new javafx.event.EventType<>(ANY, "EXPORT_RESULT");
        public static final javafx.event.EventType<ResultTabEvent> COPY_RESULT = 
                new javafx.event.EventType<>(ANY, "COPY_RESULT");
        public static final javafx.event.EventType<ResultTabEvent> REFRESH_RESULT = 
                new javafx.event.EventType<>(ANY, "REFRESH_RESULT");
        public static final javafx.event.EventType<ResultTabEvent> TOGGLE_MAXIMIZE = 
                new javafx.event.EventType<>(ANY, "TOGGLE_MAXIMIZE");
        
        private final QueryResult result;

        public ResultTabEvent(javafx.event.EventType<ResultTabEvent> eventType, QueryResult result) {
            super(eventType);
            this.result = result;
        }

        public QueryResult getResult() {
            return result;
        }
    }
}
