package com.company.dbstudio.ui.form;

import com.company.dbstudio.connection.config.DatabaseTypeConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.util.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DynamicFormGenerator {

    private final Map<String, Control> fieldControls = new HashMap<>();
    private final DatabaseTypeConfig.DbTypeInfo typeInfo;

    public DynamicFormGenerator(ConnectionType connectionType) {
        this.typeInfo = DatabaseTypeConfig.getInstance().getTypeInfo(connectionType);
    }

    public void setConnectionType(ConnectionType connectionType) {
        fieldControls.clear();
        DatabaseTypeConfig.DbTypeInfo newTypeInfo = DatabaseTypeConfig.getInstance().getTypeInfo(connectionType);
        if (newTypeInfo != null) {
            typeInfo.setType(newTypeInfo.getType());
            typeInfo.setDisplayName(newTypeInfo.getDisplayName());
            typeInfo.setDriverClass(newTypeInfo.getDriverClass());
            typeInfo.setDefaultPort(newTypeInfo.getDefaultPort());
            typeInfo.setJdbcUrlTemplate(newTypeInfo.getJdbcUrlTemplate());
            typeInfo.setRequiredFields(newTypeInfo.getRequiredFields());
            typeInfo.setOptionalFields(newTypeInfo.getOptionalFields());
            typeInfo.setJdbcProperties(newTypeInfo.getJdbcProperties());
            typeInfo.setDefaultValues(newTypeInfo.getDefaultValues());
        }
    }

    public int generateBasicFields(GridPane grid, int startRow) {
        int row = startRow;

        List<String> requiredFields = typeInfo != null ? typeInfo.getRequiredFields() 
            : List.of("host", "port", "database", "username");

        for (String field : requiredFields) {
            Control control = createFieldControl(field, true);
            fieldControls.put(field, control);

            String label = getFieldLabel(field);
            grid.add(new Label(label + " *:"), 0, row);
            grid.add(control, 1, row);
            row++;
        }

        List<String> optionalFields = typeInfo != null ? typeInfo.getOptionalFields() 
            : List.of("password");

        for (String field : optionalFields) {
            if (isPasswordField(field)) {
                Control control = createFieldControl(field, false);
                fieldControls.put(field, control);

                grid.add(new Label(getFieldLabel(field) + ":"), 0, row);
                grid.add(control, 1, row);
                row++;
                break;
            }
        }

        return row;
    }

    public int generateJdbcPropertiesFields(GridPane grid, int startRow) {
        int row = startRow;

        if (typeInfo == null || typeInfo.getJdbcProperties() == null) {
            return row;
        }

        grid.add(new Separator(), 0, row, 2, 1);
        row++;

        Label title = new Label("JDBC 属性");
        title.setStyle("-fx-font-weight: bold;");
        grid.add(title, 0, row, 2, 1);
        row++;

        for (String prop : typeInfo.getJdbcProperties()) {
            if (isPasswordField(prop) || isCommonField(prop)) {
                continue;
            }

            Control control = createPropertyControl(prop);
            fieldControls.put(prop, control);

            grid.add(new Label(getFieldLabel(prop) + ":"), 0, row);
            grid.add(control, 1, row);
            row++;
        }

        return row;
    }

    private Control createFieldControl(String field, boolean required) {
        String defaultValue = typeInfo != null ? typeInfo.getDefaultValue(field) : null;

        if (isPasswordField(field)) {
            PasswordField pf = new PasswordField();
            if (defaultValue != null) {
                pf.setText(defaultValue);
            }
            return pf;
        }

        if ("port".equals(field)) {
            TextField tf = new TextField();
            tf.setText(defaultValue != null ? defaultValue : 
                String.valueOf(typeInfo != null ? typeInfo.getDefaultPort() : 3306));
            tf.textProperty().addListener((obs, old, newVal) -> {
                if (!newVal.matches("\\d*")) {
                    tf.setText(newVal.replaceAll("[^\\d]", ""));
                }
            });
            return tf;
        }

        if (isBooleanProperty(field)) {
            ComboBox<Boolean> combo = new ComboBox<>();
            combo.getItems().addAll(true, false);
            combo.setValue(defaultValue != null ? Boolean.parseBoolean(defaultValue) : false);
            return combo;
        }

        if (isNumericProperty(field)) {
            Spinner<Integer> spinner = new Spinner<>(1, 300, 
                defaultValue != null ? Integer.parseInt(defaultValue) : 30);
            spinner.setEditable(true);
            return spinner;
        }

        TextField tf = new TextField();
        if (defaultValue != null) {
            tf.setText(defaultValue);
        } else if ("host".equals(field)) {
            tf.setText("127.0.0.1");
        }
        if (required) {
            tf.setPromptText("必填");
        }
        return tf;
    }

    private Control createPropertyControl(String property) {
        String defaultValue = typeInfo != null ? typeInfo.getDefaultValue(property) : null;

        if (isBooleanProperty(property)) {
            ComboBox<Boolean> combo = new ComboBox<>();
            combo.getItems().addAll(true, false);
            combo.setValue(defaultValue != null ? Boolean.parseBoolean(defaultValue) : false);
            return combo;
        }

        if (isNumericProperty(property)) {
            int defaultValueInt = defaultValue != null ? Integer.parseInt(defaultValue) : 30;
            Spinner<Integer> spinner = new Spinner<>(1, 300, defaultValueInt);
            spinner.setEditable(true);
            return spinner;
        }

        if ("protocol".equals(property)) {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("binary", "compact", "json");
            combo.setValue(defaultValue != null ? defaultValue : "binary");
            return combo;
        }

        if ("sslMode".equals(property)) {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("disable", "require", "verify-ca", "verify-full");
            combo.setValue(defaultValue != null ? defaultValue : "disable");
            return combo;
        }

        if ("stringtype".equals(property)) {
            ComboBox<String> combo = new ComboBox<>();
            combo.getItems().addAll("varchar", "unspecified");
            combo.setValue(defaultValue != null ? defaultValue : "varchar");
            return combo;
        }

        TextField tf = new TextField();
        if (defaultValue != null) {
            tf.setText(defaultValue);
        }
        return tf;
    }

    private String getFieldLabel(String field) {
        return switch (field) {
            case "host" -> "主机地址";
            case "port" -> "端口";
            case "database" -> "数据库名";
            case "service" -> "服务名";
            case "username" -> "用户名";
            case "password" -> "密码";
            case "useSSL" -> "使用SSL";
            case "serverTimezone" -> "服务器时区";
            case "allowPublicKeyRetrieval" -> "允许公钥获取";
            case "useUnicode" -> "使用Unicode";
            case "characterEncoding" -> "字符编码";
            case "sslMode" -> "SSL模式";
            case "currentSchema" -> "当前Schema";
            case "connectTimeout" -> "连接超时(秒)";
            case "networkTimeout" -> "网络超时(秒)";
            case "defaultRowPrefetch" -> "默认预取行数";
            case "oracle.jdbc.timezoneAsRegion" -> "时区作为区域";
            case "encrypt" -> "启用加密";
            case "trustServerCertificate" -> "信任服务器证书";
            case "loginTimeout" -> "登录超时(秒)";
            case "sendTimeAsDatetime" -> "时间作为DateTime";
            case "soTimeout" -> "Socket超时(秒)";
            case "protocol" -> "协议";
            case "groupName" -> "分组";
            default -> capitalize(field);
        };
    }

    private boolean isPasswordField(String field) {
        return "password".equals(field);
    }

    private boolean isCommonField(String field) {
        return List.of("host", "port", "database", "service", "username", "password", "databaseName")
                .contains(field);
    }

    private boolean isBooleanProperty(String property) {
        return List.of(
                "useSSL", "allowPublicKeyRetrieval", "useUnicode",
                "encrypt", "trustServerCertificate", "sendTimeAsDatetime",
                "oracle.jdbc.timezoneAsRegion"
        ).contains(property);
    }

    private boolean isNumericProperty(String property) {
        return List.of(
                "connectTimeout", "networkTimeout", "loginTimeout",
                "soTimeout", "defaultRowPrefetch"
        ).contains(property);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    public String getFieldValue(String field) {
        Control control = fieldControls.get(field);
        if (control == null) {
            return typeInfo != null ? typeInfo.getDefaultValue(field) : null;
        }

        if (control instanceof TextField tf) {
            return tf.getText().trim();
        } else if (control instanceof PasswordField pf) {
            return pf.getText();
        } else if (control instanceof ComboBox<?> cb) {
            Object value = cb.getValue();
            return value != null ? value.toString() : null;
        } else if (control instanceof Spinner<?> spinner) {
            Object value = spinner.getValue();
            return value != null ? value.toString() : null;
        }
        return null;
    }

    public int getPortValue() {
        String portStr = getFieldValue("port");
        if (portStr != null && !portStr.isEmpty()) {
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return typeInfo != null ? typeInfo.getDefaultPort() : 3306;
            }
        }
        return typeInfo != null ? typeInfo.getDefaultPort() : 3306;
    }

    public void setFieldValue(String field, String value) {
        Control control = fieldControls.get(field);
        if (control == null) {
            return;
        }

        if (control instanceof TextField tf) {
            tf.setText(value != null ? value : "");
        } else if (control instanceof PasswordField pf) {
            pf.setText(value != null ? value : "");
        } else if (control instanceof ComboBox cb) {
            if (isBooleanProperty(field)) {
                cb.setValue(value != null ? Boolean.parseBoolean(value) : false);
            } else {
                cb.setValue(value);
            }
        } else if (control instanceof Spinner spinner) {
            try {
                if (value != null) {
                    spinner.getValueFactory().setValue(Integer.parseInt(value));
                }
            } catch (NumberFormatException e) {
                // ignore
            }
        }
    }

    public void setPortValue(int port) {
        setFieldValue("port", String.valueOf(port));
    }

    public Map<String, String> collectJdbcProperties() {
        Map<String, String> props = new HashMap<>();
        if (typeInfo == null || typeInfo.getJdbcProperties() == null) {
            return props;
        }
        for (String prop : typeInfo.getJdbcProperties()) {
            if (isCommonField(prop)) {
                continue;
            }
            String value = getFieldValue(prop);
            if (value != null && !value.isEmpty()) {
                props.put(prop, value);
            }
        }
        return props;
    }

    public void applyJdbcProperties(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            setFieldValue(entry.getKey(), entry.getValue());
        }
    }

    public Pair<String, String> validate() {
        if (typeInfo == null || typeInfo.getRequiredFields() == null) {
            return null;
        }
        for (String field : typeInfo.getRequiredFields()) {
            String value = getFieldValue(field);
            if (value == null || value.trim().isEmpty()) {
                return new Pair<>(field, getFieldLabel(field) + " 不能为空");
            }
        }
        return null;
    }

    public DatabaseTypeConfig.DbTypeInfo getTypeInfo() {
        return typeInfo;
    }

    public Map<String, Control> getFieldControls() {
        return fieldControls;
    }
}
