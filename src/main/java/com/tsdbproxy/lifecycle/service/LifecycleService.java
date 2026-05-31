package com.tsdbproxy.lifecycle.service;

import com.tsdbproxy.common.entity.LifecyclePolicy;
import com.tsdbproxy.common.exception.BusinessException;
import com.tsdbproxy.common.mapper.LifecyclePolicyMapper;
import com.tsdbproxy.lifecycle.dto.LifecycleExecuteRequest;
import com.tsdbproxy.lifecycle.dto.LifecyclePolicyCreateRequest;
import com.tsdbproxy.lifecycle.strategy.LifecycleStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleService {

    private final LifecyclePolicyMapper lifecyclePolicyMapper;
    private final Map<String, LifecycleStrategy> strategyMap;

    public LifecycleService(LifecyclePolicyMapper lifecyclePolicyMapper, List<LifecycleStrategy> strategies) {
        this.lifecyclePolicyMapper = lifecyclePolicyMapper;
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(LifecycleStrategy::getOperationType, s -> s));
    }

    public Mono<LifecyclePolicy> createPolicy(LifecyclePolicyCreateRequest request) {
        return Mono.fromCallable(() -> {
            LifecyclePolicy policy = new LifecyclePolicy();
            policy.setName(request.getName());
            policy.setTableName(request.getTableName());
            policy.setTimeColumn(request.getTimeColumn());
            policy.setHotDays(request.getHotDays());
            policy.setColdDays(request.getColdDays());
            policy.setArchiveDays(request.getArchiveDays());
            policy.setArchiveLocation(request.getArchiveLocation());
            policy.setEnabled(request.getEnabled());
            lifecyclePolicyMapper.insert(policy);
            return policy;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> executePolicy(LifecycleExecuteRequest request) {
        return Mono.fromRunnable(() -> {
            LifecyclePolicy policy = lifecyclePolicyMapper.selectById(request.getPolicyId());
            if (policy == null) {
                throw new BusinessException("策略不存在");
            }

            LifecycleStrategy strategy = strategyMap.get(request.getOperationType());
            if (strategy == null) {
                throw new BusinessException("不支持的操作类型: " + request.getOperationType());
            }

            strategy.execute(policy);

            policy.setLastExecutionTime(LocalDateTime.now());
            lifecyclePolicyMapper.updateById(policy);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledLifecycle() {
        log.info("开始定时执行数据生命周期管理任务");

        Flux.fromIterable(lifecyclePolicyMapper.selectList(null))
                .filter(p -> p.getEnabled() == 1)
                .flatMap(p -> {
                    LifecycleExecuteRequest migrateReq = new LifecycleExecuteRequest();
                    migrateReq.setPolicyId(p.getId());
                    migrateReq.setOperationType("migrate");
                    return executePolicy(migrateReq).thenMany(Flux.just(p));
                })
                .flatMap(p -> {
                    LifecycleExecuteRequest archiveReq = new LifecycleExecuteRequest();
                    archiveReq.setPolicyId(p.getId());
                    archiveReq.setOperationType("archive");
                    return executePolicy(archiveReq).thenMany(Flux.just(p));
                })
                .flatMap(p -> {
                    LifecycleExecuteRequest cleanupReq = new LifecycleExecuteRequest();
                    cleanupReq.setPolicyId(p.getId());
                    cleanupReq.setOperationType("cleanup");
                    return executePolicy(cleanupReq);
                })
                .subscribe();
    }
}
