package com.company.dbstudio.sql.ui;

import com.company.dbstudio.connection.QueryHistoryManager;
import com.company.dbstudio.connection.model.QueryHistory;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.util.DateUtils;
import com.company.dbstudio.core.util.StringUtils;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

public class QueryHistoryView extends BorderPane {
    private final QueryHistoryManager historyManager;
    private final TableView<QueryHistory> historyTable;
    private final TextField searchField;
    private final ComboBox<String> typeFilter;
    private final ComboBox<String> connectionFilter;
    private final CheckBox successOnlyCheck;
    private final Spinner<Integer> minTimeSpinner;
    private final FilteredList<QueryHistory> filteredHistory;
    private Consumer<QueryHistory> onSelectHandler;
    private Consumer<QueryHistory> onExecuteHandler;

    public QueryHistoryView() {
        this.historyManager = ApplicationContext.getBean(QueryHistoryManager.class);
        
        List<QueryHistory> historyList = historyManager.getAllHistory();
        filteredHistory = new FilteredList<>(FXCollections.observableArrayList(historyList));
        
        historyTable = createHistoryTable();
        searchField = createSearchField();
        typeFilter = createTypeFilter();
        connectionFilter = createConnectionFilter();
        successOnlyCheck = new CheckBox("仅成功");
        minTimeSpinner = createMinTimeSpinner();
        
        setTop(createToolbar());
        setCenter(historyTable);
        setBottom(createDetailPanel());
        
        setupFilterListeners();
        
        getStyleClass().add("query-history-view");
        setPrefSize(600, 400);
    }

    private ToolBar createToolbar() {
        ToolBar toolBar = new ToolBar();
        
        Label searchLabel = new Label("搜索:");
        searchLabel.setPadding(new Insets(0, 5, 0, 10));
        
        Label typeLabel = new Label("类型:");
        typeLabel.setPadding(new Insets(0, 5, 0, 10));
        
        Label connLabel = new Label("连接:");
        connLabel.setPadding(new Insets(0, 5, 0, 10));
        
        Label minTimeLabel = new Label("耗时≥(ms):");
        minTimeLabel.setPadding(new Insets(0, 5, 0, 10));
        
        Button clearBtn = new Button("清除筛选");
        clearBtn.setOnAction(e -> clearFilters());
        
        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshHistory());
        
        Button deleteBtn = new Button("删除选中");
        deleteBtn.setOnAction(e -> deleteSelected());
        
        Button clearAllBtn = new Button("清空全部");
        clearAllBtn.setOnAction(e -> clearAllHistory());
        
        toolBar.getItems().addAll(
                searchLabel, searchField,
                new Separator(),
                typeLabel, typeFilter,
                new Separator(),
                connLabel, connectionFilter,
                new Separator(),
                minTimeLabel, minTimeSpinner,
                new Separator(),
                successOnlyCheck,
                new Separator(),
                clearBtn, refreshBtn, deleteBtn, clearAllBtn
        );
        
        return toolBar;
    }

    private TextField createSearchField() {
        TextField field = new TextField();
        field.setPromptText("搜索SQL语句...");
        field.setPrefWidth(200);
        return field;
    }

    private ComboBox<String> createTypeFilter() {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().add("全部");
        combo.getItems().addAll("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "EXPLAIN", "SHOW", "OTHER");
        combo.getSelectionModel().select(0);
        combo.setPrefWidth(100);
        return combo;
    }

    private ComboBox<String> createConnectionFilter() {
        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().add("全部");
        combo.getItems().addAll(historyManager.getConnectionIds());
        combo.getSelectionModel().select(0);
        combo.setPrefWidth(150);
        return combo;
    }

    private Spinner<Integer> createMinTimeSpinner() {
        Spinner<Integer> spinner = new Spinner<>(0, Integer.MAX_VALUE, 0, 100);
        spinner.setPrefWidth(80);
        spinner.setEditable(true);
        return spinner;
    }

    private TableView<QueryHistory> createHistoryTable() {
        TableView<QueryHistory> tableView = new TableView<>();
        tableView.getStyleClass().add("history-table");
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT);
        tableView.setItems(filteredHistory);
        
        TableColumn<QueryHistory, String> statusCol = new TableColumn<>("状态");
        statusCol.setCellValueFactory(param -> {
            String status = param.getValue().isSuccess() ? "✅" : "❌";
            return new SimpleStringProperty(status);
        });
        statusCol.setPrefWidth(50);
        statusCol.setMinWidth(50);
        statusCol.setMaxWidth(50);
        
        TableColumn<QueryHistory, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(param -> {
            String type = param.getValue().getQueryType();
            return new SimpleStringProperty(type != null ? type : "OTHER");
        });
        typeCol.setPrefWidth(80);
        
        TableColumn<QueryHistory, String> sqlCol = new TableColumn<>("SQL语句");
        sqlCol.setCellValueFactory(param -> {
            String sql = param.getValue().getSql();
            if (sql != null && sql.length() > 100) {
                sql = sql.substring(0, 100) + "...";
            }
            return new SimpleStringProperty(sql != null ? sql : "");
        });
        sqlCol.setPrefWidth(300);
        
        TableColumn<QueryHistory, Long> timeCol = new TableColumn<>("耗时(ms)");
        timeCol.setCellValueFactory(param -> {
            Long time = param.getValue().getExecutionTime();
            return new SimpleObjectProperty<>(time != null ? time : 0L);
        });
        timeCol.setPrefWidth(100);
        
        TableColumn<QueryHistory, Integer> rowsCol = new TableColumn<>("行数");
        rowsCol.setCellValueFactory(param -> {
            Integer rows = param.getValue().getRowCount();
            return new SimpleObjectProperty<>(rows != null ? rows : 0);
        });
        rowsCol.setPrefWidth(80);
        
        TableColumn<QueryHistory, Long> affectedCol = new TableColumn<>("影响");
        affectedCol.setCellValueFactory(param -> {
            Long affected = param.getValue().getAffectedRows();
            return new SimpleObjectProperty<>(affected != null ? affected : 0L);
        });
        affectedCol.setPrefWidth(80);
        
        TableColumn<QueryHistory, String> dateCol = new TableColumn<>("时间");
        dateCol.setCellValueFactory(param -> {
            LocalDateTime date = param.getValue().getCreatedAt();
            return new SimpleStringProperty(date != null ? DateUtils.formatDateTime(date) : "");
        });
        dateCol.setPrefWidth(150);
        
        TableColumn<QueryHistory, String> connCol = new TableColumn<>("连接");
        connCol.setCellValueFactory(param -> {
            String conn = param.getValue().getConnectionId();
            return new SimpleStringProperty(conn != null ? conn.substring(0, Math.min(8, conn.length())) + "..." : "");
        });
        connCol.setPrefWidth(100);
        
        tableView.getColumns().addAll(statusCol, typeCol, sqlCol, timeCol, rowsCol, affectedCol, dateCol, connCol);
        
        tableView.setRowFactory(tv -> {
            TableRow<QueryHistory> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            
            MenuItem loadItem = new MenuItem("加载到编辑器");
            loadItem.setOnAction(e -> {
                if (!row.isEmpty() && onSelectHandler != null) {
                    onSelectHandler.accept(row.getItem());
                }
            });
            
            MenuItem executeItem = new MenuItem("执行");
            executeItem.setOnAction(e -> {
                if (!row.isEmpty() && onExecuteHandler != null) {
                    onExecuteHandler.accept(row.getItem());
                }
            });
            
            MenuItem copyItem = new MenuItem("复制SQL");
            copyItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    copyToClipboard(row.getItem().getSql());
                }
            });
            
            MenuItem deleteItem = new MenuItem("删除");
            deleteItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    historyManager.removeHistory(row.getItem().getId());
                    refreshHistory();
                }
            });
            
            contextMenu.getItems().addAll(loadItem, executeItem, copyItem, new SeparatorMenuItem(), deleteItem);
            row.setContextMenu(contextMenu);
            
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && onSelectHandler != null) {
                    onSelectHandler.accept(row.getItem());
                }
            });
            
            return row;
        });
        
        return tableView;
    }

    private BorderPane createDetailPanel() {
        BorderPane detailPanel = new BorderPane();
        detailPanel.getStyleClass().add("detail-panel");
        detailPanel.setPrefHeight(150);
        
        TextArea detailArea = new TextArea();
        detailArea.setEditable(false);
        detailArea.setWrapText(true);
        detailArea.setStyle("-fx-font-family: 'Monaco'; -fx-font-size: 11px;");
        
        historyTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                StringBuilder sb = new StringBuilder();
                sb.append("完整SQL:\n").append(newVal.getSql()).append("\n\n");
                if (newVal.getErrorMessage() != null) {
                    sb.append("错误信息:\n").append(newVal.getErrorMessage()).append("\n\n");
                }
                if (newVal.getExecutionPlan() != null) {
                    sb.append("执行计划:\n").append(newVal.getExecutionPlan()).append("\n");
                }
                detailArea.setText(sb.toString());
            } else {
                detailArea.setText("");
            }
        });
        
        detailPanel.setCenter(detailArea);
        
        Label titleLabel = new Label("  详情:");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-padding: 5px;");
        detailPanel.setTop(titleLabel);
        
        return detailPanel;
    }

    private void setupFilterListeners() {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());
        typeFilter.valueProperty().addListener((obs, oldVal, newVal) -> applyFilters());
        connectionFilter.valueProperty().addListener((obs, oldVal, newVal) -> applyFilters());
        successOnlyCheck.selectedProperty().addListener((obs, oldVal, newVal) -> applyFilters());
        minTimeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> applyFilters());
    }

    private void applyFilters() {
        String searchText = searchField.getText().toLowerCase();
        String type = typeFilter.getValue();
        String connection = connectionFilter.getValue();
        boolean successOnly = successOnlyCheck.isSelected();
        int minTime = minTimeSpinner.getValue();
        
        filteredHistory.setPredicate(history -> {
            if (searchText != null && !searchText.isEmpty()) {
                String sql = history.getSql();
                if (sql == null || !sql.toLowerCase().contains(searchText)) {
                    return false;
                }
            }
            
            if (!"全部".equals(type) && type != null) {
                String queryType = history.getQueryType();
                if (queryType == null || !type.equals(queryType)) {
                    return false;
                }
            }
            
            if (!"全部".equals(connection) && connection != null) {
                String connId = history.getConnectionId();
                if (connId == null || !connId.equals(connection)) {
                    return false;
                }
            }
            
            if (successOnly && !history.isSuccess()) {
                return false;
            }
            
            if (minTime > 0) {
                Long execTime = history.getExecutionTime();
                if (execTime == null || execTime < minTime) {
                    return false;
                }
            }
            
            return true;
        });
    }

    private void clearFilters() {
        searchField.clear();
        typeFilter.getSelectionModel().select(0);
        connectionFilter.getSelectionModel().select(0);
        successOnlyCheck.setSelected(false);
        minTimeSpinner.getValueFactory().setValue(0);
        applyFilters();
    }

    private void refreshHistory() {
        List<QueryHistory> historyList = historyManager.getAllHistory();
        filteredHistory.getSource().setAll(historyList);
        connectionFilter.getItems().set(1, historyManager.getConnectionIds());
    }

    private void deleteSelected() {
        QueryHistory selected = historyTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            historyManager.removeHistory(selected.getId());
            refreshHistory();
        }
    }

    private void clearAllHistory() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认清空");
        alert.setHeaderText("确定要清空所有查询历史吗？");
        alert.setContentText("此操作不可恢复。");
        
        alert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                historyManager.clearHistory();
                refreshHistory();
            }
        });
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
    }

    public void setOnSelectHandler(Consumer<QueryHistory> handler) {
        this.onSelectHandler = handler;
    }

    public void setOnExecuteHandler(Consumer<QueryHistory> handler) {
        this.onExecuteHandler = handler;
    }

    public void addHistory(QueryHistory history) {
        filteredHistory.getSource().add(0, history);
    }
}
