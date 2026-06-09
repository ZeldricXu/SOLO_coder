package com.company.dbstudio.etl.exporter;

import com.company.dbstudio.etl.model.Format;
import com.company.dbstudio.etl.model.ImportExportConfig;
import com.company.dbstudio.etl.model.ProgressInfo;
import com.company.dbstudio.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class ParquetExporter extends AbstractExporter {

    private static final Logger logger = LoggerFactory.getLogger(ParquetExporter.class);

    @Override
    protected void openOutputStream() throws Exception {
        logger.warn("Parquet export is not yet implemented");
        throw new UnsupportedOperationException("Parquet export is not yet implemented");
    }

    @Override
    protected void writeHeader() throws Exception {
    }

    @Override
    protected void writeRow() throws Exception {
    }

    @Override
    protected void writeFooter() throws Exception {
    }

    @Override
    protected void flushBatch() throws Exception {
    }

    @Override
    protected void flushAndClose() throws Exception {
    }

    @Override
    public Result<Long> export(ImportExportConfig config, Consumer<ProgressInfo> progressCallback) {
        return Result.err("Parquet export is not yet implemented");
    }
}
