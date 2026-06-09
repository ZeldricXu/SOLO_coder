package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.core.util.IOUtils;
import com.opencsv.CSVWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CsvExporter extends AbstractExporter {

    private static final Logger logger = LoggerFactory.getLogger(CsvExporter.class);

    private BufferedWriter writer;
    private CSVWriter csvWriter;

    @Override
    protected void openOutputStream() throws Exception {
        Path outputPath = Paths.get(config.getOutputPath());
        IOUtils.ensureDirectoryExists(outputPath.getParent());

        writer = new BufferedWriter(
                new OutputStreamWriter(
                        Files.newOutputStream(outputPath),
                        config.getEncoding() != null ? config.getEncoding() : StandardCharsets.UTF_8
                ),
                8192
        );

        char separator = config.getDelimiter() != null ? config.getDelimiter().charAt(0) : ',';
        csvWriter = new CSVWriter(writer, separator, '"', '"', "\n");

        logger.info("CSV output stream opened: {}", outputPath);
    }

    @Override
    protected void writeHeader() throws Exception {
        if (config.isIncludeHeader() && !columnNames.isEmpty()) {
            String[] headers = columnNames.toArray(new String[0]);
            csvWriter.writeNext(headers);
            logger.debug("CSV header written: {} columns", headers.length);
        }
    }

    @Override
    protected void writeRow() throws Exception {
        String[] values = new String[columnMappings.size()];
        for (int i = 0; i < columnMappings.size(); i++) {
            Object value = getColumnValue(i + 1);
            values[i] = value != null ? value.toString() : config.getNullValue() != null ? config.getNullValue() : "";
        }
        csvWriter.writeNext(values);
    }

    @Override
    protected void writeFooter() throws Exception {
    }

    @Override
    protected void flushBatch() throws Exception {
        csvWriter.flush();
    }

    @Override
    protected void flushAndClose() throws Exception {
        if (csvWriter != null) {
            csvWriter.flush();
            csvWriter.close();
            csvWriter = null;
        }
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        logger.info("CSV output stream closed, total rows: {}", rowCount);
    }
}
