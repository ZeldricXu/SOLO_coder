package com.company.dbstudio.data.ui;

import com.company.dbstudio.core.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;

public class JsonViewerDialog extends Stage {
    private final String jsonContent;
    private final String fileName;
    private final TabPane tabPane;
    private JsonNode rootNode;
    private boolean isFormatValid;

    public JsonViewerDialog(String jsonContent, String fileName) {
        this.jsonContent = jsonContent != null ? jsonContent : "";
        this.fileName = fileName != null ? fileName : "json_data";
        this.tabPane = new TabPane();
        this.tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        parseJson();
        initializeUI();
    }

    private void parseJson() {
        try {
            rootNode = JsonUtils.fromJson(jsonContent, JsonNode.class);
            isFormatValid = true;
        } catch (Exception e) {
            isFormatValid = false;
        }
    }

    private void initializeUI() {
        setTitle("JSON查看器 - " + fileName);
        initModality(Modality.APPLICATION_MODAL);
        setWidth(800);
        setHeight(600);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        Label statusLabel = new Label();
        if (isFormatValid) {
            statusLabel.setText("✅ JSON格式有效");
            statusLabel.setStyle("-fx-text-fill: #2b8a3e; -fx-font-weight: bold;");
        } else {
            statusLabel.setText("⚠️ JSON格式无效");
            statusLabel.setStyle("-fx-text-fill: #fab005; -fx-font-weight: bold;");
        }

        createTabs();

        Button formatBtn = new Button("格式化");
        formatBtn.setOnAction(e -> formatJson());

        Button minifyBtn = new Button("压缩");
        minifyBtn.setOnAction(e -> minifyJson());

        Button validateBtn = new Button("验证");
        validateBtn.setOnAction(e -> validateJson());

        Button copyBtn = new Button("复制");
        copyBtn.setOnAction(e -> copyToClipboard());

        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> close());

        HBox buttonBox = new HBox(10, formatBtn, minifyBtn, validateBtn, copyBtn, closeBtn);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        VBox contentBox = new VBox(10, statusLabel, tabPane, buttonBox);
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);
        root.setCenter(contentBox);

        Scene scene = new Scene(root);
        setScene(scene);
    }

    private void createTabs() {
        if (isFormatValid) {
            Tab treeTab = new Tab("树形视图", createTreeView());
            tabPane.getTabs().add(treeTab);
        }

        Tab textTab = new Tab("文本视图", createTextView());
        Tab queryTab = new Tab("JSONPath查询", createQueryView());
        Tab statsTab = new Tab("统计信息", createStatsView());

        tabPane.getTabs().addAll(textTab, queryTab, statsTab);
    }

    private TreeView<String> createTreeView() {
        TreeItem<String> rootItem = buildTree(rootNode, "root");
        TreeView<String> treeView = new TreeView<>(rootItem);
        rootItem.setExpanded(true);
        
        treeView.setCellFactory(param -> new TreeCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    if (item.startsWith("📄") || item.startsWith("🔤")) {
                        setStyle("-fx-text-fill: #1864ab;");
                    } else if (item.startsWith("🔢")) {
                        setStyle("-fx-text-fill: #2f9e44;");
                    } else if (item.startsWith("✅") || item.startsWith("❌")) {
                        setStyle("-fx-text-fill: #ae3ec9;");
                    } else if (item.startsWith("🗑️")) {
                        setStyle("-fx-text-fill: #868e96;");
                    } else {
                        setStyle("-fx-text-fill: #495057;");
                    }
                }
            }
        });
        
        return treeView;
    }

    private TreeItem<String> buildTree(JsonNode node, String name) {
        TreeItem<String> item;
        
        if (node.isObject()) {
            item = new TreeItem<>("📁 " + name + " (Object)");
            node.fields().forEachRemaining(entry -> {
                item.getChildren().add(buildTree(entry.getValue(), entry.getKey()));
            });
        } else if (node.isArray()) {
            item = new TreeItem<>("📂 " + name + " (Array[" + node.size() + "])");
            for (int i = 0; i < node.size(); i++) {
                item.getChildren().add(buildTree(node.get(i), "[" + i + "]"));
            }
        } else if (node.isTextual()) {
            String value = node.asText();
            String display = value.length() > 50 ? value.substring(0, 50) + "..." : value;
            item = new TreeItem<>("🔤 " + name + ": \"" + display + "\"");
        } else if (node.isNumber()) {
            item = new TreeItem<>("🔢 " + name + ": " + node.asText());
        } else if (node.isBoolean()) {
            item = new TreeItem<>((node.asBoolean() ? "✅ " : "❌ ") + name + ": " + node.asText());
        } else if (node.isNull()) {
            item = new TreeItem<>("🗑️ " + name + ": null");
        } else {
            item = new TreeItem<>("📄 " + name + ": " + node.asText());
        }
        
        return item;
    }

    private TextArea createTextView() {
        TextArea textArea = new TextArea();
        textArea.setEditable(true);
        textArea.setWrapText(true);
        textArea.setFont(Font.font("Monaco", 12));
        textArea.setStyle("-fx-background-color: #1e1e1e; -fx-text-fill: #d4d4d4;");
        
        if (isFormatValid) {
            try {
                textArea.setText(JsonUtils.toJsonPretty(rootNode));
            } catch (Exception e) {
                textArea.setText(jsonContent);
            }
        } else {
            textArea.setText(jsonContent);
        }
        
        return textArea;
    }

    private BorderPane createQueryView() {
        BorderPane pane = new BorderPane();
        
        TextField queryField = new TextField();
        queryField.setPromptText("输入JSONPath表达式，如: $.name 或 $.users[0].name");
        
        TextArea resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setWrapText(true);
        resultArea.setFont(Font.font("Monaco", 12));
        resultArea.setStyle("-fx-background-color: #1e1e1e; -fx-text-fill: #d4d4d4;");
        
        Button executeBtn = new Button("执行");
        executeBtn.setOnAction(e -> executeJsonPathQuery(queryField.getText(), resultArea));
        
        HBox queryBox = new HBox(10, new Label("JSONPath:"), queryField, executeBtn);
        HBox.setHgrow(queryField, javafx.scene.layout.Priority.ALWAYS);
        queryBox.setPadding(new Insets(5, 0, 5, 0));
        
        pane.setTop(queryBox);
        pane.setCenter(resultArea);
        
        return pane;
    }

    private void executeJsonPathQuery(String jsonPath, TextArea resultArea) {
        if (StringUtils.isEmpty(jsonPath) || rootNode == null) {
            resultArea.setText("请输入有效的JSONPath表达式");
            return;
        }
        
        try {
            JsonNode result = rootNode.at(jsonPath);
            if (result.isMissingNode()) {
                resultArea.setText("未找到匹配的路径");
            } else {
                resultArea.setText(JsonUtils.toJsonPretty(result));
            }
        } catch (Exception e) {
            resultArea.setText("查询错误: " + e.getMessage());
        }
    }

    private TextArea createStatsView() {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(Font.font("Monaco", 12));
        
        StringBuilder stats = new StringBuilder();
        stats.append("文件: ").append(fileName).append("\n");
        stats.append("原始长度: ").append(jsonContent.length()).append(" 字符\n");
        
        if (isFormatValid) {
            stats.append("格式: 有效JSON\n");
            stats.append("根类型: ").append(rootNode.getNodeType()).append("\n");
            
            int[] counts = countNodes(rootNode);
            stats.append("对象数量: ").append(counts[0]).append("\n");
            stats.append("数组数量: ").append(counts[1]).append("\n");
            stats.append("字符串数量: ").append(counts[2]).append("\n");
            stats.append("数字数量: ").append(counts[3]).append("\n");
            stats.append("布尔值数量: ").append(counts[4]).append("\n");
            stats.append("空值数量: ").append(counts[5]).append("\n");
            stats.append("总节点数: ").append(counts[6]).append("\n");
            
            try {
                String formatted = JsonUtils.toJsonPretty(rootNode);
                stats.append("格式化后长度: ").append(formatted.length()).append(" 字符\n");
                
                String minified = JsonUtils.toJson(rootNode);
                stats.append("压缩后长度: ").append(minified.length()).append(" 字符\n");
                stats.append("压缩率: ").append(String.format("%.1f%%", 
                        (1 - (double) minified.length() / formatted.length()) * 100)).append("\n");
            } catch (Exception ignored) {
            }
        } else {
            stats.append("格式: 无效JSON\n");
        }
        
        textArea.setText(stats.toString());
        return textArea;
    }

    private int[] countNodes(JsonNode node) {
        int[] counts = new int[7]; // obj, arr, str, num, bool, null, total
        
        if (node.isObject()) {
            counts[0]++;
            node.fields().forEachRemaining(entry -> {
                int[] childCounts = countNodes(entry.getValue());
                for (int i = 0; i < 7; i++) counts[i] += childCounts[i];
            });
        } else if (node.isArray()) {
            counts[1]++;
            node.forEach(child -> {
                int[] childCounts = countNodes(child);
                for (int i = 0; i < 7; i++) counts[i] += childCounts[i];
            });
        } else if (node.isTextual()) {
            counts[2]++;
        } else if (node.isNumber()) {
            counts[3]++;
        } else if (node.isBoolean()) {
            counts[4]++;
        } else if (node.isNull()) {
            counts[5]++;
        }
        counts[6]++;
        
        return counts;
    }

    private void formatJson() {
        if (!isFormatValid) {
            showError("格式错误", "JSON格式无效，无法格式化");
            return;
        }
        
        try {
            String formatted = JsonUtils.toJsonPretty(rootNode);
            Tab textTab = tabPane.getTabs().get(1);
            TextArea textArea = (TextArea) ((ScrollPane) textTab.getContent()).getContent();
            textArea.setText(formatted);
            tabPane.getSelectionModel().select(textTab);
        } catch (Exception e) {
            showError("格式化失败", e.getMessage());
        }
    }

    private void minifyJson() {
        if (!isFormatValid) {
            showError("格式错误", "JSON格式无效，无法压缩");
            return;
        }
        
        try {
            String minified = JsonUtils.toJson(rootNode);
            Tab textTab = tabPane.getTabs().get(1);
            TextArea textArea = (TextArea) ((ScrollPane) textTab.getContent()).getContent();
            textArea.setText(minified);
            tabPane.getSelectionModel().select(textTab);
        } catch (Exception e) {
            showError("压缩失败", e.getMessage());
        }
    }

    private void validateJson() {
        parseJson();
        if (isFormatValid) {
            showInfo("验证成功", "JSON格式有效");
        } else {
            showError("验证失败", "JSON格式无效");
        }
    }

    private void copyToClipboard() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        String text = "";
        
        if (selectedTab.getContent() instanceof TextArea textArea) {
            text = textArea.getText();
        } else if (selectedTab.getContent() instanceof BorderPane borderPane) {
            if (borderPane.getCenter() instanceof TextArea textArea) {
                text = textArea.getText();
            }
        } else if (selectedTab.getContent() instanceof ScrollPane scrollPane) {
            if (scrollPane.getContent() instanceof TextArea textArea) {
                text = textArea.getText();
            }
        }
        
        if (!text.isEmpty()) {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
            showInfo("已复制", "内容已复制到剪贴板");
        }
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
