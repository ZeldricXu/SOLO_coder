package com.company.dbstudio.etl.ui;

import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ImportExportConfig.*;
import com.company.dbstudio.etl.service.ImportExportService;
import com.company.dbstudio.etl.service.ImportExportService.ProgressInfo;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

public class ImportExportView extends BorderPane {

    private final ImportExportService importExportService;
    private final String connectionId;
    private final ImportExportConfig config;

    private ComboBox<OperationType> operationTypeCombo;
    private ComboBox<Format> formatCombo;
    private TextField sourceTableField;
    private TextField targetTableField;
    private TextField sourceSchemaField;
    private TextField targetSchemaField;
    private TextField filePathField;
    private TextField encodingField;
    private TextField csvDelimiterField;
    private TextField batchSizeField;
    private CheckBox includeHeaderCheck;
    private CheckBox useTransactionCheck;
    private CheckBox truncateBeforeCheck;
    private ProgressBar progressBar;
    private Label statusLabel;
    private TableView<ColumnMapping> mappingTable;
    private Button executeBtn;
    private Button cancelBtn;

    public ImportExportView(String connectionId) {
        this.connectionId = connectionId;
        this.importExportService = ApplicationContext.getBean(ImportExportService.class);
        this.config = new ImportExportConfig();
        this.config.setConnectionId(connectionId);

        initializeUI();
    }

    private void initializeUI() {
        setTop(createFormPane());
        setCenter(createMappingPane());
        setBottom(createProgressPane());
        setPadding(new Insets(10));
    }

    private VBox createFormPane() {
        VBox formPane = new VBox(10);
        formPane.setPadding(new Insets(0, 0, 10, 0));

        HBox topBar = new HBox(20);
        topBar.setAlignment(Pos.CENTER_LEFT);

        Label opLabel = new Label("操作类型:");
        operationTypeCombo = new ComboBox<>();
        operationTypeCombo.getItems().addAll(OperationType.values());
        operationTypeCombo.setValue(OperationType.EXPORT);
        operationTypeConverter();

        Label formatLabel = new Label("文件格式:");
        formatCombo = new ComboBox<>();
        formatCombo.getItems().addAll(Format.CSV, Format.JSON, Format.EXCEL);
        formatCombo.setValue(Format.CSV);

        topBar.getChildren().addAll(opLabel, operationTypeCombo, formatLabel, formatCombo);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;
        grid.add(new Label("源Schema:"), 0, row);
        sourceSchemaField = new TextField();
        sourceSchemaField.setPrefWidth(200);
        grid.add(sourceSchemaField, 1, row);

        grid.add(new Label("源表名:"), 2, row);
        sourceTableField = new TextField();
        sourceTableField.setPrefWidth(200);
        grid.add(sourceTableField, 3, row);

        row++;
        grid.add(new Label("目标Schema:"), 0, row);
        targetSchemaField = new TextField();
        targetSchemaField.setPrefWidth(200);
        grid.add(targetSchemaField, 1, row);

        grid.add(new Label("目标表名:"), 2, row);
        targetTableField = new TextField();
        targetTableField.setPrefWidth(200);
        grid.add(targetTableField, 3, row);

        row++;
        grid.add(new Label("文件路径:"), 0, row);
        filePathField = new TextField();
        filePathField.setPrefWidth(400);
        grid.add(filePathField, 1, row, 3, 1);

        Button browseBtn = new Button("浏览...");
        browseBtn.setOnAction(e -> browseFile());
        grid.add(browseBtn, 4, row);

        row++;
        grid.add(new Label("编码:"), 0, row);
        encodingField = new TextField("UTF-8");
        encodingField.setPrefWidth(100);
        grid.add(encodingField, 1, row);

        grid.add(new Label("CSV分隔符:"), 2, row);
        csvDelimiterField = new TextField(",");
        csvDelimiterField.setPrefWidth(60);
        grid.add(csvDelimiterField, 3, row);

        row++;
        grid.add(new Label("批量大小:"), 0, row);
        batchSizeField = new TextField("1000");
        batchSizeField.setPrefWidth(100);
        grid.add(batchSizeField, 1, row);

        includeHeaderCheck = new CheckBox("包含表头");
        includeHeaderCheck.setSelected(true);
        grid.add(includeHeaderCheck, 2, row);

        useTransactionCheck = new CheckBox("使用事务");
        useTransactionCheck.setSelected(true);
        grid.add(useTransactionCheck, 3, row);

        row++;
        truncateBeforeCheck = new CheckBox("导入前清空表");
        truncateBeforeCheck.setSelected(false);
        grid.add(truncateBeforeCheck, 0, row, 2, 1);

        HBox buttonBar = new HBox(10);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(10, 0, 0, 0));

        executeBtn = new Button("执行");
        executeBtn.setOnAction(e -> execute());

        cancelBtn = new Button("取消");
        cancelBtn.setDisable(true);
        cancelBtn.setOnAction(e -> cancel());

        buttonBar.getChildren().addAll(executeBtn, cancelBtn);

        formPane.getChildren().addAll(topBar, grid, buttonBar);
        return formPane;
    }

    private BorderPane createMappingPane() {
        BorderPane mappingPane = new BorderPane();
        mappingPane.setPadding(new Insets(10, 0, 10, 0));

        Label title = new Label("列映射配置");
        title.setStyle("-fx-font-weight: bold;");

        mappingTable = new TableView<>();
        mappingTable.setEditable(true);
        mappingTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ColumnMapping, Boolean> includeCol = new TableColumn<>("包含");
        includeCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().isInclude()));
        includeCol.setCellFactory(col -> new CheckBoxTableCell<>());
        includeCol.setOnEditCommit(e -> e.getRowValue().setInclude(e.getNewValue()));
        includeCol.setPrefWidth(60);

        TableColumn<ColumnMapping, String> sourceCol = new TableColumn<>("源列名");
        sourceCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().getSourceColumn()));
        sourceCol.setCellFactory(TextFieldTableCell.forTableColumn());
        sourceCol.setOnEditCommit(e -> e.getRowValue().setSourceColumn(e.getNewValue()));

        TableColumn<ColumnMapping, String> targetCol = new TableColumn<>("目标列名");
        targetCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().getTargetColumn()));
        targetCol.setCellFactory(TextFieldTableCell.forTableColumn());
        targetCol.setOnEditCommit(e -> e.getRowValue().setTargetColumn(e.getNewValue()));

        TableColumn<ColumnMapping, ValueTransform> transformCol = new TableColumn<>("值转换");
        transformCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().getTransform()));
        transformCol.setCellFactory(col -> new ComboBoxTableCell<>(ValueTransform.values()));
        transformCol.setOnEditCommit(e -> e.getRowValue().setTransform(e.getNewValue()));

        TableColumn<ColumnMapping, String> defaultCol = new TableColumn<>("默认值");
        defaultCol.setCellValueFactory(p -> new SimpleObjectProperty<>(p.getValue().getDefaultValue()));
        defaultCol.setCellFactory(TextFieldTableCell.forTableColumn());
        defaultCol.setOnEditCommit(e -> e.getRowValue().setDefaultValue(e.getNewValue()));

        mappingTable.getColumns().addAll(includeCol, sourceCol, targetCol, transformCol, defaultCol);

        HBox mappingToolbar = new HBox(10);
        mappingToolbar.setAlignment(Pos.CENTER_LEFT);
        mappingToolbar.setPadding(new Insets(5, 0, 5, 0));

        Button addMappingBtn = new Button("+ 添加映射");
        addMappingBtn.setOnAction(e -> addMapping());

        Button removeMappingBtn = new Button("- 删除选中");
        removeMappingBtn.setOnAction(e -> removeSelectedMapping());

        Button autoDetectBtn = new Button("自动检测列");
        autoDetectBtn.setOnAction(e -> autoDetectColumns());

        mappingToolbar.getChildren().addAll(addMappingBtn, removeMappingBtn, autoDetectBtn);

        VBox content = new VBox(5, title, mappingToolbar, mappingTable);
        mappingPane.setCenter(content);

        return mappingPane;
    }

    private HBox createProgressPane() {
        HBox progressPane = new HBox(10);
        progressPane.setAlignment(Pos.CENTER_LEFT);
        progressPane.setPadding(new Insets(10, 0, 0, 0));

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);

        statusLabel = new Label("就绪");

        progressPane.getChildren().addAll(new Label("进度:"), progressBar, statusLabel);
        return progressPane;
    }

    private void operationTypeConverter() {
        operationTypeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            boolean isExport = newVal == OperationType.EXPORT;
            sourceTableField.setDisable(!isExport);
            sourceSchemaField.setDisable(!isExport);
            targetTableField.setDisable(isExport);
            targetSchemaField.setDisable(isExport);
            truncateBeforeCheck.setDisable(isExport);
        });
    }

    private void browseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(operationTypeCombo.getValue() == OperationType.EXPORT ? "选择导出文件" : "选择导入文件");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        formatCombo.getValue().getDisplayName() + "文件",
                        "*." + formatCombo.getValue().getExtension()
                )
        );

        File file;
        if (operationTypeCombo.getValue() == OperationType.EXPORT) {
            file = fileChooser.showSaveDialog(getScene().getWindow());
        } else {
            file = fileChooser.showOpenDialog(getScene().getWindow());
        }

        if (file != null) {
            filePathField.setText(file.getAbsolutePath());
        }
    }

    private void addMapping() {
        ColumnMapping mapping = new ColumnMapping();
        mapping.setSourceColumn("column" + (mappingTable.getItems().size() + 1));
        mapping.setTargetColumn("column" + (mappingTable.getItems().size() + 1));
        mappingTable.getItems().add(mapping);
    }

    private void removeSelectedMapping() {
        ColumnMapping selected = mappingTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            mappingTable.getItems().remove(selected);
        }
    }

    private void autoDetectColumns() {
        mappingTable.getItems().clear();

        String tableName = operationTypeCombo.getValue() == OperationType.EXPORT
                ? sourceTableField.getText()
                : targetTableField.getText();
        String schemaName = operationTypeCombo.getValue() == OperationType.EXPORT
                ? sourceSchemaField.getText()
                : targetSchemaField.getText();

        if (tableName.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "提示", "请先输入表名");
            return;
        }

        for (int i = 0; i < 5; i++) {
            ColumnMapping mapping = new ColumnMapping();
            mapping.setSourceColumn("col" + (i + 1));
            mapping.setTargetColumn("col" + (i + 1));
            mapping.setSourceIndex(i);
            mapping.setTargetIndex(i);
            mappingTable.getItems().add(mapping);
        }
    }

    private void collectConfig() {
        config.setOperationType(operationTypeCombo.getValue());
        config.setFormat(formatCombo.getValue());
        config.setSourceSchema(sourceSchemaField.getText().trim());
        config.setSourceTable(sourceTableField.getText().trim());
        config.setTargetSchema(targetSchemaField.getText().trim());
        config.setTargetTable(targetTableField.getText().trim());
        config.setFilePath(filePathField.getText().trim());
        config.setEncoding(encodingField.getText().trim());
        config.setCsvDelimiter(csvDelimiterField.getText().trim());
        config.setIncludeHeader(includeHeaderCheck.isSelected());
        config.setUseTransaction(useTransactionCheck.isSelected());
        config.setTruncateBeforeInsert(truncateBeforeCheck.isSelected());

        try {
            config.setBatchSize(Integer.parseInt(batchSizeField.getText().trim()));
        } catch (NumberFormatException e) {
            config.setBatchSize(1000);
        }

        config.getColumnMappings().clear();
        config.getColumnMappings().addAll(mappingTable.getItems());
    }

    private boolean validateConfig() {
        if (filePathField.getText().trim().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "验证失败", "请选择文件路径");
            return false;
        }

        boolean isExport = operationTypeCombo.getValue() == OperationType.EXPORT;
        String tableName = isExport ? sourceTableField.getText().trim() : targetTableField.getText().trim();
        if (tableName.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "验证失败", "请输入表名");
            return false;
        }

        return true;
    }

    private void execute() {
        if (!validateConfig()) return;

        collectConfig();

        executeBtn.setDisable(true);
        cancelBtn.setDisable(false);
        progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        statusLabel.setText("正在执行...");

        Consumer<ProgressInfo> progressCallback = info -> {
            statusLabel.setText(info.getMessage());
            if (info.getProgressPercent() > 0) {
                progressBar.setProgress(info.getProgressPercent() / 100);
            }
        };

        Consumer<Result<Long>> resultCallback = result -> {
            executeBtn.setDisable(false);
            cancelBtn.setDisable(true);
            progressBar.setProgress(result.isSuccess() ? 1 : 0);

            if (result.isSuccess()) {
                statusLabel.setText("完成，共处理 " + result.getData() + " 行");
                showAlert(Alert.AlertType.INFORMATION, "成功",
                        operationTypeCombo.getValue() == OperationType.EXPORT ? "导出" : "导入" +
                                "完成，共处理 " + result.getData() + " 行");
            } else {
                statusLabel.setText("失败: " + result.getMessage());
                showError("操作失败", result.getMessage());
            }
        };

        if (config.getOperationType() == OperationType.EXPORT) {
            importExportService.exportDataAsync(config, progressCallback, resultCallback);
        } else {
            importExportService.importDataAsync(config, progressCallback, resultCallback);
        }
    }

    private void cancel() {
        importExportService.cancel();
        cancelBtn.setDisable(true);
        executeBtn.setDisable(false);
        statusLabel.setText("已取消");
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
