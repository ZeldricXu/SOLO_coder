package com.datamasker.domain.classification.scanner;

import com.datamasker.domain.classification.model.ClassificationRule;
import com.datamasker.domain.classification.model.DataField;
import com.datamasker.domain.classification.model.ScanResult;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DataScanner {

    private List<ClassificationRule> rules = new ArrayList<>();

    @PostConstruct
    public void initRules() {
        rules.add(createRule("R001", "ID_CARD", "PERSONAL", "^\\d{17}[\\dXx]$", "CONFIDENTIAL"));
        rules.add(createRule("R002", "PHONE", "PERSONAL", "^1[3-9]\\d{9}$", "CONFIDENTIAL"));
        rules.add(createRule("R003", "EMAIL", "PERSONAL", "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", "INTERNAL"));
        rules.add(createRule("R004", "BANK_CARD", "FINANCIAL", "^\\d{16,19}$", "SECRET"));
    }

    private ClassificationRule createRule(String ruleId, String name, String category, String pattern, String level) {
        ClassificationRule rule = new ClassificationRule();
        rule.setRuleId(ruleId);
        rule.setName(name);
        rule.setCategory(category);
        rule.setPattern(pattern);
        rule.setLevel(level);
        rule.setEnabled(true);
        return rule;
    }

    public DataField scanField(String dataSource, String fieldName, String fieldValue) {
        DataField dataField = new DataField();
        dataField.setDataSource(dataSource);
        dataField.setFieldName(fieldName);
        dataField.setFieldValue(fieldValue);
        dataField.setFieldType(inferFieldType(fieldValue));

        if (fieldValue == null || fieldValue.isBlank()) {
            dataField.setCategory("GENERAL");
            dataField.setLevel("PUBLIC");
            dataField.setConfidence(1.0);
            return dataField;
        }

        for (ClassificationRule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }
            if (Pattern.matches(rule.getPattern(), fieldValue)) {
                dataField.setCategory(rule.getCategory());
                dataField.setLevel(rule.getLevel());
                dataField.setConfidence(1.0);
                return dataField;
            }
        }

        if (isNameField(fieldName, fieldValue)) {
            dataField.setCategory("PERSONAL");
            dataField.setLevel("CONFIDENTIAL");
            dataField.setConfidence(0.7);
            return dataField;
        }

        if (isAddressField(fieldName)) {
            dataField.setCategory("LOCATION");
            dataField.setLevel("INTERNAL");
            dataField.setConfidence(0.7);
            return dataField;
        }

        dataField.setCategory("GENERAL");
        dataField.setLevel("PUBLIC");
        dataField.setConfidence(1.0);
        return dataField;
    }

    private boolean isNameField(String fieldName, String fieldValue) {
        String lower = fieldName.toLowerCase();
        if (lower.contains("name") || lower.contains("姓名")) {
            return Pattern.matches("^[\\u4e00-\\u9fa5]{2,4}$", fieldValue);
        }
        return false;
    }

    private boolean isAddressField(String fieldName) {
        String lower = fieldName.toLowerCase();
        return lower.contains("address") || lower.contains("地址");
    }

    private String inferFieldType(String value) {
        if (value == null) {
            return "STRING";
        }
        if (Pattern.matches("^1[3-9]\\d{9}$", value)) {
            return "PHONE";
        }
        if (Pattern.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", value)) {
            return "EMAIL";
        }
        if (Pattern.matches("^\\d{17}[\\dXx]$", value)) {
            return "ID_CARD";
        }
        if (Pattern.matches("^\\d{16,19}$", value)) {
            return "BANK_CARD";
        }
        if (Pattern.matches("^\\d{4}-\\d{2}-\\d{2}.*$", value)) {
            return "DATE";
        }
        if (Pattern.matches("^-?\\d+(\\.\\d+)?$", value)) {
            return "NUMBER";
        }
        return "STRING";
    }

    public ScanResult scanBatch(String dataSource, Map<String, String> fields) {
        List<DataField> results = new ArrayList<>();
        int sensitiveCount = 0;

        for (Map.Entry<String, String> entry : fields.entrySet()) {
            DataField classified = scanField(dataSource, entry.getKey(), entry.getValue());
            results.add(classified);
            if (!"PUBLIC".equals(classified.getLevel())) {
                sensitiveCount++;
            }
        }

        ScanResult scanResult = new ScanResult();
        scanResult.setDataSource(dataSource);
        scanResult.setTotalFields(fields.size());
        scanResult.setClassifiedFields(results.size());
        scanResult.setSensitiveFields(sensitiveCount);
        scanResult.setResults(results);
        scanResult.setScannedAt(LocalDateTime.now());
        return scanResult;
    }
}
