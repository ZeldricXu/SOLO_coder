package com.apishield.classification.scanner.impl;

import com.apishield.classification.domain.DataClassification;
import com.apishield.classification.domain.ScanJob;
import com.apishield.classification.scanner.DataScanner;
import com.apishield.domain.vo.SecurityLevel;
import com.apishield.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RegexDataScanner implements DataScanner {

    private static final Map<String, Pattern> SENSITIVE_PATTERNS = new HashMap<>();
    
    static {
        SENSITIVE_PATTERNS.put("ID_CARD", Pattern.compile("\\d{17}[\\dXx]"));
        SENSITIVE_PATTERNS.put("PHONE", Pattern.compile("1[3-9]\\d{9}"));
        SENSITIVE_PATTERNS.put("EMAIL", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"));
        SENSITIVE_PATTERNS.put("BANK_CARD", Pattern.compile("\\d{16,19}"));
        SENSITIVE_PATTERNS.put("ADDRESS", Pattern.compile(".*(省|市|区|街|路|巷|号).*"));
    }

    @Override
    public String getScannerType() {
        return "REGEX";
    }

    @Override
    public List<DataClassification> scan(ScanJob job) {
        log.info("Starting regex scan for job: {}", job.getJobId());
        List<DataClassification> results = new ArrayList<>();

        List<String> sampleColumns = Arrays.asList("id_card", "phone", "email", "bank_card", "address", "username", "age", "created_at");
        
        for (String column : sampleColumns) {
            DataClassification classification = classifyColumn(job, column);
            if (classification != null) {
                results.add(classification);
            }
        }

        log.info("Regex scan completed for job: {}, found {} sensitive columns", job.getJobId(), results.size());
        return results;
    }

    @Override
    public boolean supports(String dataSource) {
        return true;
    }

    private DataClassification classifyColumn(ScanJob job, String columnName) {
        String lowerColumn = columnName.toLowerCase();
        
        for (Map.Entry<String, Pattern> entry : SENSITIVE_PATTERNS.entrySet()) {
            String category = entry.getKey();
            Pattern pattern = entry.getValue();
            
            if (lowerColumn.contains(category.toLowerCase().replace("_", "")) ||
                lowerColumn.contains(category.toLowerCase())) {
                
                DataClassification dc = new DataClassification();
                dc.setId(IdGenerator.generateId("dc"));
                dc.setClassificationId(dc.getId());
                dc.setDataSource(job.getDataSource());
                dc.setTableName(job.getTables().isEmpty() ? "sample_table" : job.getTables().get(0));
                dc.setColumnName(columnName);
                dc.setDataType("VARCHAR");
                dc.setDataCategory(category);
                dc.setSecurityLevel(getSecurityLevel(category));
                dc.setSensitivePattern(pattern.pattern());
                dc.setConfidenceScore(0.85);
                dc.setScanJobId(job.getJobId());
                dc.setScannedAt(LocalDateTime.now());
                dc.setStatus("CLASSIFIED");
                dc.setCreatedAt(LocalDateTime.now());
                dc.setUpdatedAt(LocalDateTime.now());
                
                return dc;
            }
        }
        
        return null;
    }

    private SecurityLevel getSecurityLevel(String category) {
        switch (category) {
            case "ID_CARD":
            case "BANK_CARD":
                return SecurityLevel.SECRET;
            case "PHONE":
            case "ADDRESS":
                return SecurityLevel.CONFIDENTIAL;
            case "EMAIL":
                return SecurityLevel.INTERNAL;
            default:
                return SecurityLevel.PUBLIC;
        }
    }
}
