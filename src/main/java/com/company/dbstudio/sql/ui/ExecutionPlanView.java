package com.company.dbstudio.sql.ui;

import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.sql.model.ExecutionPlan;
import com.company.dbstudio.sql.model.IndexSuggestion;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExecutionPlanView extends BorderPane {
    private final TreeView<ExecutionPlan> planTree;
    private final TableView<Map<String, String>> statsTable;
    private final ListView<IndexSuggestion> suggestionsList;
    private final TextArea planTextArea;
    private final TabPane tabPane;
    private ExecutionPlan currentPlan;

    public ExecutionPlanView() {
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        planTree = createPlanTree();
        statsTable = createStatsTable();
        suggestionsList = createSuggestionsList();
        planTextArea = createPlanTextArea();

        Tab treeTab = new Tab("树状视图", planTree);
        Tab statsTab = new Tab("统计信息", statsTable);
        Tab suggestionsTab = new Tab("索引建议", suggestionsList);
        Tab textTab = new Tab("文本视图", planTextArea);

        tabPane.getTabs().addAll(treeTab, statsTab, suggestionsTab, textTab);
        setCenter(tabPane);

        setTop(createToolbar());

        getStyleClass().add("execution-plan-view");
        setPrefSize(800, 400);
    }

    private ToolBar createToolbar() {
        ToolBar toolBar = new ToolBar();
        
        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> refreshPlan());
        
        Button exportBtn = new Button("导出");
        exportBtn.setOnAction(e -> exportPlan());
        
        Button expandBtn = new Button("全部展开");
        expandBtn.setOnAction(e -> expandAll());
        
        Button collapseBtn = new Button("全部折叠");
        collapseBtn.setOnAction(e -> collapseAll());
        
        toolBar.getItems().addAll(refreshBtn, exportBtn, new Separator(), expandBtn, collapseBtn);
        
        return toolBar;
    }

    private TreeView<ExecutionPlan> createPlanTree() {
        TreeView<ExecutionPlan> treeView = new TreeView<>();
        treeView.getStyleClass().add("plan-tree");
        treeView.setShowRoot(true);
        treeView.setCellFactory(param -> new TreeCell<ExecutionPlan>() {
            @Override
            protected void updateItem(ExecutionPlan item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(formatPlanNode(item));
                    setGraphic(getNodeIcon(item));
                    setTooltip(new Tooltip(getNodeTooltip(item)));
                    
                    if (item.isFullTableScan()) {
                        setStyle("-fx-text-fill: #ff6b6b;");
                    } else if (item.isIndexScan()) {
                        setStyle("-fx-text-fill: #ffd43b;");
                    } else if (item.isSortOperation()) {
                        setStyle("-fx-text-fill: #ffa94d;");
                    } else {
                        setStyle("-fx-text-fill: #2b8a3e;");
                    }
                }
            }
        });
        
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                showNodeDetails(newVal.getValue());
            }
        });
        
        return treeView;
    }

    private TableView<Map<String, String>> createStatsTable() {
        TableView<Map<String, String>> tableView = new TableView<>();
        tableView.getStyleClass().add("plan-stats-table");
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_SUBSEQUENT);
        
        TableColumn<Map<String, String>, String> operationCol = new TableColumn<>("操作");
        operationCol.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get("operation")));
        
        TableColumn<Map<String, String>, String> objectCol = new TableColumn<>("对象");
        objectCol.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get("object")));
        
        TableColumn<Map<String, String>, String> rowsCol = new TableColumn<>("行数");
        rowsCol.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get("rows")));
        
        TableColumn<Map<String, String>, String> costCol = new TableColumn<>("成本");
        costCol.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get("cost")));
        
        TableColumn<Map<String, String>, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get("type")));
        
        TableColumn<Map<String, String>, String> fullScanCol = new TableColumn<>("全表扫描");
        fullScanCol.setCellValueFactory(param -> {
            String value = param.getValue().get("isFullScan");
            String display = "true".equals(value) ? "⚠️ 是" : "否";
            return new SimpleStringProperty(display);
        });
        
        tableView.getColumns().addAll(operationCol, objectCol, rowsCol, costCol, typeCol, fullScanCol);
        
        return tableView;
    }

    private ListView<IndexSuggestion> createSuggestionsList() {
        ListView<IndexSuggestion> listView = new ListView<>();
        listView.getStyleClass().add("suggestions-list");
        listView.setCellFactory(param -> new ListCell<IndexSuggestion>() {
            @Override
            protected void updateItem(IndexSuggestion item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    VBox content = new VBox(5);
                    content.setPadding(new Insets(10, 10, 10, 10));
                    
                    Label titleLabel = new Label("💡 " + item.getExplanation());
                    titleLabel.setWrapText(true);
                    titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #495057;");
                    
                    TextArea ddlArea = new TextArea(item.getDdl());
                    ddlArea.setEditable(false);
                    ddlArea.setPrefRowCount(2);
                    ddlArea.setStyle("-fx-font-family: 'Monaco'; -fx-font-size: 11px;");
                    
                    Button applyBtn = new Button("应用");
                    applyBtn.setOnAction(e -> applySuggestion(item));
                    
                    Button copyBtn = new Button("复制DDL");
                    copyBtn.setOnAction(e -> copyToClipboard(item.getDdl()));
                    
                    HBox buttonBox = new HBox(10, applyBtn, copyBtn);
                    
                    content.getChildren().addAll(titleLabel, ddlArea, buttonBox);
                    setGraphic(content);
                }
            }
        });
        
        return listView;
    }

    private TextArea createPlanTextArea() {
        TextArea textArea = new TextArea();
        textArea.getStyleClass().add("plan-text-area");
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setStyle("-fx-font-family: 'Monaco'; -fx-font-size: 12px;");
        return textArea;
    }

    private String formatPlanNode(ExecutionPlan node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getOperation());
        if (node.getObjectName() != null && !node.getObjectName().isEmpty()) {
            sb.append(" [").append(node.getObjectName()).append("]");
        }
        sb.append(" (rows=").append(node.getRows());
        sb.append(", cost=").append(String.format("%.2f", node.getCost())).append(")");
        return sb.toString();
    }

    private javafx.scene.Node getNodeIcon(ExecutionPlan node) {
        String type = node.getOperationType();
        String color = switch (type) {
            case "SELECT" -> "#228be6";
            case "INSERT" -> "#51cf66";
            case "UPDATE" -> "#fab005";
            case "DELETE" -> "#ff6b6b";
            case "JOIN" -> "#845ef7";
            case "SORT" -> "#fd7e14";
            case "GROUP", "AGGREGATE" -> "#15aabf";
            default -> "#adb5bd";
        };
        
        Rectangle icon = new Rectangle(12, 12);
        icon.setFill(Color.web(color));
        icon.setArcWidth(3);
        icon.setArcHeight(3);
        return icon;
    }

    private String getNodeTooltip(ExecutionPlan node) {
        StringBuilder sb = new StringBuilder();
        sb.append("操作: ").append(node.getOperation()).append("\n");
        if (node.getObjectName() != null) {
            sb.append("对象: ").append(node.getObjectName()).append("\n");
        }
        if (node.getObjectType() != null) {
            sb.append("类型: ").append(node.getObjectType()).append("\n");
        }
        sb.append("预估行数: ").append(node.getRows()).append("\n");
        sb.append("预估字节: ").append(StringUtils.formatBytes(node.getBytes())).append("\n");
        sb.append("成本: ").append(String.format("%.2f", node.getCost())).append("\n");
        if (node.getPredicate() != null) {
            sb.append("谓词: ").append(node.getPredicate()).append("\n");
        }
        if (node.getAccess() != null) {
            sb.append("访问条件: ").append(node.getAccess()).append("\n");
        }
        if (node.getFilter() != null) {
            sb.append("过滤条件: ").append(node.getFilter()).append("\n");
        }
        return sb.toString();
    }

    private void showNodeDetails(ExecutionPlan node) {
        // 可以在详情面板显示节点详情
    }

    public void setPlan(ExecutionPlan plan) {
        this.currentPlan = plan;
        
        if (plan != null) {
            TreeItem<ExecutionPlan> rootItem = buildTree(plan);
            planTree.setRoot(rootItem);
            rootItem.setExpanded(true);
            expandAll();
            
            List<Map<String, String>> stats = new com.company.dbstudio.sql.service.ExecutionPlanParser()
                    .getPlanStats(plan);
            statsTable.setItems(FXCollections.observableArrayList(stats));
            
            planTextArea.setText(new com.company.dbstudio.sql.service.ExecutionPlanParser()
                    .formatPlanAsText(plan));
        }
    }

    public void setSuggestions(List<IndexSuggestion> suggestions) {
        suggestionsList.setItems(FXCollections.observableArrayList(suggestions));
        Tab suggestionsTab = tabPane.getTabs().get(2);
        suggestionsTab.setText("索引建议 (" + suggestions.size() + ")");
    }

    private TreeItem<ExecutionPlan> buildTree(ExecutionPlan plan) {
        TreeItem<ExecutionPlan> item = new TreeItem<>(plan);
        for (ExecutionPlan child : plan.getChildren()) {
            item.getChildren().add(buildTree(child));
        }
        return item;
    }

    private void expandAll() {
        if (planTree.getRoot() != null) {
            expandAll(planTree.getRoot());
        }
    }

    private void expandAll(TreeItem<ExecutionPlan> item) {
        item.setExpanded(true);
        for (TreeItem<ExecutionPlan> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private void collapseAll() {
        if (planTree.getRoot() != null) {
            collapseAll(planTree.getRoot());
        }
    }

    private void collapseAll(TreeItem<ExecutionPlan> item) {
        item.setExpanded(false);
        for (TreeItem<ExecutionPlan> child : item.getChildren()) {
            collapseAll(child);
        }
    }

    private void refreshPlan() {
        fireEvent(new ExecutionPlanEvent(ExecutionPlanEvent.REFRESH_PLAN, currentPlan));
    }

    private void exportPlan() {
        fireEvent(new ExecutionPlanEvent(ExecutionPlanEvent.EXPORT_PLAN, currentPlan));
    }

    private void applySuggestion(IndexSuggestion suggestion) {
        fireEvent(new ExecutionPlanEvent(ExecutionPlanEvent.APPLY_SUGGESTION, suggestion));
    }

    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
    }

    public static class ExecutionPlanEvent extends javafx.event.Event {
        public static final javafx.event.EventType<ExecutionPlanEvent> REFRESH_PLAN = 
                new javafx.event.EventType<>(ANY, "REFRESH_PLAN");
        public static final javafx.event.EventType<ExecutionPlanEvent> EXPORT_PLAN = 
                new javafx.event.EventType<>(ANY, "EXPORT_PLAN");
        public static final javafx.event.EventType<ExecutionPlanEvent> APPLY_SUGGESTION = 
                new javafx.event.EventType<>(ANY, "APPLY_SUGGESTION");
        
        private final Object data;

        public ExecutionPlanEvent(javafx.event.EventType<ExecutionPlanEvent> eventType, Object data) {
            super(eventType);
            this.data = data;
        }

        public Object getData() {
            return data;
        }
    }
}
