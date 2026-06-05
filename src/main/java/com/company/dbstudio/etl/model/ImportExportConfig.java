package com.company.dbstudio.etl.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportExportConfig {

    public enum Format {
        CSV("CSV", "csv", true),
        JSON("JSON", "json", true),
        EXCEL("Excel", "xlsx", false),
        PARQUET("Parquet", "parquet", false);

        private final String displayName;
        private final String extension;
        private final boolean textFormat;

        Format(String displayName, String extension, boolean textFormat) {
            this.displayName = displayName;
            this.extension = extension;
            this.textFormat = textFormat;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getExtension() {
            return extension;
        }

        public boolean isTextFormat() {
            return textFormat;
        }
    }

    public enum OperationType {
        EXPORT, IMPORT
    }

    public enum ValueTransform {
        NONE("无转换"),
        TO_UPPER("转为大写"),
        TO_LOWER("转为小写"),
        TRIM("去除首尾空格"),
        REPLACE_NULL("替换NULL为空字符串"),
        TO_DATE("转为日期"),
        TO_NUMBER("转为数字"),
        HASH_MD5("MD5哈希"),
        BASE64_ENCODE("Base64编码"),
        BASE64_DECODE("Base64解码"),
        CUSTOM("自定义表达式");

        private final String displayName;

        ValueTransform(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static class ColumnMapping {
        private String sourceColumn;
        private String targetColumn;
        private int sourceIndex;
        private int targetIndex;
        private boolean include;
        private ValueTransform transform;
        private String customExpression;
        private String defaultValue;

        public ColumnMapping() {
            this.include = true;
            this.transform = ValueTransform.NONE;
        }

        public ColumnMapping(String sourceColumn, String targetColumn) {
            this();
            this.sourceColumn = sourceColumn;
            this.targetColumn = targetColumn;
        }

        public String getSourceColumn() {
            return sourceColumn;
        }

        public void setSourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
        }

        public String getTargetColumn() {
            return targetColumn;
        }

        public void setTargetColumn(String targetColumn) {
            this.targetColumn = targetColumn;
        }

        public int getSourceIndex() {
            return sourceIndex;
        }

        public void setSourceIndex(int sourceIndex) {
            this.sourceIndex = sourceIndex;
        }

        public int getTargetIndex() {
            return targetIndex;
        }

        public void setTargetIndex(int targetIndex) {
            this.targetIndex = targetIndex;
        }

        public boolean isInclude() {
            return include;
        }

        public void setInclude(boolean include) {
            this.include = include;
        }

        public ValueTransform getTransform() {
            return transform;
        }

        public void setTransform(ValueTransform transform) {
            this.transform = transform;
        }

        public String getCustomExpression() {
            return customExpression;
        }

        public void setCustomExpression(String customExpression) {
            this.customExpression = customExpression;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    private OperationType operationType;
    private Format format;
    private String connectionId;
    private String sourceTable;
    private String sourceSchema;
    private String sourceQuery;
    private String targetTable;
    private String targetSchema;
    private String filePath;
    private boolean streaming;
    private int batchSize;
    private boolean includeHeader;
    private String encoding;
    private String csvDelimiter;
    private String csvQuoteChar;
    private String csvEscapeChar;
    private boolean excelMultipleSheets;
    private String excelSheetName;
    private boolean parquetCompressed;
    private String parquetCompressionCodec;
    private boolean jsonPrettyPrint;
    private boolean createTableIfNotExists;
    private boolean truncateBeforeInsert;
    private boolean useTransaction;
    private long commitInterval;
    private final List<ColumnMapping> columnMappings;
    private final Map<String, String> customParameters;

    public ImportExportConfig() {
        this.columnMappings = new ArrayList<>();
        this.customParameters = new LinkedHashMap<>();
        this.streaming = true;
        this.batchSize = 1000;
        this.includeHeader = true;
        this.encoding = "UTF-8";
        this.csvDelimiter = ",";
        this.csvQuoteChar = "\"";
        this.csvEscapeChar = "\\";
        this.excelSheetName = "Sheet1";
        this.excelMultipleSheets = false;
        this.parquetCompressed = true;
        this.parquetCompressionCodec = "SNAPPY";
        this.jsonPrettyPrint = true;
        this.createTableIfNotExists = false;
        this.truncateBeforeInsert = false;
        this.useTransaction = true;
        this.commitInterval = 10000;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public Format getFormat() {
        return format;
    }

    public void setFormat(Format format) {
        this.format = format;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceQuery() {
        return sourceQuery;
    }

    public void setSourceQuery(String sourceQuery) {
        this.sourceQuery = sourceQuery;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getTargetSchema() {
        return targetSchema;
    }

    public void setTargetSchema(String targetSchema) {
        this.targetSchema = targetSchema;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public boolean isIncludeHeader() {
        return includeHeader;
    }

    public void setIncludeHeader(boolean includeHeader) {
        this.includeHeader = includeHeader;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public String getCsvDelimiter() {
        return csvDelimiter;
    }

    public void setCsvDelimiter(String csvDelimiter) {
        this.csvDelimiter = csvDelimiter;
    }

    public String getCsvQuoteChar() {
        return csvQuoteChar;
    }

    public void setCsvQuoteChar(String csvQuoteChar) {
        this.csvQuoteChar = csvQuoteChar;
    }

    public String getCsvEscapeChar() {
        return csvEscapeChar;
    }

    public void setCsvEscapeChar(String csvEscapeChar) {
        this.csvEscapeChar = csvEscapeChar;
    }

    public boolean isExcelMultipleSheets() {
        return excelMultipleSheets;
    }

    public void setExcelMultipleSheets(boolean excelMultipleSheets) {
        this.excelMultipleSheets = excelMultipleSheets;
    }

    public String getExcelSheetName() {
        return excelSheetName;
    }

    public void setExcelSheetName(String excelSheetName) {
        this.excelSheetName = excelSheetName;
    }

    public boolean isParquetCompressed() {
        return parquetCompressed;
    }

    public void setParquetCompressed(boolean parquetCompressed) {
        this.parquetCompressed = parquetCompressed;
    }

    public String getParquetCompressionCodec() {
        return parquetCompressionCodec;
    }

    public void setParquetCompressionCodec(String parquetCompressionCodec) {
        this.parquetCompressionCodec = parquetCompressionCodec;
    }

    public boolean isJsonPrettyPrint() {
        return jsonPrettyPrint;
    }

    public void setJsonPrettyPrint(boolean jsonPrettyPrint) {
        this.jsonPrettyPrint = jsonPrettyPrint;
    }

    public boolean isCreateTableIfNotExists() {
        return createTableIfNotExists;
    }

    public void setCreateTableIfNotExists(boolean createTableIfNotExists) {
        this.createTableIfNotExists = createTableIfNotExists;
    }

    public boolean isTruncateBeforeInsert() {
        return truncateBeforeInsert;
    }

    public void setTruncateBeforeInsert(boolean truncateBeforeInsert) {
        this.truncateBeforeInsert = truncateBeforeInsert;
    }

    public boolean isUseTransaction() {
        return useTransaction;
    }

    public void setUseTransaction(boolean useTransaction) {
        this.useTransaction = useTransaction;
    }

    public long getCommitInterval() {
        return commitInterval;
    }

    public void setCommitInterval(long commitInterval) {
        this.commitInterval = commitInterval;
    }

    public List<ColumnMapping> getColumnMappings() {
        return columnMappings;
    }

    public void addColumnMapping(ColumnMapping mapping) {
        this.columnMappings.add(mapping);
    }

    public Map<String, String> getCustomParameters() {
        return customParameters;
    }

    public void setCustomParameter(String key, String value) {
        this.customParameters.put(key, value);
    }

    public String getFullSourceTableName() {
        if (sourceSchema != null && !sourceSchema.isEmpty()) {
            return sourceSchema + "." + sourceTable;
        }
        return sourceTable;
    }

    public String getFullTargetTableName() {
        if (targetSchema != null && !targetSchema.isEmpty()) {
            return targetSchema + "." + targetTable;
        }
        return targetTable;
    }

    public List<ColumnMapping> getIncludedMappings() {
        return columnMappings.stream()
                .filter(ColumnMapping::isInclude)
                .toList();
    }
}
