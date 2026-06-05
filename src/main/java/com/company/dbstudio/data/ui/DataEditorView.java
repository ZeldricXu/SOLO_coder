package com.company.dbstudio.data.ui;

import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.StringUtils;
import com.company.dbstudio.data.model.RowChange;
import com.company.dbstudio.data.model.TableData;
import com.company.dbstudio.data.model.TableData.ColumnMetadata;
import com.company.dbstudio.data.service.DataBrowseService;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DataEditorView extends BorderPane {

    private final DataBrowseService dataBrowseService;
    private final String connectionId;
    private final TableData tableData;
    private final ObservableList<RowChange> pendingChanges;
    private final Map<Integer, Map<String, Object>> originalValues;

    private TableView<ObservableList<Object>> tableView;
    private TextField filterField;
    private TextField orderField;
    private Label statusLabel;
    private Label changesLabel;
    private Spinner<Integer> pageSizeSpinner;
    private ComboBox<String> schemaCombo;
    private ComboBox<String> tableCombo;

    public DataEditorView(String connectionId) {
        this(connectionId, null, null);
    }

    public DataEditorView(String connectionId, String tableName, String schemaName) {
        this.connectionId = connectionId;
        this.dataBrowseService = ApplicationContext.getBean(DataBrowseService.class);
        this.tableData = new TableData(
                tableName != null ? tableName : "",
                schemaName != null ? schemaName : ""
        );
        this.pendingChanges = FXCollections.observableArrayList();
        this.originalValues = new LinkedHashMap<>();

        initializeUI();
        loadSchemas();

        if (tableName != null && !tableName.isEmpty()) {
            loadTableData();
        }
    }

    private void initializeUI() {
        setTop(createTopBar());
        setCenter(createTablePane());
        setBottom(createStatusBar());
        setPadding(new Insets(5));
    }

    private VBox createTopBar() {
        VBox topBar = new VBox(5);
        topBar.setPadding(new Insets(0, 0, 5, 0));

        HBox tableSelectorBar = createTableSelectorBar();
        HBox toolBar = createToolBar();
        HBox filterBar = createFilterBar();

        topBar.getChildren().addAll(tableSelectorBar, toolBar, filterBar);
        return topBar;
    }

    private HBox createTableSelectorBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);

        Label schemaLabel = new Label("Schema:");
        schemaCombo = new ComboBox<>();
        schemaCombo.setPrefWidth(150);
        schemaCombo.setOnAction(e -> onSchemaChanged());

        Label tableLabel = new Label("Table:");
        tableCombo = new ComboBox<>();
        tableCombo.setPrefWidth(200);
        tableCombo.setOnAction(e -> onTableChanged());

        Button loadBtn = new Button("加载");
        loadBtn.setOnAction(e -> loadTableData());

        bar.getChildren().addAll(schemaLabel, schemaCombo, tableLabel, tableCombo, loadBtn);
        return bar;
    }

    private HBox createToolBar() {
        HBox bar = new HBox(5);
        bar.setAlignment(Pos.CENTER_LEFT);

        Button addRowBtn = new Button("+ 新增行");
        addRowBtn.setOnAction(e -> addNewRow());

        Button deleteRowBtn = new Button("- 删除行");
        deleteRowBtn.setOnAction(e -> deleteSelectedRow());

        Button saveBtn = new Button("保存更改");
        saveBtn.setOnAction(e -> saveChanges());

        Button revertBtn = new Button("撤销");
        revertBtn.setOnAction(e -> revertChanges());

        Button refreshBtn = new Button("刷新");
        refreshBtn.setOnAction(e -> loadTableData());

        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);

        Button firstPageBtn = new Button("|<");
        firstPageBtn.setOnAction(e -> goToFirstPage());

        Button prevPageBtn = new Button("<");
        prevPageBtn.setOnAction(e -> goToPreviousPage());

        Button nextPageBtn = new Button(">");
        nextPageBtn.setOnAction(e -> goToNextPage());

        Button lastPageBtn = new Button(">|");
        lastPageBtn.setOnAction(e -> goToLastPage());

        Label pageLabel = new Label("页大小:");
        pageSizeSpinner = new Spinner<>(10, 1000, 100, 10);
        pageSizeSpinner.setPrefWidth(80);
        pageSizeSpinner.valueProperty().addListener((obs, oldVal, newVal) -> {
            tableData.setPageSize(newVal);
            if (tableData.getTableName() != null && !tableData.getTableName().isEmpty()) {
                loadTableData();
            }
        });

        bar.getChildren().addAll(
                addRowBtn, deleteRowBtn, saveBtn, revertBtn, refreshBtn,
                sep1, firstPageBtn, prevPageBtn, nextPageBtn, lastPageBtn,
                pageLabel, pageSizeSpinner
        );
        return bar;
    }

    private HBox createFilterBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);

        Label filterLabel = new Label("WHERE:");
        filterField = new TextField();
        filterField.setPromptText("例如: age > 18 AND status = 'active'");
        HBox.setHgrow(filterField, Priority.ALWAYS);

        Label orderLabel = new Label("ORDER BY:");
        orderField = new TextField();
        orderField.setPromptText("例如: created_at DESC");
        orderField.setPrefWidth(200);

        Button applyFilterBtn = new Button("应用筛选");
        applyFilterBtn.setOnAction(e -> applyFilterAndOrder());

        Button clearFilterBtn = new Button("清除");
        clearFilterBtn.setOnAction(e -> {
            filterField.clear();
            orderField.clear();
            tableData.setWhereClause(null);
            tableData.setOrderByClause(null);
            if (tableData.getTableName() != null && !tableData.getTableName().isEmpty()) {
                loadTableData();
            }
        });

        bar.getChildren().addAll(filterLabel, filterField, orderLabel, orderField, applyFilterBtn, clearFilterBtn);
        return bar;
    }

    private BorderPane createTablePane() {
        BorderPane pane = new BorderPane();

        tableView = new TableView<>();
        tableView.setEditable(true);
        tableView.getSelectionModel().setCellSelectionEnabled(true);
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        tableView.setRowFactory(tv -> {
            TableRow<ObservableList<Object>> row = new TableRow<>();
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(createRowContextMenu(row))
            );
            return row;
        });

        pane.setCenter(tableView);
        return pane;
    }

    private ContextMenu createRowContextMenu(TableRow<ObservableList<Object>> row) {
        ContextMenu menu = new ContextMenu();

        MenuItem viewBlobItem = new MenuItem("查看BLOB/CLOB");
        viewBlobItem.setOnAction(e -> viewSpecialField(row.getItem(), true));

        MenuItem viewJsonItem = new MenuItem("查看JSON");
        viewJsonItem.setOnAction(e -> viewSpecialField(row.getItem(), false));

        MenuItem copyRowItem = new MenuItem("复制行数据");
        copyRowItem.setOnAction(e -> copyRowToClipboard(row.getItem()));

        SeparatorMenuItem sep = new SeparatorMenuItem();

        MenuItem deleteItem = new MenuItem("删除此行");
        deleteItem.setOnAction(e -> deleteRow(row.getIndex()));

        menu.getItems().addAll(viewBlobItem, viewJsonItem, copyRowItem, sep, deleteItem);
        return menu;
    }

    private HBox createStatusBar() {
        HBox bar = new HBox(20);
        bar.setPadding(new Insets(5, 0, 0, 0));
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("就绪");
        changesLabel = new Label("0 待保存更改");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(statusLabel, spacer, changesLabel);
        return bar;
    }

    private void loadSchemas() {
        dataBrowseService.getSchemaNamesAsync(connectionId, result -> {
            if (result.isSuccess()) {
                schemaCombo.getItems().setAll(result.getData());
                if (!schemaCombo.getItems().isEmpty()) {
                    if (tableData.getSchemaName() != null && !tableData.getSchemaName().isEmpty()) {
                        schemaCombo.getSelectionModel().select(tableData.getSchemaName());
                    } else {
                        schemaCombo.getSelectionModel().select(0);
                    }
                }
            } else {
                showError("加载Schema失败", result.getMessage());
            }
        });
    }

    private void onSchemaChanged() {
        String schema = schemaCombo.getSelectionModel().getSelectedItem();
        if (schema != null) {
            dataBrowseService.getTableNamesAsync(connectionId, schema, result -> {
                if (result.isSuccess()) {
                    tableCombo.getItems().setAll(result.getData());
                    if (tableData.getTableName() != null && !tableData.getTableName().isEmpty()) {
                        tableCombo.getSelectionModel().select(tableData.getTableName());
                    }
                } else {
                    showError("加载表列表失败", result.getMessage());
                }
            });
        }
    }

    private void onTableChanged() {
        String table = tableCombo.getSelectionModel().getSelectedItem();
        if (table != null) {
            tableData.setTableName(table);
            tableData.setSchemaName(schemaCombo.getSelectionModel().getSelectedItem());
        }
    }

    private void applyFilterAndOrder() {
        tableData.setWhereClause(filterField.getText().trim().isEmpty() ? null : filterField.getText().trim());
        tableData.setOrderByClause(orderField.getText().trim().isEmpty() ? null : orderField.getText().trim());
        tableData.firstPage();
        loadTableData();
    }

    private void loadTableData() {
        if (tableData.getTableName() == null || tableData.getTableName().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先选择要浏览的表");
            return;
        }

        statusLabel.setText("正在加载数据...");
        pendingChanges.clear();
        originalValues.clear();
        updateChangesLabel();

        dataBrowseService.loadTableDataAsync(connectionId, tableData, result -> {
            if (result.isSuccess()) {
                buildTableColumns();
                tableView.setItems(result.getData().getRows());
                statusLabel.setText(String.format("加载完成: 共 %d 行, 第 %d/%d 页",
                        tableData.getTotalRows(),
                        tableData.getCurrentPage(),
                        tableData.getTotalPages()));
            } else {
                statusLabel.setText("加载失败");
                showError("加载数据失败", result.getMessage());
            }
        });
    }

    private void buildTableColumns() {
        tableView.getColumns().clear();
        List<ColumnMetadata> columns = tableData.getColumns();

        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            ColumnMetadata colMeta = columns.get(i);

            TableColumn<ObservableList<Object>, Object> col = new TableColumn<>();
            col.setText(colMeta.getName());
            col.setUserData(colMeta);

            StringBuilder headerText = new StringBuilder(colMeta.getName());
            if (colMeta.isPrimaryKey()) {
                headerText.append(" 🔑");
            }
            headerText.append("\n").append(colMeta.getFullTypeName());
            if (!colMeta.isNullable()) {
                headerText.append(" NOT NULL");
            }
            col.setGraphic(new Label(headerText.toString()));

            col.setCellValueFactory(param -> {
                ObservableList<Object> row = param.getValue();
                if (colIndex < row.size()) {
                    return new SimpleObjectProperty<>(row.get(colIndex));
                }
                return new SimpleObjectProperty<>(null);
            });

            if (colMeta.isEditable() && !colMeta.isBlobType() && !colMeta.isJsonType() && !colMeta.isClobType()) {
                col.setCellFactory(createEditableCellFactory(colMeta));
                col.setOnEditCommit(event -> handleCellEdit(event, colIndex, colMeta));
            } else {
                col.setCellFactory(createReadOnlyCellFactory(colMeta));
            }

            col.setPrefWidth(120);
            col.setMinWidth(50);
            tableView.getColumns().add(col);
        }
    }

    private Callback<TableColumn<ObservableList<Object>, Object>,
            TableCell<ObservableList<Object>, Object>> createEditableCellFactory(ColumnMetadata colMeta) {
        return col -> new TextFieldTableCell<>(new StringConverter<>() {
            @Override
            public String toString(Object object) {
                if (object == null) return "";
                if (object instanceof LocalDateTime ldt) {
                    return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
                if (object instanceof LocalDate ld) {
                    return ld.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                }
                return object.toString();
            }

            @Override
            public Object fromString(String string) {
                try {
                    if (colMeta.isNumericType()) {
                        if (string.isEmpty()) return null;
                        if (colMeta.getType().toLowerCase().contains("int")) {
                            return Long.parseLong(string);
                        }
                        return new BigDecimal(string);
                    }
                    if (colMeta.isBooleanType()) {
                        return Boolean.parseBoolean(string) || "1".equals(string);
                    }
                    if (colMeta.isDateType()) {
                        if (string.isEmpty()) return null;
                        if (colMeta.getType().toLowerCase().contains("timestamp")) {
                            return LocalDateTime.parse(string, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        }
                        return LocalDate.parse(string, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                    }
                    return string;
                } catch (Exception e) {
                    return string;
                }
            }
        });
    }

    private Callback<TableColumn<ObservableList<Object>, Object>,
            TableCell<ObservableList<Object>, Object>> createReadOnlyCellFactory(ColumnMetadata colMeta) {
        return col -> new TableCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    if (colMeta.isBlobType() || colMeta.isClobType()) {
                        Button viewBtn = new Button("[" + colMeta.getType() + "] 查看");
                        viewBtn.setOnAction(e -> viewSpecialField(getTableRow().getItem(), colMeta.isBlobType()));
                        setGraphic(viewBtn);
                        setText(null);
                    } else if (colMeta.isJsonType()) {
                        Button viewBtn = new Button("[JSON] 查看");
                        viewBtn.setOnAction(e -> viewSpecialField(getTableRow().getItem(), false));
                        setGraphic(viewBtn);
                        setText(null);
                    } else {
                        setText(item.toString());
                        setGraphic(null);
                    }
                }
            }
        };
    }

    private void handleCellEdit(TableColumn.CellEditEvent<ObservableList<Object>, Object> event,
                                int colIndex, ColumnMetadata colMeta) {
        ObservableList<Object> row = event.getRowValue();
        Object oldValue = originalValues.computeIfAbsent(
                tableView.getItems().indexOf(row),
                k -> new LinkedHashMap<>()
        ).computeIfAbsent(colMeta.getName(), k -> row.get(colIndex));

        Object newValue = event.getNewValue();
        row.set(colIndex, newValue);

        RowChange change = findOrCreateUpdateChange(row);
        change.addOldValue(colMeta.getName(), oldValue, colMeta.getSqlType());
        change.addNewValue(colMeta.getName(), newValue, colMeta.getSqlType());

        addPrimaryKeysToChange(change, row);

        updateChangesLabel();
        tableView.refresh();
    }

    private RowChange findOrCreateUpdateChange(ObservableList<Object> row) {
        int rowIndex = tableView.getItems().indexOf(row);
        for (RowChange change : pendingChanges) {
            if (change.getType() == RowChange.ChangeType.UPDATE
                    && change.getTableName().equals(tableData.getFullTableName())
                    && rowMatchesChange(row, change)) {
                return change;
            }
        }
        RowChange change = RowChange.forUpdate(tableData.getFullTableName());
        pendingChanges.add(change);
        return change;
    }

    private boolean rowMatchesChange(ObservableList<Object> row, RowChange change) {
        for (Map.Entry<String, Object> pk : change.getPrimaryKeys().entrySet()) {
            int colIndex = findColumnIndex(pk.getKey());
            if (colIndex >= 0 && colIndex < row.size()) {
                Object rowVal = row.get(colIndex);
                if (!valuesEqual(rowVal, pk.getValue())) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean valuesEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private void addPrimaryKeysToChange(RowChange change, ObservableList<Object> row) {
        for (ColumnMetadata col : tableData.getColumns()) {
            if (col.isPrimaryKey()) {
                int colIndex = findColumnIndex(col.getName());
                if (colIndex >= 0 && colIndex < row.size()) {
                    change.addPrimaryKey(col.getName(), row.get(colIndex), col.getSqlType());
                }
            }
        }
    }

    private int findColumnIndex(String columnName) {
        List<ColumnMetadata> columns = tableData.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    private void addNewRow() {
        ObservableList<Object> newRow = FXCollections.observableArrayList();
        for (int i = 0; i < tableData.getColumns().size(); i++) {
            newRow.add(null);
        }
        tableView.getItems().add(0, newRow);

        RowChange change = RowChange.forInsert(tableData.getFullTableName());
        pendingChanges.add(change);

        updateChangesLabel();
    }

    private void deleteSelectedRow() {
        ObservableList<Integer> selectedIndices = tableView.getSelectionModel().getSelectedIndices();
        if (selectedIndices.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先选择要删除的行");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("确定要删除选中的 " + selectedIndices.size() + " 行吗？");
        confirm.setContentText("此操作将在保存后从数据库中删除这些行。");

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            List<Integer> indices = new ArrayList<>(selectedIndices);
            indices.sort((a, b) -> b - a);

            for (int rowIndex : indices) {
                ObservableList<Object> row = tableView.getItems().get(rowIndex);
                if (rowIndex >= 0 && rowIndex < tableView.getItems().size()) {
                    RowChange change = RowChange.forDelete(tableData.getFullTableName());
                    addPrimaryKeysToChange(change, row);
                    for (ColumnMetadata col : tableData.getColumns()) {
                        int colIndex = findColumnIndex(col.getName());
                        if (colIndex >= 0 && colIndex < row.size()) {
                            change.addOldValue(col.getName(), row.get(colIndex), col.getSqlType());
                        }
                    }
                    pendingChanges.add(change);
                    tableView.getItems().remove(rowIndex);
                }
            }
            updateChangesLabel();
        }
    }

    private void deleteRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < tableView.getItems().size()) {
            ObservableList<Object> row = tableView.getItems().get(rowIndex);
            RowChange change = RowChange.forDelete(tableData.getFullTableName());
            addPrimaryKeysToChange(change, row);
            for (ColumnMetadata col : tableData.getColumns()) {
                int colIndex = findColumnIndex(col.getName());
                if (colIndex >= 0 && colIndex < row.size()) {
                    change.addOldValue(col.getName(), row.get(colIndex), col.getSqlType());
                }
            }
            pendingChanges.add(change);
            tableView.getItems().remove(rowIndex);
            updateChangesLabel();
        }
    }

    private void saveChanges() {
        if (pendingChanges.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", "没有需要保存的更改");
            return;
        }

        List<RowChange> validChanges = pendingChanges.stream()
                .filter(RowChange::hasChanges)
                .toList();

        if (validChanges.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "提示", "没有有效的更改需要保存");
            pendingChanges.clear();
            updateChangesLabel();
            return;
        }

        statusLabel.setText("正在保存更改...");

        dataBrowseService.applyChangesAsync(connectionId, validChanges, result -> {
            if (result.isSuccess()) {
                statusLabel.setText("保存成功: 影响 " + result.getData() + " 行");
                pendingChanges.clear();
                originalValues.clear();
                updateChangesLabel();
                loadTableData();
            } else {
                statusLabel.setText("保存失败");
                showError("保存更改失败", result.getMessage());
            }
        });
    }

    private void revertChanges() {
        if (pendingChanges.isEmpty()) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认撤销");
        confirm.setHeaderText("确定要撤销所有未保存的更改吗？");

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            pendingChanges.clear();
            originalValues.clear();
            loadTableData();
        }
    }

    private void viewSpecialField(ObservableList<Object> row, boolean isBlob) {
        if (row == null) return;

        TablePosition<?, ?> pos = tableView.getSelectionModel().getSelectedCells().stream()
                .findFirst()
                .orElse(null);

        if (pos == null) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先选择一个字段单元格");
            return;
        }

        int colIndex = pos.getColumn();
        if (colIndex < 0 || colIndex >= tableData.getColumns().size()) {
            return;
        }

        ColumnMetadata colMeta = tableData.getColumns().get(colIndex);
        String whereClause = buildWhereClause(row);

        if (isBlob || colMeta.isBlobType()) {
            statusLabel.setText("正在加载BLOB数据...");
            dataBrowseService.loadBlobDataAsync(connectionId, tableData.getFullTableName(),
                    colMeta.getName(), whereClause, result -> {
                        if (result.isSuccess()) {
                            BlobViewerDialog dialog = new BlobViewerDialog(result.getData(),
                                    colMeta.getName() + " - " + tableData.getFullTableName());
                            dialog.show();
                            statusLabel.setText("BLOB数据加载完成");
                        } else {
                            statusLabel.setText("加载失败");
                            showError("加载BLOB数据失败", result.getMessage());
                        }
                    });
        } else {
            statusLabel.setText("正在加载JSON/CLOB数据...");
            dataBrowseService.loadJsonDataAsync(connectionId, tableData.getFullTableName(),
                    colMeta.getName(), whereClause, result -> {
                        if (result.isSuccess()) {
                            JsonViewerDialog dialog = new JsonViewerDialog(result.getData(),
                                    colMeta.getName() + " - " + tableData.getFullTableName());
                            dialog.show();
                            statusLabel.setText("JSON数据加载完成");
                        } else {
                            statusLabel.setText("加载失败");
                            showError("加载JSON数据失败", result.getMessage());
                        }
                    });
        }
    }

    private String buildWhereClause(ObservableList<Object> row) {
        StringBuilder where = new StringBuilder();
        boolean hasPk = false;

        for (ColumnMetadata col : tableData.getColumns()) {
            if (col.isPrimaryKey()) {
                int colIndex = findColumnIndex(col.getName());
                if (colIndex >= 0 && colIndex < row.size()) {
                    Object value = row.get(colIndex);
                    if (value != null) {
                        if (hasPk) where.append(" AND ");
                        where.append(col.getName()).append(" = ");
                        if (value instanceof Number) {
                            where.append(value);
                        } else {
                            where.append("'").append(value.toString().replace("'", "''")).append("'");
                        }
                        hasPk = true;
                    }
                }
            }
        }

        if (!hasPk) {
            for (ColumnMetadata col : tableData.getColumns()) {
                int colIndex = findColumnIndex(col.getName());
                if (colIndex >= 0 && colIndex < row.size()) {
                    Object value = row.get(colIndex);
                    if (value != null) {
                        if (where.length() > 0) where.append(" AND ");
                        where.append(col.getName()).append(" = ");
                        if (value instanceof Number) {
                            where.append(value);
                        } else {
                            where.append("'").append(value.toString().replace("'", "''")).append("'");
                        }
                    }
                }
            }
        }

        return where.length() > 0 ? where.toString() : null;
    }

    private void copyRowToClipboard(ObservableList<Object> row) {
        if (row == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) sb.append("\t");
            Object val = row.get(i);
            sb.append(val != null ? val.toString() : "NULL");
        }
        javafx.scene.input.Clipboard.getSystemClipboard()
                .setContent(Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, sb.toString()));
    }

    private void goToFirstPage() {
        tableData.firstPage();
        loadTableData();
    }

    private void goToPreviousPage() {
        if (tableData.hasPreviousPage()) {
            tableData.previousPage();
            loadTableData();
        }
    }

    private void goToNextPage() {
        if (tableData.hasNextPage()) {
            tableData.nextPage();
            loadTableData();
        }
    }

    private void goToLastPage() {
        tableData.lastPage();
        loadTableData();
    }

    private void updateChangesLabel() {
        long count = pendingChanges.stream().filter(RowChange::hasChanges).count();
        changesLabel.setText(count + " 待保存更改");
        changesLabel.setStyle(count > 0 ? "-fx-text-fill: #d9534f; -fx-font-weight: bold;" : "");
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

    public TableData getTableData() {
        return tableData;
    }

    public String getConnectionId() {
        return connectionId;
    }
}
