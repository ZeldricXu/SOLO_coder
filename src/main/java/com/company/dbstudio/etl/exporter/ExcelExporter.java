package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.core.util.IOUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ExcelExporter extends AbstractExporter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExporter.class);
    private static final int ROW_ACCESS_WINDOW_SIZE = 1000;

    private SXSSFWorkbook workbook;
    private SXSSFSheet sheet;
    private OutputStream outputStream;
    private CellStyle headerStyle;
    private CellStyle dataStyle;
    private int currentRowIndex = 0;

    @Override
    protected void openOutputStream() throws Exception {
        Path outputPath = Paths.get(config.getOutputPath());
        IOUtils.ensureDirectoryExists(outputPath.getParent());

        outputStream = new BufferedOutputStream(
                Files.newOutputStream(outputPath),
                8192
        );

        workbook = new SXSSFWorkbook(ROW_ACCESS_WINDOW_SIZE);
        workbook.setCompressTempFiles(true);
        sheet = workbook.createSheet("Data");
        sheet.setRandomAccessWindowSize(ROW_ACCESS_WINDOW_SIZE);

        createStyles();
        logger.info("Excel output stream opened: {}", outputPath);
    }

    private void createStyles() {
        headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        dataStyle = workbook.createCellStyle();
        dataStyle.setBorderBottom(BorderStyle.THIN);
        dataStyle.setBorderTop(BorderStyle.THIN);
        dataStyle.setBorderLeft(BorderStyle.THIN);
        dataStyle.setBorderRight(BorderStyle.THIN);
    }

    @Override
    protected void writeHeader() throws Exception {
        if (config.isIncludeHeader() && !columnNames.isEmpty()) {
            Row headerRow = sheet.createRow(currentRowIndex++);
            for (int i = 0; i < columnNames.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columnNames.get(i));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 20 * 256);
            }
            logger.debug("Excel header written: {} columns", columnNames.size());
        }
    }

    @Override
    protected void writeRow() throws Exception {
        Row row = sheet.createRow(currentRowIndex++);
        for (int i = 0; i < columnMappings.size(); i++) {
            Cell cell = row.createCell(i);
            Object value = getColumnValue(i + 1);
            setCellValue(cell, value);
            cell.setCellStyle(dataStyle);
        }
    }

    private void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
        } else if (value instanceof Number num) {
            cell.setCellValue(num.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof java.util.Date date) {
            cell.setCellValue(date);
        } else if (value instanceof java.sql.Date date) {
            cell.setCellValue(date);
        } else if (value instanceof java.sql.Timestamp timestamp) {
            cell.setCellValue(timestamp);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    @Override
    protected void writeFooter() throws Exception {
    }

    @Override
    protected void flushBatch() throws Exception {
        sheet.flushRows();
    }

    @Override
    protected void flushAndClose() throws Exception {
        if (workbook != null) {
            workbook.write(outputStream);
            workbook.close();
            workbook.dispose();
            workbook = null;
        }
        if (outputStream != null) {
            outputStream.flush();
            outputStream.close();
            outputStream = null;
        }
        logger.info("Excel output stream closed, total rows: {}", rowCount);
    }
}
