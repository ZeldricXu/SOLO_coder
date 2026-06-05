package com.company.dbstudio.data.ui;

import com.company.dbstudio.core.util.IOUtils;
import com.company.dbstudio.core.util.StringUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Base64;

public class BlobViewerDialog extends Stage {
    private final byte[] data;
    private final String fileName;
    private final TabPane tabPane;

    public BlobViewerDialog(byte[] data, String fileName) {
        this.data = data;
        this.fileName = fileName != null ? fileName : "blob_data";
        
        this.tabPane = new TabPane();
        this.tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        initializeUI();
    }

    private void initializeUI() {
        setTitle("BLOB查看器 - " + fileName);
        initModality(Modality.APPLICATION_MODAL);
        setWidth(800);
        setHeight(600);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        Label infoLabel = new Label("文件大小: " + StringUtils.formatBytes(data != null ? data.length : 0));
        infoLabel.setStyle("-fx-font-weight: bold;");

        createTabs();

        Button saveBtn = new Button("保存到文件");
        saveBtn.setOnAction(e -> saveToFile());

        Button copyBase64Btn = new Button("复制Base64");
        copyBase64Btn.setOnAction(e -> copyBase64());

        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> close());

        HBox buttonBox = new HBox(10, saveBtn, copyBase64Btn, closeBtn);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));

        VBox contentBox = new VBox(10, infoLabel, tabPane, buttonBox);
        VBox.setVgrow(tabPane, javafx.scene.layout.Priority.ALWAYS);
        root.setCenter(contentBox);

        Scene scene = new Scene(root);
        setScene(scene);
    }

    private void createTabs() {
        if (isImageData()) {
            Tab imageTab = new Tab("图片预览", createImageView());
            tabPane.getTabs().add(imageTab);
        }

        Tab hexTab = new Tab("十六进制", createHexView());
        Tab textTab = new Tab("文本", createTextView());
        Tab base64Tab = new Tab("Base64", createBase64View());
        Tab infoTab = new Tab("信息", createInfoView());

        tabPane.getTabs().addAll(hexTab, textTab, base64Tab, infoTab);
    }

    private boolean isImageData() {
        if (data == null || data.length < 4) return false;
        
        byte[] header = new byte[4];
        System.arraycopy(data, 0, header, 0, 4);
        
        // JPEG: FF D8 FF
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
            return true;
        }
        // PNG: 89 50 4E 47
        if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
            return true;
        }
        // GIF: 47 49 46 38
        if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38) {
            return true;
        }
        // BMP: 42 4D
        if (header[0] == 0x42 && header[1] == 0x4D) {
            return true;
        }
        return false;
    }

    private ScrollPane createImageView() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);

        try {
            Image image = new Image(new ByteArrayInputStream(data));
            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);
            scrollPane.setContent(imageView);
        } catch (Exception e) {
            Label errorLabel = new Label("无法预览图片: " + e.getMessage());
            scrollPane.setContent(errorLabel);
        }

        return scrollPane;
    }

    private TextArea createHexView() {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setFont(javafx.scene.text.Font.font("Monaco", 12));
        textArea.setStyle("-fx-background-color: #1e1e1e; -fx-text-fill: #d4d4d4;");

        StringBuilder hexBuilder = new StringBuilder();
        if (data != null) {
            for (int i = 0; i < data.length; i += 16) {
                hexBuilder.append(String.format("%08X: ", i));
                
                for (int j = 0; j < 16; j++) {
                    if (i + j < data.length) {
                        hexBuilder.append(String.format("%02X ", data[i + j]));
                    } else {
                        hexBuilder.append("   ");
                    }
                    if (j == 7) hexBuilder.append(" ");
                }
                
                hexBuilder.append("  |");
                for (int j = 0; j < 16 && i + j < data.length; j++) {
                    byte b = data[i + j];
                    if (b >= 32 && b < 127) {
                        hexBuilder.append((char) b);
                    } else {
                        hexBuilder.append(".");
                    }
                }
                hexBuilder.append("|\n");
            }
        }
        
        textArea.setText(hexBuilder.toString());
        return textArea;
    }

    private TextArea createTextView() {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(javafx.scene.text.Font.font("Monaco", 12));

        if (data != null) {
            try {
                textArea.setText(new String(data, "UTF-8"));
            } catch (Exception e) {
                textArea.setText("无法作为文本显示: " + e.getMessage());
            }
        }

        return textArea;
    }

    private TextArea createBase64View() {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(javafx.scene.text.Font.font("Monaco", 12));

        if (data != null) {
            String base64 = Base64.getEncoder().encodeToString(data);
            textArea.setText(base64);
        }

        return textArea;
    }

    private TextArea createInfoView() {
        TextArea textArea = new TextArea();
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setFont(javafx.scene.text.Font.font("Monaco", 12));

        StringBuilder info = new StringBuilder();
        info.append("文件名: ").append(fileName).append("\n");
        info.append("文件大小: ").append(StringUtils.formatBytes(data != null ? data.length : 0)).append("\n");
        info.append("字节数: ").append(data != null ? data.length : 0).append("\n");
        
        if (data != null && data.length > 0) {
            info.append("前16字节: ");
            for (int i = 0; i < Math.min(16, data.length); i++) {
                info.append(String.format("%02X ", data[i]));
            }
            info.append("\n");
            
            String mimeType = guessMimeType();
            info.append("猜测MIME类型: ").append(mimeType).append("\n");
        }

        textArea.setText(info.toString());
        return textArea;
    }

    private String guessMimeType() {
        if (data == null || data.length < 4) return "application/octet-stream";
        
        byte[] header = new byte[4];
        System.arraycopy(data, 0, header, 0, Math.min(4, data.length));
        
        if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) return "image/jpeg";
        if (header[0] == (byte) 0x89 && header[1] == 0x50) return "image/png";
        if (header[0] == 0x47 && header[1] == 0x49) return "image/gif";
        if (header[0] == 0x42 && header[1] == 0x4D) return "image/bmp";
        if (header[0] == 0x25 && header[1] == 0x50) return "application/pdf";
        if (header[0] == 0x50 && header[1] == 0x4B) return "application/zip";
        if (header[0] == 0x7F && header[1] == 0x45) return "application/x-executable";
        if (header[0] == 0x1F && header[1] == (byte) 0x8B) return "application/x-gzip";
        
        return "application/octet-stream";
    }

    private void saveToFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存BLOB数据");
        fileChooser.setInitialFileName(fileName);
        
        File file = fileChooser.showSaveDialog(this);
        if (file != null) {
            try {
                IOUtils.writeBytes(file.toPath(), data);
                showInfo("保存成功", "文件已保存到: " + file.getAbsolutePath());
            } catch (Exception e) {
                showError("保存失败", e.getMessage());
            }
        }
    }

    private void copyBase64() {
        if (data != null) {
            String base64 = Base64.getEncoder().encodeToString(data);
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(base64);
            clipboard.setContent(content);
            showInfo("已复制", "Base64编码已复制到剪贴板");
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
