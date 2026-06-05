package com.company.dbstudio.schema.ui;

import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.schema.model.SchemaObject;
import com.company.dbstudio.schema.model.SchemaObject.ObjectType;
import com.company.dbstudio.schema.service.SchemaService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SchemaBrowserView extends BorderPane {

    private final SchemaService schemaService;
    private final String connectionId;

    private TreeView<SchemaObject> schemaTree;
    private TextField searchField;
    private TabPane detailTabPane;
    private CodeArea ddlArea;
    private CodeArea compareArea1;
    private CodeArea compareArea2;
    private TextArea diffArea;
    private Label statusLabel;
    private SchemaObject selectedObject;
    private SchemaObject compareObject;

    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile(
            "\\b(CREATE|TABLE|VIEW|PROCEDURE|FUNCTION|TRIGGER|INDEX|PRIMARY|FOREIGN|KEY|UNIQUE|" +
                    "VARCHAR|INT|INTEGER|BIGINT|DECIMAL|FLOAT|DOUBLE|DATE|TIME|TIMESTAMP|BOOLEAN|" +
                    "TEXT|CLOB|BLOB|NOT|NULL|DEFAULT|AUTO_INCREMENT|IDENTITY|SERIAL|REFERENCES|" +
                    "ON|DELETE|UPDATE|CASCADE|SET|NULL|RESTRICT|NO|ACTION|COMMENT|CONSTRAINT|" +
                    "ALTER|DROP|INSERT|UPDATE|DELETE|SELECT|FROM|WHERE|AND|OR|NOT|IN|LIKE|IS|" +
                    "BEGIN|END|DECLARE|RETURN|IF|ELSE|WHILE|FOR|EXEC|EXECUTE|CALL)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SQL_STRING_PATTERN = Pattern.compile("'[^']*'");
    private static final Pattern SQL_COMMENT_PATTERN = Pattern.compile("--.*$|/\\*.*?\\*/", Pattern.MULTILINE);
    private static final Pattern SQL_NUMBER_PATTERN = Pattern.compile("\\b\\d+\\.?\\d*\\b");

    public SchemaBrowserView(String connectionId) {
        this.connectionId = connectionId;
        this.schemaService = ApplicationContext.getBean(SchemaService.class);

        initializeUI();
        loadSchemaTree();
    }

    private void initializeUI() {
        setTop(createTopBar());
        setLeft(createTreePane());
        setCenter(createDetailPane());
        setBottom(createStatusBar());
        setPadding(new Insets(5));
    }

    private VBox createTopBar() {
        VBox topBar = new VBox(5);
        topBar.setPadding(new Insets(0, 0, 5, 0));

        HBox searchBar = new HBox(10);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        Label searchLabel = new Label("搜索:");
        searchField = new TextField();
        searchField.setPromptText("输入表名/对象名进行搜索...");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTree(newVal));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> loadSchemaTree());

        Button expandAllBtn = new Button("全部展开");
        expandAllBtn.setOnAction(e -> expandAll(schemaTree.getRoot(), true));

        Button collapseAllBtn = new Button("全部折叠");
        collapseAllBtn.setOnAction(e -> expandAll(schemaTree.getRoot(), false));

        searchBar.getChildren().addAll(searchLabel, searchField, refreshBtn, expandAllBtn, collapseAllBtn);
        topBar.getChildren().add(searchBar);
        return topBar;
    }

    private BorderPane createTreePane() {
        BorderPane treePane = new BorderPane();
        treePane.setPrefWidth(300);

        schemaTree = new TreeView<>();
        schemaTree.setShowRoot(false);
        schemaTree.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> onTreeSelectionChanged(newVal));

        schemaTree.setCellFactory(tv -> new TreeCell<SchemaObject>() {
            @Override
            protected void updateItem(SchemaObject item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getDisplayName());
                    if (item.getComment() != null && !item.getComment().isEmpty()) {
                        setTooltip(new Tooltip(item.getComment()));
                    }
                }
            }
        });

        Label treeLabel = new Label("对象导航");
        treeLabel.setStyle("-fx-font-weight: bold; -fx-padding: 5;");

        treePane.setTop(treeLabel);
        treePane.setCenter(schemaTree);
        return treePane;
    }

    private TabPane createDetailPane() {
        detailTabPane = new TabPane();

        Tab ddlTab = new Tab("DDL", createDDLTab());
        ddlTab.setClosable(false);

        Tab propertiesTab = new Tab("属性", createPropertiesTab());
        propertiesTab.setClosable(false);

        Tab compareTab = new Tab("DDL对比", createCompareTab());
        compareTab.setClosable(false);

        detailTabPane.getTabs().addAll(ddlTab, propertiesTab, compareTab);
        return detailTabPane;
    }

    private BorderPane createDDLTab() {
        BorderPane ddlPane = new BorderPane();

        HBox toolBar = new HBox(10);
        toolBar.setPadding(new Insets(5));
        toolBar.setAlignment(Pos.CENTER_LEFT);

        Button generateBtn = new Button("生成DDL");
        generateBtn.setOnAction(e -> generateDDL());

        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> copyDDL());

        Button saveBtn = new Button("保存到文件");
        saveBtn.setOnAction(e -> saveDDLToFile());

        Button setAsCompare1Btn = new Button("设为对比A");
        setAsCompare1Btn.setOnAction(e -> setAsCompareObject(1));

        Button setAsCompare2Btn = new Button("设为对比B");
        setAsCompare2Btn.setOnAction(e -> setAsCompareObject(2));

        toolBar.getChildren().addAll(generateBtn, copyBtn, saveBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                setAsCompare1Btn, setAsCompare2Btn);

        ddlArea = new CodeArea();
        ddlArea.setEditable(false);
        ddlArea.setParagraphGraphicFactory(LineNumberFactory.get(ddlArea));
        ddlArea.textProperty().addListener((obs, oldText, newText) ->
                ddlArea.setStyleSpans(0, computeHighlighting(newText)));

        ddlPane.setTop(toolBar);
        ddlPane.setCenter(ddlArea);
        return ddlPane;
    }

    private BorderPane createPropertiesTab() {
        BorderPane propsPane = new BorderPane();

        TableView<PropertyItem> propsTable = new TableView<>();
        propsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<PropertyItem, String> nameCol = new TableColumn<>("属性");
        nameCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().name));
        nameCol.setPrefWidth(150);

        TableColumn<PropertyItem, String> valueCol = new TableColumn<>("值");
        valueCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().value));

        propsTable.getColumns().addAll(nameCol, valueCol);

        schemaTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && newVal.getValue() != null) {
                SchemaObject obj = newVal.getValue();
                propsTable.getItems().clear();
                propsTable.getItems().add(new PropertyItem("名称", obj.getName()));
                propsTable.getItems().add(new PropertyItem("类型", obj.getType().getDisplayName()));
                propsTable.getItems().add(new PropertyItem("Schema", obj.getSchemaName()));
                if (obj.getParentName() != null) {
                    propsTable.getItems().add(new PropertyItem("父对象", obj.getParentName()));
                }
                propsTable.getItems().add(new PropertyItem("完整名称", obj.getFullName()));
                if (obj.getComment() != null) {
                    propsTable.getItems().add(new PropertyItem("注释", obj.getComment()));
                }
                propsTable.getItems().add(new PropertyItem("子对象数", String.valueOf(obj.getChildren().size())));
                propsTable.getItems().add(new PropertyItem("已加载", obj.isLoaded() ? "是" : "否"));
            }
        });

        propsPane.setCenter(propsTable);
        return propsPane;
    }

    private BorderPane createCompareTab() {
        BorderPane comparePane = new BorderPane();

        HBox toolBar = new HBox(10);
        toolBar.setPadding(new Insets(5));
        toolBar.setAlignment(Pos.CENTER_LEFT);

        Label labelA = new Label("对比A:");
        TextField compareField1 = new TextField();
        compareField1.setPrefWidth(200);
        compareField1.setEditable(false);
        compareField1.setPromptText("选择对象后点击\"设为对比A\"");

        Label labelB = new Label("对比B:");
        TextField compareField2 = new TextField();
        compareField2.setPrefWidth(200);
        compareField2.setEditable(false);
        compareField2.setPromptText("选择对象后点击\"设为对比B\"");

        Button compareBtn = new Button("执行对比");
        compareBtn.setOnAction(e -> executeCompare());

        Button clearBtn = new Button("清除");
        clearBtn.setOnAction(e -> {
            compareObject = null;
            compareField1.clear();
            compareField2.clear();
            compareArea1.clear();
            compareArea2.clear();
            diffArea.clear();
        });

        toolBar.getChildren().addAll(labelA, compareField1, labelB, compareField2, compareBtn, clearBtn);

        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);

        SplitPane ddlSplitPane = new SplitPane();
        compareArea1 = new CodeArea();
        compareArea1.setEditable(false);
        compareArea1.setParagraphGraphicFactory(LineNumberFactory.get(compareArea1));
        compareArea1.textProperty().addListener((obs, oldText, newText) ->
                compareArea1.setStyleSpans(0, computeHighlighting(newText)));

        compareArea2 = new CodeArea();
        compareArea2.setEditable(false);
        compareArea2.setParagraphGraphicFactory(LineNumberFactory.get(compareArea2));
        compareArea2.textProperty().addListener((obs, oldText, newText) ->
                compareArea2.setStyleSpans(0, computeHighlighting(newText)));

        ddlSplitPane.getItems().addAll(compareArea1, compareArea2);
        ddlSplitPane.setDividerPositions(0.5);

        diffArea = new TextArea();
        diffArea.setEditable(false);
        diffArea.setPromptText("对比结果将显示在这里...");
        diffArea.setStyle("-fx-font-family: monospace;");

        splitPane.getItems().addAll(ddlSplitPane, diffArea);
        splitPane.setDividerPositions(0.6);

        comparePane.setTop(toolBar);
        comparePane.setCenter(splitPane);
        return comparePane;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(5, 0, 0, 0));
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("就绪");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(statusLabel, spacer);
        return bar;
    }

    private void loadSchemaTree() {
        statusLabel.setText("正在加载Schema...");
        schemaService.loadSchemasAsync(connectionId, result -> {
            if (result.isSuccess()) {
                TreeItem<SchemaObject> root = new TreeItem<>(
                        new SchemaObject(ObjectType.SCHEMA, "root"));
                root.setExpanded(true);

                for (SchemaObject schema : result.getData()) {
                    TreeItem<SchemaObject> schemaItem = new TreeItem<>(schema);
                    schemaItem.getChildren().add(new TreeItem<>(null));
                    schemaItem.expandedProperty().addListener(
                            (obs, oldVal, newVal) -> onSchemaExpanded(schemaItem, newVal));
                    root.getChildren().add(schemaItem);
                }

                schemaTree.setRoot(root);
                statusLabel.setText("Schema加载完成，共 " + result.getData().size() + " 个");
            } else {
                statusLabel.setText("加载失败");
                showError("加载Schema失败", result.getMessage());
            }
        });
    }

    private void onSchemaExpanded(TreeItem<SchemaObject> schemaItem, boolean expanded) {
        if (!expanded) return;

        SchemaObject schema = schemaItem.getValue();
        if (schema == null || schema.isLoaded()) return;

        schemaItem.getChildren().clear();
        statusLabel.setText("正在加载 " + schema.getName() + " 的表...");

        schemaService.loadTablesAsync(connectionId, schema.getName(), tableResult -> {
            if (tableResult.isSuccess()) {
                TreeItem<SchemaObject> tablesFolder = new TreeItem<>(
                        new SchemaObject(ObjectType.TABLE, "Tables", schema.getName()));
                tablesFolder.setExpanded(true);

                for (SchemaObject table : tableResult.getData()) {
                    TreeItem<SchemaObject> tableItem = new TreeItem<>(table);
                    tableItem.getChildren().add(new TreeItem<>(null));
                    tableItem.expandedProperty().addListener(
                            (obs, oldVal, newVal) -> onTableExpanded(tableItem, newVal));
                    tablesFolder.getChildren().add(tableItem);
                }
                schemaItem.getChildren().add(tablesFolder);

                schemaService.loadProceduresAsync(connectionId, schema.getName(), procResult -> {
                    if (procResult.isSuccess() && !procResult.getData().isEmpty()) {
                        TreeItem<SchemaObject> procsFolder = new TreeItem<>(
                                new SchemaObject(ObjectType.PROCEDURE, "Programmability", schema.getName()));
                        procsFolder.setExpanded(true);

                        for (SchemaObject proc : procResult.getData()) {
                            TreeItem<SchemaObject> procItem = new TreeItem<>(proc);
                            procsFolder.getChildren().add(procItem);
                        }
                        schemaItem.getChildren().add(procsFolder);
                    }
                });

                schema.setLoaded(true);
                statusLabel.setText("加载完成，共 " + tableResult.getData().size() + " 个表");
            } else {
                statusLabel.setText("加载失败");
                showError("加载表列表失败", tableResult.getMessage());
            }
        });
    }

    private void onTableExpanded(TreeItem<SchemaObject> tableItem, boolean expanded) {
        if (!expanded) return;

        SchemaObject table = tableItem.getValue();
        if (table == null || table.isLoaded()) return;

        tableItem.getChildren().clear();
        statusLabel.setText("正在加载 " + table.getName() + " 的详情...");

        schemaService.loadTableChildrenAsync(connectionId, table, result -> {
            if (result.isSuccess()) {
                for (SchemaObject child : result.getData()) {
                    TreeItem<SchemaObject> childItem = new TreeItem<>(child);
                    tableItem.getChildren().add(childItem);
                }
                table.setLoaded(true);
                statusLabel.setText("加载完成，共 " + result.getData().size() + " 个子对象");
            } else {
                statusLabel.setText("加载失败");
                showError("加载表详情失败", result.getMessage());
            }
        });
    }

    private void onTreeSelectionChanged(TreeItem<SchemaObject> newVal) {
        if (newVal != null && newVal.getValue() != null) {
            selectedObject = newVal.getValue();
            ddlArea.clear();
            if (selectedObject.getType() != ObjectType.SCHEMA
                    && selectedObject.getType() != ObjectType.TABLE) {
                generateDDL();
            }
        }
    }

    private void generateDDL() {
        if (selectedObject == null) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先选择一个对象");
            return;
        }

        statusLabel.setText("正在生成DDL...");
        schemaService.generateDDLAsync(connectionId, selectedObject, result -> {
            if (result.isSuccess()) {
                ddlArea.replaceText(result.getData());
                statusLabel.setText("DDL生成完成");
            } else {
                statusLabel.setText("生成失败");
                showError("生成DDL失败", result.getMessage());
            }
        });
    }

    private void copyDDL() {
        String ddl = ddlArea.getText();
        if (!ddl.isEmpty()) {
            javafx.scene.input.Clipboard.getSystemClipboard()
                    .setContent(Collections.singletonMap(
                            javafx.scene.input.DataFormat.PLAIN_TEXT, ddl));
            statusLabel.setText("DDL已复制到剪贴板");
        }
    }

    private void saveDDLToFile() {
        String ddl = ddlArea.getText();
        if (ddl.isEmpty()) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存DDL");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SQL文件", "*.sql"));
        fileChooser.setInitialFileName(
                (selectedObject != null ? selectedObject.getName() : "schema") + ".sql");

        java.io.File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file != null) {
            try {
                java.nio.file.Files.writeString(file.toPath(), ddl);
                statusLabel.setText("DDL已保存到 " + file.getAbsolutePath());
            } catch (Exception e) {
                showError("保存失败", e.getMessage());
            }
        }
    }

    private void setAsCompareObject(int index) {
        if (selectedObject == null) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先选择一个对象");
            return;
        }

        if (index == 1) {
            compareObject = selectedObject;
            TextField field = (TextField) ((HBox) ((BorderPane) detailTabPane.getTabs().get(2)
                    .getContent()).getTop()).getChildren().get(1);
            field.setText(selectedObject.getFullName());

            schemaService.generateDDLAsync(connectionId, selectedObject, result -> {
                if (result.isSuccess()) {
                    compareArea1.replaceText(result.getData());
                }
            });
        } else {
            TextField field = (TextField) ((HBox) ((BorderPane) detailTabPane.getTabs().get(2)
                    .getContent()).getTop()).getChildren().get(3);
            field.setText(selectedObject.getFullName());

            schemaService.generateDDLAsync(connectionId, selectedObject, result -> {
                if (result.isSuccess()) {
                    compareArea2.replaceText(result.getData());
                }
            });
        }
    }

    private void executeCompare() {
        String ddl1 = compareArea1.getText();
        String ddl2 = compareArea2.getText();

        if (ddl1.isEmpty() || ddl2.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先设置两个对比对象");
            return;
        }

        Result<List<String>> diffResult = schemaService.compareDDL(ddl1, ddl2);
        if (diffResult.isSuccess()) {
            List<String> diffs = diffResult.getData();
            StringBuilder sb = new StringBuilder();
            sb.append("=== DDL 对比结果 ===\n");
            sb.append("- 表示只在A中存在\n");
            sb.append("+ 表示只在B中存在\n\n");

            if (diffs.isEmpty()) {
                sb.append("✅ 两个DDL完全相同！");
            } else {
                sb.append("共发现 ").append(diffs.size()).append(" 处差异：\n\n");
                for (String line : diffs) {
                    sb.append(line).append("\n");
                }
            }
            diffArea.setText(sb.toString());
            statusLabel.setText("对比完成，发现 " + diffs.size() + " 处差异");
        } else {
            showError("对比失败", diffResult.getMessage());
        }
    }

    private void filterTree(String filter) {
        TreeItem<SchemaObject> root = schemaTree.getRoot();
        if (root == null) return;

        filterTree(root, filter.toLowerCase());
    }

    private boolean filterTree(TreeItem<SchemaObject> item, String filter) {
        if (item.getValue() == null) return false;

        boolean matches = filter.isEmpty() ||
                item.getValue().getName().toLowerCase().contains(filter);

        boolean childMatches = false;
        for (TreeItem<SchemaObject> child : item.getChildren()) {
            if (filterTree(child, filter)) {
                childMatches = true;
            }
        }

        boolean show = matches || childMatches;
        item.getParent().getChildren().remove(item);
        if (show) {
            item.getParent().getChildren().add(item);
            if (childMatches) item.setExpanded(true);
        }

        return show;
    }

    private void expandAll(TreeItem<SchemaObject> item, boolean expand) {
        if (item == null) return;
        item.setExpanded(expand);
        for (TreeItem<SchemaObject> child : item.getChildren()) {
            expandAll(child, expand);
        }
    }

    private StyleSpans<Collection<String>> computeHighlighting(String text) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        int lastKwEnd = 0;

        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            String styleClass = "keyword";
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);

        lastKwEnd = 0;
        matcher = SQL_STRING_PATTERN.matcher(text);
        StyleSpans<Collection<String>> stringSpans = spansBuilder.create();
        spansBuilder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton("string"), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);

        return spansBuilder.create();
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

    private static class PropertyItem {
        String name;
        String value;

        PropertyItem(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
