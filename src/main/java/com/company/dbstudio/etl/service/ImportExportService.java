package com.company.dbstudio.etl.service;

import com.company.dbstudio.connection.datasource.DataSourceRegistry;
import com.company.dbstudio.core.ApplicationContext;
import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.core.util.JsonUtils;
import com.company.dbstudio.etl.exporter.AbstractExporter;
import com.company.dbstudio.etl.exporter.ExporterFactory;
import com.company.dbstudio.etl.model.Format;
import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ImportExportConfig.*;
import com.company.dbstudio.etl.model.ProgressInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.application.Platform;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ImportExportService {

    private final DataSourceRegistry dataSourceRegistry;
    private final AtomicBoolean cancelled;
    private AbstractExporter currentExporter;

    public ImportExportService() {
        this.dataSourceRegistry = ApplicationContext.getBean(DataSourceRegistry.class);
        this.cancelled = new AtomicBoolean(false);
    }

    public void cancel() {
        cancelled.set(true);
        if (currentExporter != null) {
            currentExporter.cancel();
        }
    }

    public enum ColumnType {
        INTEGER, LONG, FLOAT, DOUBLE, DECIMAL, BOOLEAN, DATE, TIMESTAMP, STRING, UNKNOWN;

        public boolean isNumeric() {
            return this == INTEGER || this == LONG || this == FLOAT || this == DOUBLE || this == DECIMAL;
        }

        public boolean isTemporal() {
            return this == DATE || this == TIMESTAMP;
        }

        public boolean isBoolean() {
            return this == BOOLEAN;
        }
    }

    public ColumnType inferColumnType(String value) {
        if (value == null || value.trim().isEmpty() || "NULL".equalsIgnoreCase(value.trim())) {
            return ColumnType.UNKNOWN;
        }

        String trimmed = value.trim();

        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)
                || "1".equals(trimmed) || "0".equals(trimmed)) {
            return ColumnType.BOOLEAN;
        }

        try {
            Integer.parseInt(trimmed);
            return ColumnType.INTEGER;
        } catch (NumberFormatException e) {
        }

        try {
            Long.parseLong(trimmed);
            return ColumnType.LONG;
        } catch (NumberFormatException e) {
        }

        try {
            Float.parseFloat(trimmed);
            return ColumnType.FLOAT;
        } catch (NumberFormatException e) {
        }

        try {
            Double.parseDouble(trimmed);
            return ColumnType.DOUBLE;
        } catch (NumberFormatException e) {
        }

        if (trimmed.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            return ColumnType.DATE;
        }

        if (trimmed.matches("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*")) {
            return ColumnType.TIMESTAMP;
        }

        if (trimmed.matches("^\\d{4}/\\d{2}/\\d{2}$")) {
            return ColumnType.DATE;
        }

        return ColumnType.STRING;
    }

    public List<ColumnType> inferColumnTypes(List<String[]> sampleRows, boolean hasHeader) {
        if (sampleRows == null || sampleRows.isEmpty()) {
            return List.of();
        }

        int startIndex = hasHeader ? 1 : 0;
        if (sampleRows.size() <= startIndex) {
            return List.of();
        }

        int columnCount = sampleRows.get(startIndex).length;
        List<ColumnType> types = new ArrayList<>();

        for (int col = 0; col < columnCount; col++) {
            ColumnType detectedType = ColumnType.UNKNOWN;
            for (int row = startIndex; row < Math.min(sampleRows.size(), startIndex + 100); row++) {
                String[] rowData = sampleRows.get(row);
                if (col < rowData.length) {
                    ColumnType cellType = inferColumnType(rowData[col]);
                    if (cellType != ColumnType.UNKNOWN) {
                        if (detectedType == ColumnType.UNKNOWN) {
                            detectedType = cellType;
                        } else if (detectedType != cellType) {
                            if ((detectedType == ColumnType.INTEGER && cellType == ColumnType.LONG) ||
                                (detectedType == ColumnType.LONG && cellType == ColumnType.INTEGER)) {
                                detectedType = ColumnType.LONG;
                            } else if ((detectedType == ColumnType.FLOAT && cellType == ColumnType.DOUBLE) ||
                                       (detectedType == ColumnType.DOUBLE && cellType == ColumnType.FLOAT)) {
                                detectedType = ColumnType.DOUBLE;
                            } else if (detectedType.isNumeric() && cellType.isNumeric()) {
                                detectedType = ColumnType.DOUBLE;
                            } else {
                                detectedType = ColumnType.STRING;
                                break;
                            }
                        }
                    }
                }
            }
            types.add(detectedType);
        }

        return types;
    }

    public java.nio.charset.Charset detectEncoding(String filePath) {
        return detectEncoding(new File(filePath));
    }

    public java.nio.charset.Charset detectEncoding(File file) {
        byte[] bom = new byte[4];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int read = fis.read(bom);
            if (read >= 3 && bom[0] == (byte) 0xEF && bom[1] == (byte) 0xBB && bom[2] == (byte) 0xBF) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
            if (read >= 2 && bom[0] == (byte) 0xFF && bom[1] == (byte) 0xFE) {
                return java.nio.charset.StandardCharsets.UTF_16LE;
            }
            if (read >= 2 && bom[0] == (byte) 0xFE && bom[1] == (byte) 0xFF) {
                return java.nio.charset.StandardCharsets.UTF_16BE;
            }
        } catch (Exception e) {
        }

        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            if (isUTF8(content)) {
                return java.nio.charset.StandardCharsets.UTF_8;
            }
        } catch (Exception e) {
        }

        try {
            return java.nio.charset.Charset.forName("GBK");
        } catch (Exception e) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    private boolean isUTF8(byte[] data) {
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
            } else if (b < 0xC0) {
                return false;
            } else if (b < 0xE0) {
                if (i + 1 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if (b < 0xF0) {
                if (i + 2 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                if ((data[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if (b < 0xF8) {
                if (i + 3 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                if ((data[i + 2] & 0xC0) != 0x80) return false;
                if ((data[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false;
            }
        }
        return true;
    }

    public Result<Long> exportData(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) {
        cancelled.set(false);
        currentExporter = ExporterFactory.createExporter(config.getFormat());
        return currentExporter.export(config, progressCallback);
    }

    public void exportDataAsync(ImportExportConfig config,
                          Consumer<ProgressInfo> progressCallback,
                          Consumer<Result<Long>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<Long> result = exportData(config, progressCallback);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    public Result<Long> importData(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) {
        cancelled.set(false);
        try {
            return switch (config.getFormat()) {
                case CSV -> importFromCsv(config, progressCallback);
                case JSON -> importFromJson(config, progressCallback);
                case EXCEL -> importFromExcel(config, progressCallback);
                case PARQUET -> importFromParquet(config, progressCallback);
            };
        } catch (Exception e) {
            return Result.failure("导入失败: " + e.getMessage());
        }
    }

    public void importDataAsync(ImportExportConfig config,
                          Consumer<ProgressInfo> progressCallback,
                          Consumer<Result<Long>> callback) {
        ApplicationContext.executeAsync(() -> {
            Result<Long> result = importData(config, progressCallback);
            Platform.runLater(() -> callback.accept(result));
        });
    }

    private Result<Long> importFromCsv(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) throws Exception {
        AtomicLong rowCount = new AtomicLong(0);
        Charset charset = Charset.forName(config.getEncoding());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(config.getFilePath()), charset));
             Connection conn = dataSourceRegistry.getConnection(config.getConnectionId())) {

            if (config.isUseTransaction()) {
                conn.setAutoCommit(false);
            }

            String line;
            boolean firstLine = true;
            String[] headers = null;

            String insertSql = buildInsertSql(config);
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

                while ((line = reader.readLine()) != null) {
                    if (cancelled.get()) break;
                    if (line.trim().isEmpty()) continue;

                    if (firstLine && config.isIncludeHeader()) {
                        headers = parseCsvLine(line, config);
                        firstLine = false;
                        buildColumnMappingsFromHeaders(config, headers);
                        continue;
                    }

                    String[] values = parseCsvLine(line, config);
                    setPreparedStatementValues(pstmt, config, values);
                    pstmt.addBatch();

                    long count = rowCount.incrementAndGet();
                    if (count % config.getBatchSize() == 0) {
                        pstmt.executeBatch();
                        if (config.isUseTransaction() && count % config.getCommitInterval() == 0) {
                            conn.commit();
                        }
                        reportProgress(progressCallback, count, -1, "已导入 " + count + " 行");
                    }
                }

                pstmt.executeBatch();
                if (config.isUseTransaction()) {
                    conn.commit();
                }
            } finally {
                if (config.isUseTransaction()) {
                    conn.setAutoCommit(true);
                }
            }

            reportProgress(progressCallback, rowCount.get(), rowCount.get(), "导入完成，共 " + rowCount.get() + " 行");
            return Result.success(rowCount.get());
        }
    }

    private Result<Long> importFromJson(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) throws Exception {
        AtomicLong rowCount = new AtomicLong(0);

        try (Connection conn = dataSourceRegistry.getConnection(config.getConnectionId())) {

            if (config.isUseTransaction()) {
                conn.setAutoCommit(false);
            }

            JsonNode rootNode = JsonUtils.fromJson(new File(config.getFilePath()), JsonNode.class);
            if (!rootNode.isArray()) {
                return Result.failure("JSON文件格式错误，应为数组格式");
            }

            ArrayNode arrayNode = (ArrayNode) rootNode;
            String insertSql = buildInsertSql(config);

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

                for (JsonNode node : arrayNode) {
                    if (cancelled.get()) break;
                    if (!node.isObject()) continue;

                    if (config.getColumnMappings().isEmpty()) {
                        buildColumnMappingsFromJson(config, node);
                    }

                    setPreparedStatementValuesFromJson(pstmt, config, node);
                    pstmt.addBatch();

                    long count = rowCount.incrementAndGet();
                    if (count % config.getBatchSize() == 0) {
                        pstmt.executeBatch();
                        if (config.isUseTransaction() && count % config.getCommitInterval() == 0) {
                            conn.commit();
                        }
                        reportProgress(progressCallback, count, arrayNode.size(), "已导入 " + count + " 行");
                    }
                }

                pstmt.executeBatch();
                if (config.isUseTransaction()) {
                    conn.commit();
                }
            } finally {
                if (config.isUseTransaction()) {
                    conn.setAutoCommit(true);
                }
            }

            reportProgress(progressCallback, rowCount.get(), rowCount.get(), "导入完成，共 " + rowCount.get() + " 行");
            return Result.success(rowCount.get());
        }
    }

    private Result<Long> importFromExcel(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) throws Exception {
        AtomicLong rowCount = new AtomicLong(0);

        try (Workbook workbook = new XSSFWorkbook(config.getFilePath());
             Connection conn = dataSourceRegistry.getConnection(config.getConnectionId())) {

            if (config.isUseTransaction()) {
                conn.setAutoCommit(false);
            }

            Sheet sheet = workbook.getSheet(config.getExcelSheetName());
            if (sheet == null) {
                sheet = workbook.getSheetAt(0);
            }

            Iterator<Row> rowIterator = sheet.iterator();
            boolean firstRow = true;

            String insertSql = buildInsertSql(config);
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

                while (rowIterator.hasNext()) {
                    Row row = rowIterator.next();
                    if (cancelled.get()) break;

                    if (firstRow && config.isIncludeHeader()) {
                        String[] headers = new String[row.getLastCellNum()];
                        for (int i = 0; i < row.getLastCellNum(); i++) {
                            Cell cell = row.getCell(i);
                            headers[i] = getCellValueAsString(cell);
                        }
                        buildColumnMappingsFromHeaders(config, headers);
                        firstRow = false;
                        continue;
                    }

                    Object[] values = new Object[row.getLastCellNum()];
                    for (int i = 0; i < row.getLastCellNum(); i++) {
                        values[i] = getCellValue(row.getCell(i));
                    }

                    setPreparedStatementValues(pstmt, config, values);
                    pstmt.addBatch();

                    long count = rowCount.incrementAndGet();
                    if (count % config.getBatchSize() == 0) {
                        pstmt.executeBatch();
                        if (config.isUseTransaction() && count % config.getCommitInterval() == 0) {
                            conn.commit();
                        }
                        reportProgress(progressCallback, count, -1, "已导入 " + count + " 行");
                    }
                }

                pstmt.executeBatch();
                if (config.isUseTransaction()) {
                    conn.commit();
                }
            } finally {
                if (config.isUseTransaction()) {
                    conn.setAutoCommit(true);
                }
            }

            reportProgress(progressCallback, rowCount.get(), rowCount.get(), "导入完成，共 " + rowCount.get() + " 行");
            return Result.success(rowCount.get());
        }
    }

    private Result<Long> importFromParquet(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) throws Exception {
        return Result.failure("Parquet格式暂不支持");
    }

    private Statement createStatement(Connection conn, ImportExportConfig config) throws SQLException {
        Statement stmt = conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY);
        stmt.setFetchSize(config.getBatchSize());
        return stmt;
    }

    private ResultSet executeQuery(Statement stmt, ImportExportConfig config) throws SQLException {
        String sql = config.getSourceQuery();
        if (sql == null || sql.isEmpty()) {
            sql = "SELECT * FROM " + config.getFullSourceTableName();
        }
        return stmt.executeQuery(sql);
    }

    private List<ColumnMapping> buildColumnMappings(ImportExportConfig config, ResultSetMetaData metaData)
            throws SQLException {
        if (config.getColumnMappings().isEmpty()) {
            for (int i = 1; i <= metaData.getColumnCount(); i++) {
                ColumnMapping mapping = new ColumnMapping();
                mapping.setSourceColumn(metaData.getColumnName(i));
                mapping.setTargetColumn(metaData.getColumnName(i));
                mapping.setSourceIndex(i - 1);
                mapping.setTargetIndex(i - 1);
                config.addColumnMapping(mapping);
            }
        }
        return config.getColumnMappings();
    }

    private void buildColumnMappingsFromHeaders(ImportExportConfig config, String[] headers) {
        if (config.getColumnMappings().isEmpty()) {
            for (int i = 0; i < headers.length; i++) {
                ColumnMapping mapping = new ColumnMapping();
                mapping.setSourceColumn(headers[i]);
                mapping.setTargetColumn(headers[i]);
                mapping.setSourceIndex(i);
                mapping.setTargetIndex(i);
                config.addColumnMapping(mapping);
            }
        }
    }

    private void buildColumnMappingsFromJson(ImportExportConfig config, JsonNode node) {
        if (config.getColumnMappings().isEmpty()) {
            Iterator<String> fieldNames = node.fieldNames();
            int index = 0;
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                ColumnMapping mapping = new ColumnMapping();
                mapping.setSourceColumn(fieldName);
                mapping.setTargetColumn(fieldName);
                mapping.setSourceIndex(index);
                mapping.setTargetIndex(index);
                config.addColumnMapping(mapping);
                index++;
            }
        }
    }

    private Object applyTransform(Object value, ColumnMapping mapping) {
        if (value == null) {
            if (mapping.getDefaultValue() != null) {
                return mapping.getDefaultValue();
            }
            return null;
        }

        return switch (mapping.getTransform()) {
            case NONE -> value;
            case TO_UPPER -> value.toString().toUpperCase();
            case TO_LOWER -> value.toString().toLowerCase();
            case TRIM -> value.toString().trim();
            case REPLACE_NULL -> value != null ? value : "";
            case TO_DATE -> {
                try {
                    if (value instanceof java.sql.Date d) yield d.toLocalDate();
                    if (value instanceof String s) yield LocalDate.parse(s);
                } catch (Exception e) {
                    yield value;
                }
                yield value;
            }
            case TO_NUMBER -> {
                try {
                    yield new BigDecimal(value.toString());
                } catch (Exception e) {
                    yield value;
                }
            }
            case HASH_MD5 -> {
                try {
                    MessageDigest md = MessageDigest.getInstance("MD5");
                    byte[] hash = md.digest(value.toString().getBytes(StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hash) sb.append(String.format("%02x", b));
                    yield sb.toString();
                } catch (Exception e) {
                    yield value;
                }
            }
            case BASE64_ENCODE -> Base64.getEncoder().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));
            case BASE64_DECODE -> new String(Base64.getDecoder().decode(value.toString()), StandardCharsets.UTF_8);
            case CUSTOM -> {
                if (mapping.getCustomExpression() != null) {
                    yield evaluateCustomExpression(value, mapping.getCustomExpression());
                }
                yield value;
            }
        };
    }

    private Object evaluateCustomExpression(Object value, String expression) {
        return expression.replace("${value}", value != null ? value.toString() : "");
    }

    private String formatCsvValue(Object value, ColumnMapping mapping, ImportExportConfig config) {
        if (value == null) return "";
        String strValue = value.toString();
        if (strValue.contains(config.getCsvDelimiter()) ||
            strValue.contains(config.getCsvQuoteChar()) ||
            strValue.contains("\n") ||
            strValue.contains("\r")) {
            return config.getCsvQuoteChar() + strValue + config.getCsvQuoteChar();
        }
        return strValue;
    }

    private String escapeCsvField(String field) {
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }

    private String[] parseCsvLine(String line, ImportExportConfig config) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        String delimiter = config.getCsvDelimiter();
        String quote = config.getCsvQuoteChar();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quote.charAt(0) && i + 1 < line.length() && line.charAt(i + 1) == quote.charAt(0)) {
                    current.append(c);
                    i++;
                } else if (c == quote.charAt(0)) {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == delimiter.charAt(0)) {
                    values.add(current.toString());
                    current.setLength(0);
                } else if (c == quote.charAt(0)) {
                    inQuotes = true;
                } else {
                    current.append(c);
                }
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    private void setJsonNodeValue(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof String s) {
            node.put(field, s);
        } else if (value instanceof Integer i) {
            node.put(field, i);
        } else if (value instanceof Long l) {
            node.put(field, l);
        } else if (value instanceof Float f) {
            node.put(field, f);
        } else if (value instanceof Double d) {
            node.put(field, d);
        } else if (value instanceof Boolean b) {
            node.put(field, b);
        } else if (value instanceof BigDecimal bd) {
            node.put(field, bd);
        } else if (value instanceof LocalDate ld) {
            node.put(field, ld.toString());
        } else if (value instanceof LocalDateTime ldt) {
            node.put(field, ldt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else if (value instanceof java.sql.Date d) {
            node.put(field, d.toLocalDate().toString());
        } else if (value instanceof java.sql.Timestamp t) {
            node.put(field, t.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } else {
            node.put(field, value.toString());
        }
    }

    private void setExcelCellValue(Cell cell, Object value, ColumnMapping mapping, CellStyle dateStyle) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof String s) {
            cell.setCellValue(s);
        } else if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (value instanceof Boolean b) {
            cell.setCellValue(b);
        } else if (value instanceof LocalDate ld) {
            cell.setCellValue(ld);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof LocalDateTime ldt) {
            cell.setCellValue(ldt);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof java.sql.Date d) {
            cell.setCellValue(d.toLocalDate());
            cell.setCellStyle(dateStyle);
        } else if (value instanceof java.sql.Timestamp t) {
            cell.setCellValue(t.toLocalDateTime());
            cell.setCellStyle(dateStyle);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private Object getCellValue(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue();
                }
                yield cell.getNumericCellValue();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> cell.getCellFormula();
            default -> null;
        };
    }

    private String getCellValueAsString(Cell cell) {
        Object value = getCellValue(cell);
        return value != null ? value.toString() : "";
    }

    private String buildInsertSql(ImportExportConfig config) {
        List<ColumnMapping> included = config.getIncludedMappings();
        StringBuilder sql = new StringBuilder("INSERT INTO " + config.getFullTargetTableName() + " (";
        sql.append(included.stream()
                .map(ColumnMapping::getTargetColumn)
                .collect(Collectors.joining(", ")));
        sql.append(") VALUES (");
        sql.append(included.stream()
                .map(m -> "?")
                .collect(Collectors.joining(", ")));
        sql.append(")");
        return sql.toString();
    }

    private void setPreparedStatementValues(PreparedStatement pstmt, ImportExportConfig config, Object[] values) throws SQLException {
        List<ColumnMapping> included = config.getIncludedMappings();
        for (int i = 0; i < included.size(); i++) {
            ColumnMapping mapping = included.get(i);
            int paramIndex = i + 1;
            Object value = values[mapping.getSourceIndex()];
            value = applyTransform(value, mapping);
            pstmt.setObject(paramIndex, value);
        }
    }

    private void setPreparedStatementValuesFromJson(PreparedStatement pstmt, ImportExportConfig config, JsonNode node) throws SQLException {
        List<ColumnMapping> included = config.getIncludedMappings();
        for (int i = 0; i < included.size(); i++) {
            ColumnMapping mapping = included.get(i);
            int paramIndex = i + 1;
            JsonNode valueNode = node.get(mapping.getSourceColumn());
            Object value = null;
            if (valueNode != null && !valueNode.isNull()) {
                if (valueNode.isTextual()) value = valueNode.asText();
                else if (valueNode.isInt()) value = valueNode.asInt();
                else if (valueNode.isLong()) value = valueNode.asLong();
                else if (valueNode.isDouble()) value = valueNode.asDouble();
                else if (valueNode.isBoolean()) value = valueNode.asBoolean();
                else value = valueNode.toString();
            }
            value = applyTransform(value, mapping);
            pstmt.setObject(paramIndex, value);
        }
    }

    private void reportProgress(Consumer<ProgressInfo> callback, long current, long total, String message) {
        if (callback != null) {
            Platform.runLater(() -> callback.accept(new ProgressInfo(current, total, message)));
        }
    }

    public AbstractExporter getCurrentExporter() {
        return currentExporter;
    }
}
