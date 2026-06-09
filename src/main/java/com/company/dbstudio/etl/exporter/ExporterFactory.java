package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.etl.model.Format;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExporterFactory {

    private static final Logger logger = LoggerFactory.getLogger(ExporterFactory.class);

    private ExporterFactory() {
    }

    public static AbstractExporter createExporter(Format format) {
        if (format == null) {
            throw new IllegalArgumentException("Format cannot be null");
        }

        AbstractExporter exporter = switch (format) {
            case CSV -> new CsvExporter();
            case JSON -> new JsonExporter();
            case EXCEL -> new ExcelExporter();
            case PARQUET -> new ParquetExporter();
        };

        logger.debug("Created exporter for format: {}", format);
        return exporter;
    }
}
