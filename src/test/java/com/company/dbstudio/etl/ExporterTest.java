package com.company.dbstudio.etl;

import com.company.dbstudio.etl.exporter.*;
import com.company.dbstudio.etl.model.Format;
import com.company.dbstudio.etl.model.ImportExportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void testExporterFactory() {
        assertTrue(ExporterFactory.createExporter(Format.CSV) instanceof CsvExporter);
        assertTrue(ExporterFactory.createExporter(Format.JSON) instanceof JsonExporter);
        assertTrue(ExporterFactory.createExporter(Format.EXCEL) instanceof ExcelExporter);
        assertTrue(ExporterFactory.createExporter(Format.PARQUET) instanceof ParquetExporter);
    }

    @Test
    void testCsvExporterCreation() {
        CsvExporter exporter = new CsvExporter();
        assertNotNull(exporter);
    }

    @Test
    void testJsonExporterCreation() {
        JsonExporter exporter = new JsonExporter();
        assertNotNull(exporter);
    }

    @Test
    void testExcelExporterCreation() {
        ExcelExporter exporter = new ExcelExporter();
        assertNotNull(exporter);
    }

    @Test
    void testParquetExporterCreation() {
        ParquetExporter exporter = new ParquetExporter();
        assertNotNull(exporter);
    }

    @Test
    void testAbstractExporterCancel() {
        CsvExporter exporter = new CsvExporter();
        assertFalse(exporter.isCancelled());

        exporter.cancel();
        assertTrue(exporter.isCancelled());
    }

    @Test
    void testAbstractExporterConfig() {
        CsvExporter exporter = new CsvExporter();
        ImportExportConfig config = new ImportExportConfig();
        config.setOutputPath(tempDir.resolve("test.csv").toString());
        config.setFormat(Format.CSV);

        exporter.setConfig(config);
        assertEquals(config, exporter.getConfig());
    }

    @Test
    void testAbstractExporterProgress() {
        CsvExporter exporter = new CsvExporter();
        assertEquals(0, exporter.getProgress());

        exporter.setProgress(50);
        assertEquals(50, exporter.getProgress());
    }

    @Test
    void testExportConfig() {
        ImportExportConfig config = new ImportExportConfig();
        config.setOutputPath("/tmp/test.csv");
        config.setFormat(Format.CSV);
        config.setBatchSize(1000);
        config.setIncludeHeader(true);

        assertEquals("/tmp/test.csv", config.getOutputPath());
        assertEquals(Format.CSV, config.getFormat());
        assertEquals(1000, config.getBatchSize());
        assertTrue(config.isIncludeHeader());
    }

    @Test
    void testFormatEnum() {
        assertEquals("CSV", Format.CSV.name());
        assertEquals("JSON", Format.JSON.name());
        assertEquals("EXCEL", Format.EXCEL.name());
        assertEquals("PARQUET", Format.PARQUET.name());

        assertEquals("CSV", Format.CSV.getDisplayName());
        assertEquals("JSON", Format.JSON.getDisplayName());
        assertEquals("Excel", Format.EXCEL.getDisplayName());
        assertEquals("Parquet", Format.PARQUET.getDisplayName());
    }

    @Test
    void testFormatFromExtension() {
        assertEquals(Format.CSV, Format.fromExtension("csv"));
        assertEquals(Format.JSON, Format.fromExtension("json"));
        assertEquals(Format.EXCEL, Format.fromExtension("xlsx"));
        assertEquals(Format.EXCEL, Format.fromExtension("xls"));
        assertEquals(Format.PARQUET, Format.fromExtension("parquet"));
    }

    @Test
    void testExporterIsCloseable() {
        CsvExporter exporter = new CsvExporter();
        assertTrue(exporter instanceof AutoCloseable);
    }

    @Test
    void testExporterCancelInitialState() {
        CsvExporter exporter = new CsvExporter();
        assertFalse(exporter.isCancelled());
    }

    @Test
    void testExporterProgressInitialState() {
        CsvExporter exporter = new CsvExporter();
        assertEquals(0, exporter.getProgress());
    }
}
