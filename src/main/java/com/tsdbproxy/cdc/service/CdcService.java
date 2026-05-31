package com.tsdbproxy.cdc.service;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.cdc.dto.CdcTaskCreateRequest;
import com.tsdbproxy.cdc.parser.BinlogParser;
import com.tsdbproxy.common.entity.CdcTask;
import com.tsdbproxy.common.entity.Datasource;
import com.tsdbproxy.common.exception.BusinessException;
import com.tsdbproxy.common.mapper.CdcTaskMapper;
import com.tsdbproxy.common.mapper.DatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcService {

    private final CdcTaskMapper cdcTaskMapper;
    private final DatasourceMapper datasourceMapper;
    private final BinlogParser binlogParser;

    public Mono<CdcTask> createTask(CdcTaskCreateRequest request) {
        return Mono.fromCallable(() -> {
            CdcTask task = new CdcTask();
            task.setName(request.getName());
            task.setDatasourceId(request.getDatasourceId());
            task.setTableName(request.getTableName());
            task.setOutputType(request.getOutputType());
            task.setOutputConfig(JSONUtil.toJsonStr(request.getOutputConfig()));
            task.setStatus("stopped");
            task.setProcessedEvents(0L);
            cdcTaskMapper.insert(task);
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<CdcTask> startTask(Long taskId) {
        return Mono.fromCallable(() -> {
            CdcTask task = cdcTaskMapper.selectById(taskId);
            if (task == null) {
                throw new BusinessException("任务不存在");
            }

            Datasource datasource = datasourceMapper.selectById(task.getDatasourceId());
            if (datasource == null) {
                throw new BusinessException("数据源不存在");
            }

            binlogParser.startCdc(task, datasource.getHost(), datasource.getPort(),
                    datasource.getUsername(), datasource.getPassword());

            task.setStatus("running");
            task.setLastProcessTime(LocalDateTime.now());
            cdcTaskMapper.updateById(task);

            log.info("CDC任务已启动: taskId={}", taskId);
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<CdcTask> stopTask(Long taskId) {
        return Mono.fromCallable(() -> {
            CdcTask task = cdcTaskMapper.selectById(taskId);
            if (task == null) {
                throw new BusinessException("任务不存在");
            }

            binlogParser.stopCdc(taskId);

            task.setStatus("stopped");
            cdcTaskMapper.updateById(task);

            log.info("CDC任务已停止: taskId={}", taskId);
            return task;
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
