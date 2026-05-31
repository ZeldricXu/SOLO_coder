package com.streamsql;

import com.streamsql.modules.cdc_capture.CdcCaptureServiceTest;
import com.streamsql.modules.data_lineage.DataLineageServiceTest;
import com.streamsql.modules.data_quality.QualityRuleServiceTest;
import com.streamsql.modules.lifecycle.DataLifecycleServiceTest;
import com.streamsql.modules.metadata_crawler.MetadataCrawlerServiceTest;
import com.streamsql.modules.stream_query.StreamQueryParserServiceTest;
import com.streamsql.modules.timeseries_compression.TimeseriesCompressionServiceTest;
import com.streamsql.modules.vector_index.VectorIndexServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@DisplayName("StreamSQL综合测试套件")
@SelectClasses({
        QualityRuleServiceTest.class,
        VectorIndexServiceTest.class,
        StreamQueryParserServiceTest.class,
        MetadataCrawlerServiceTest.class,
        CdcCaptureServiceTest.class,
        DataLineageServiceTest.class,
        TimeseriesCompressionServiceTest.class,
        DataLifecycleServiceTest.class
})
public class StreamSQLTestSuite {
}
