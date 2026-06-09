package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.core.util.IOUtils;
import com.company.dbstudio.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

public class JsonExporter extends AbstractExporter {

    private static final Logger logger = LoggerFactory.getLogger(JsonExporter.class);

    private BufferedWriter writer;
    private boolean isFirstRow = true;

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

        writer.write("[\n");
        isFirstRow = true;
        logger.info("JSON output stream opened: {}", outputPath);
    }

    @Override
    protected void writeHeader() throws Exception {
    }

    @Override
    protected void writeRow() throws Exception {
        if (!isFirstRow) {
            writer.write(",\n");
        }
        isFirstRow = false;

        Map<String, Object> rowData = new LinkedHashMap<>();
        for (int i = 0; i < columnMappings.size(); i++) {
            String columnName = columnNames.get(i);
            Object value = getColumnValue(i + 1);
            rowData.put(columnName, value);
        }

        writer.write("  ");
        writer.write(JsonUtils.toJson(rowData));
    }

    @Override
    protected void writeFooter() throws Exception {
        writer.write("\n]");
    }

    @Override
    protected void flushBatch() throws Exception {
        writer.flush();
    }

    @Override
    protected void flushAndClose() throws Exception {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        logger.info("JSON output stream closed, total rows: {}", rowCount);
    }
}
