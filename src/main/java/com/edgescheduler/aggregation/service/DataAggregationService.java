package com.edgescheduler.aggregation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.aggregation.dto.DataCollectRequest;
import com.edgescheduler.aggregation.dto.DataStreamDTO;
import com.edgescheduler.aggregation.entity.DataAggregationResult;
import com.edgescheduler.aggregation.entity.DataStream;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface DataAggregationService {

    DataStreamDTO createDataStream(DataStreamDTO dto);

    DataStreamDTO getDataStream(String streamId);

    IPage<DataStreamDTO> listDataStreams(Page<DataStream> page, String deviceKey, Integer enabled);

    DataStreamDTO updateDataStream(String streamId, DataStreamDTO dto);

    void deleteDataStream(String streamId);

    void collectData(DataCollectRequest request);

    void processAggregation();

    Map<String, Object> calculateAggregation(String type, List<Map<String, Object>> data,
                                             List<Map<String, Object>> fieldsConfig);

    List<DataAggregationResult> getAggregationResults(String streamId, int limit);

    List<DataAggregationResult> getAggregationResultsByTimeRange(
            String streamId, LocalDateTime startTime, LocalDateTime endTime);

    void uploadAggregationResults();

    Map<String, Object> getAggregationStatistics(String streamId);
}
