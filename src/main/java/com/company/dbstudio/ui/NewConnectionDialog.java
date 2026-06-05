package com.company.dbstudio.ui;

import com.company.dbstudio.connection.ConnectionConfig;
import com.company.dbstudio.connection.ConnectionType;
import com.company.dbstudio.connection.model.PoolConfig;
import com.company.dbstudio.connection.model.SshConfig;
import com.company.dbstudio.connection.model.SslConfig;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.UUID;

public class NewConnectionDialog extends Dialog<ConnectionConfig> {

    private ConnectionConfig config;

    private TextField nameField;
    private ComboBox<ConnectionType> typeCombo;
    private TextField hostField;
    private TextField portField;
    private TextField databaseField;
    private TextField usernameField;
    private PasswordField passwordField;

    private CheckBox sshEnableCheck;
    private TextField sshHostField;
    private TextField sshPortField;
    private TextField sshUserField;
    private PasswordField sshPasswordField;

    private CheckBox sslEnableCheck;
    private CheckBox sslVerifyCheck;
    private TextField sslCertField;

    private Spinner<Integer> maxConnSpinner;
    private Spinner<Integer> minIdleSpinner;
    private Spinner<Integer> timeoutSpinner;

    private CheckBox favoriteCheck;
    private TextField groupField;

    public NewConnectionDialog() {
        this(null);
    }

    public NewConnectionDialog(ConnectionConfig existingConfig) {
        this.config = existingConfig;

        setTitle(existingConfig == null ? "新建连接" : "编辑连接");
        setHeaderText(existingConfig == null ? "配置数据库连接参数" : "修改数据库连接参数");

        DialogPane dialogPane = getDialogPane();
        dialogPane.getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TabPane tabPane = new TabPane();
        tabPane.getTabs().add(createBasicTab());
        tabPane.getTabs().add(createPoolTab());
        tabPane.getTabs().add(createSshTab());
        tabPane.getTabs().add(createSslTab());

        VBox content = new VBox(10);
        content.getChildren().add(tabPane);
        dialogPane.setContent(content);

        setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return collectConfig();
            }
            return null;
        });

        if (existingConfig != null) {
            populateFields(existingConfig);
        }
    }

    private Tab createBasicTab() {
        Tab tab = new Tab("基本信息");
        tab.setClosable(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(10));

        int row = 0;
        grid.add(new Label("连接名称:"), 0, row);
        nameField = new TextField();
        nameField.setPromptText("输入连接显示名称");
        grid.add(nameField, 1, row);

        row++;
        grid.add(new Label("分组:"), 0, row);
        groupField = new TextField();
        groupField.setPromptText("可选，用于分类管理");
        grid.add(groupField, 1, row);

        row++;
        grid.add(new Label("收藏:"), 0, row);
        favoriteCheck = new CheckBox("标记为收藏");
        grid.add(favoriteCheck, 1, row);

        row++;
        grid.add(new Label("数据库类型:"), 0, row);
        typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll(
                ConnectionType.MYSQL,
                ConnectionType.POSTGRESQL,
                ConnectionType.ORACLE,
                ConnectionType.SQL_SERVER,
                ConnectionType.THRIFT
        );
        typeCombo.setValue(ConnectionType.MYSQL);
        typeCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateDefaultPort(newVal));
        grid.add(typeCombo, 1, row);

        row++;
        grid.add(new Label("主机地址:"), 0, row);
        hostField = new TextField("127.0.0.1");
        grid.add(hostField, 1, row);

        row++;
        grid.add(new Label("端口:"), 0, row);
        portField = new TextField("3306");
        grid.add(portField, 1, row);

        row++;
        grid.add(new Label("数据库名:"), 0, row);
        databaseField = new TextField();
        databaseField.setPromptText("可选");
        grid.add(databaseField, 1, row);

        row++;
        grid.add(new Label("用户名:"), 0, row);
        usernameField = new TextField();
        grid.add(usernameField, 1, row);

        row++;
        grid.add(new Label("密码:"), 0, row);
        passwordField = new PasswordField();
        grid.add(passwordField, 1, row);

        Button testBtn = new Button("测试连接");
        testBtn.setOnAction(e -> testConnection());
        grid.add(testBtn, 1, row + 1);

        tab.setContent(grid);
        return tab;
    }

    private Tab createPoolTab() {
        Tab tab = new Tab("连接池");
        tab.setClosable(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new javafx.geometry.Insets(10));

        int row = 0;
        grid.add(new Label("最大连接数:"), 0, row);
        maxConnSpinner = new Spinner<>(1, 100, 10);
        maxConnSpinner.setEditable(true);
        grid.add(maxConnSpinner, 1, row);

        row++;
        grid.add(new Label("最小空闲连接:"), 0, row);
        minIdleSpinner = new Spinner<>(0, 50, 5);
        minIdleSpinner.setEditable(true);
        grid.add(minIdleSpinner, 1, row);

        row++;
        grid.add(new Label("连接超时(秒):"), 0, row);
        timeoutSpinner = new Spinner<>(1, 300, 30);
        timeoutSpinner.setEditable(true);
        grid.add(timeoutSpinner, 1, row);

        row++;
        Label desc = new Label("连接池参数用于优化性能。默认值适用于大多数场景。");
        desc.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        grid.add(desc, 0, row, 2, 1);

        tab.setContent(grid);
        return tab;
    }

    private Tab createSshTab() {
        Tab tab = new Tab("SSH隧道");
        tab.setClosable(false);

        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));

        sshEnableCheck = new CheckBox("启用SSH隧道");
        content.getChildren().add(sshEnableCheck);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        int row = 0;
        grid.add(new Label("SSH主机:"), 0, row);
        sshHostField = new TextField();
        sshHostField.disableProperty().bind(sshEnableCheck.selectedProperty().not());
        grid.add(sshHostField, 1, row);

        row++;
        grid.add(new Label("SSH端口:"), 0, row);
        sshPortField = new TextField("22");
        sshPortField.disableProperty().bind(sshEnableCheck.selectedProperty().not());
        grid.add(sshPortField, 1, row);

        row++;
        grid.add(new Label("SSH用户名:"), 0, row);
        sshUserField = new TextField();
        sshUserField.disableProperty().bind(sshEnableCheck.selectedProperty().not());
        grid.add(sshUserField, 1, row);

        row++;
        grid.add(new Label("SSH密码:"), 0, row);
        sshPasswordField = new PasswordField();
        sshPasswordField.disableProperty().bind(sshEnableCheck.selectedProperty().not());
        grid.add(sshPasswordField, 1, row);

        content.getChildren().add(grid);

        Label hint = new Label("SSH隧道用于安全访问内部数据库，通过SSH服务器建立加密通道。");
        hint.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        content.getChildren().add(hint);

        tab.setContent(content);
        return tab;
    }

    private Tab createSslTab() {
        Tab tab = new Tab("SSL安全");
        tab.setClosable(false);

        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(10));

        sslEnableCheck = new CheckBox("启用SSL连接");
        content.getChildren().add(sslEnableCheck);

        sslVerifyCheck = new CheckBox("验证服务器证书");
        sslVerifyCheck.disableProperty().bind(sslEnableCheck.selectedProperty().not());
        content.getChildren().add(sslVerifyCheck);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        grid.add(new Label("SSL证书路径:"), 0, 0);
        sslCertField = new TextField();
        sslCertField.disableProperty().bind(sslEnableCheck.selectedProperty().not());
        sslCertField.setPromptText("可选，证书文件路径");
        grid.add(sslCertField, 1, 0);

        Button browseBtn = new Button("浏览...");
        browseBtn.disableProperty().bind(sslEnableCheck.selectedProperty().not());
        grid.add(browseBtn, 2, 0);

        content.getChildren().add(grid);

        Label hint = new Label("SSL加密确保数据在网络传输中的安全性。建议在生产环境启用。");
        hint.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");
        content.getChildren().add(hint);

        tab.setContent(content);
        return tab;
    }

    private void updateDefaultPort(ConnectionType type) {
        portField.setText(type.getDefaultPort());
    }

    private void populateFields(ConnectionConfig existing) {
        nameField.setText(existing.getName());
        typeCombo.setValue(existing.getType());
        hostField.setText(existing.getHost());
        portField.setText(String.valueOf(existing.getPort()));
        databaseField.setText(existing.getDatabase());
        usernameField.setText(existing.getUsername());
        passwordField.setText(existing.getPassword());
        groupField.setText(existing.getGroup());
        favoriteCheck.setSelected(existing.isFavorite());

        if (existing.getPoolConfig() != null) {
            maxConnSpinner.getValueFactory().setValue(existing.getPoolConfig().getMaxPoolSize());
            minIdleSpinner.getValueFactory().setValue(existing.getPoolConfig().getMinimumIdle());
            timeoutSpinner.getValueFactory().setValue((int) (existing.getPoolConfig().getConnectionTimeout() / 1000));
        }

        if (existing.getSshConfig() != null && existing.getSshConfig().isEnabled()) {
            sshEnableCheck.setSelected(true);
            sshHostField.setText(existing.getSshConfig().getHost());
            sshPortField.setText(String.valueOf(existing.getSshConfig().getPort()));
            sshUserField.setText(existing.getSshConfig().getUsername());
            sshPasswordField.setText(existing.getSshConfig().getPassword());
        }

        if (existing.getSslConfig() != null && existing.getSslConfig().isEnabled()) {
            sslEnableCheck.setSelected(true);
            sslVerifyCheck.setSelected(existing.getSslConfig().isVerifyServerCertificate());
            sslCertField.setText(existing.getSslConfig().getCertificatePath());
        }
    }

    private ConnectionConfig collectConfig() {
        if (config == null) {
            config = new ConnectionConfig();
            config.setId(UUID.randomUUID().toString());
        }

        config.setName(nameField.getText().trim());
        config.setType(typeCombo.getValue());
        config.setHost(hostField.getText().trim());
        try {
            config.setPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            config.setPort(typeCombo.getValue().getDefaultPortInt());
        }
        config.setDatabase(databaseField.getText().trim());
        config.setUsername(usernameField.getText().trim());
        config.setPassword(passwordField.getText());
        config.setGroup(groupField.getText().trim());
        config.setFavorite(favoriteCheck.isSelected());

        PoolConfig poolConfig = new PoolConfig();
        poolConfig.setMaxPoolSize(maxConnSpinner.getValue());
        poolConfig.setMinimumIdle(minIdleSpinner.getValue());
        poolConfig.setConnectionTimeout((long) timeoutSpinner.getValue() * 1000);
        poolConfig.setIdleTimeout(600000);
        poolConfig.setMaxLifetime(1800000);
        config.setPoolConfig(poolConfig);

        SshConfig sshConfig = new SshConfig();
        sshConfig.setEnabled(sshEnableCheck.isSelected());
        if (sshConfig.isEnabled()) {
            sshConfig.setHost(sshHostField.getText().trim());
            try {
                sshConfig.setPort(Integer.parseInt(sshPortField.getText().trim()));
            } catch (NumberFormatException e) {
                sshConfig.setPort(22);
            }
            sshConfig.setUsername(sshUserField.getText().trim());
            sshConfig.setPassword(sshPasswordField.getText());
        }
        config.setSshConfig(sshConfig);

        SslConfig sslConfig = new SslConfig();
        sslConfig.setEnabled(sslEnableCheck.isSelected());
        if (sslConfig.isEnabled()) {
            sslConfig.setVerifyServerCertificate(sslVerifyCheck.isSelected());
            sslConfig.setCertificatePath(sslCertField.getText().trim());
        }
        config.setSslConfig(sslConfig);

        return config;
    }

    private void testConnection() {
        ConnectionConfig testConfig = collectConfig();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("连接测试");
        alert.setHeaderText(null);
        alert.setContentText("连接测试功能将在实际运行时验证数据库连接。\n\n配置预览:\n" +
                "类型: " + testConfig.getType().getDisplayName() + "\n" +
                "地址: " + testConfig.getHost() + ":" + testConfig.getPort() + "\n" +
                "用户: " + testConfig.getUsername());
        alert.showAndWait();
    }
}
