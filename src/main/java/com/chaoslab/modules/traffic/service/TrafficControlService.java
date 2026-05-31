package com.chaoslab.modules.traffic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.OptimisticRetry;
import com.chaoslab.entity.TrafficStrategy;
import com.chaoslab.entity.TrafficStrategyRun;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.TrafficStrategyMapper;
import com.chaoslab.mapper.TrafficStrategyRunMapper;
import com.chaoslab.modules.traffic.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrafficControlService {

    private final TrafficStrategyMapper strategyMapper;
    private final TrafficStrategyRunMapper runMapper;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TrafficStrategy> createStrategy(TrafficStrategyCreateRequest request) {
        return Mono.fromCallable(() -> {
            validateStrategyConfig(request.getType(), request.getConfig());

            TrafficStrategy strategy = new TrafficStrategy();
            strategy.setStrategyId("ts-" + UUID.randomUUID().toString().substring(0, 8));
            strategy.setName(request.getName());
            strategy.setType(request.getType());
            strategy.setNamespace(request.getNamespace());
            strategy.setSelector(request.getSelector());
            strategy.setConfig(request.getConfig());
            strategy.setEnabled(request.getEnabled());
            strategy.setStatus("draft");

            strategyMapper.insert(strategy);
            log.info("Created traffic strategy: {} type: {}", strategy.getStrategyId(), request.getType());
            return strategy;
        });
    }

    public Mono<List<TrafficStrategy>> listStrategies(String type, String namespace) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategy> wrapper = new LambdaQueryWrapper<>();
            if (type != null && !type.isEmpty()) {
                wrapper.eq(TrafficStrategy::getType, type);
            }
            if (namespace != null && !namespace.isEmpty()) {
                wrapper.eq(TrafficStrategy::getNamespace, namespace);
            }
            wrapper.orderByDesc(TrafficStrategy::getCreatedAt);
            return strategyMapper.selectList(wrapper);
        });
    }

    public Mono<TrafficStrategy> getStrategy(String strategyId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategy> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TrafficStrategy::getStrategyId, strategyId);
            TrafficStrategy strategy = strategyMapper.selectOne(wrapper);
            if (strategy == null) {
                throw BusinessException.notFound("流量策略不存在: " + strategyId);
            }
            return strategy;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TrafficStrategyRun> startCanaryRelease(CanaryReleaseRequest request) {
        return getStrategy(request.getStrategyId())
                .flatMap(strategy -> {
                    if (!"canary".equals(strategy.getType())) {
                        return Mono.error(BusinessException.validationError("策略类型不是金丝雀发布"));
                    }
                    return Mono.fromCallable(() -> {
                        TrafficStrategyRun run = new TrafficStrategyRun();
                        run.setRunId("tsr-" + UUID.randomUUID().toString().substring(0, 8));
                        run.setStrategyId(request.getStrategyId());
                        run.setPhase("initializing");
                        run.setProgress(BigDecimal.ZERO);
                        run.setTrafficPercentage(0);
                        run.setStartedAt(LocalDateTime.now());

                        Map<String, Object> metrics = new HashMap<>();
                        metrics.put("targetPercentage", request.getTargetPercentage());
                        metrics.put("stepSize", request.getStepSize());
                        metrics.put("stepIntervalMinutes", request.getStepIntervalMinutes());
                        metrics.put("autoRollback", request.getAutoRollback());
                        metrics.put("errorRateThreshold", request.getErrorRateThreshold());
                        run.setMetrics(metrics);

                        runMapper.insert(run);

                        strategy.setEnabled(true);
                        strategy.setStatus("running");
                        strategyMapper.updateById(strategy);

                        log.info("Started canary release: {} for strategy: {}", run.getRunId(), request.getStrategyId());
                        return run;
                    });
                });
    }

    @Transactional
    public Mono<TrafficStrategyRun> adjustCanaryTraffic(String runId, int percentage) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategyRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TrafficStrategyRun::getRunId, runId);
            TrafficStrategyRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("运行实例不存在: " + runId);
            }

            run.setTrafficPercentage(percentage);
            run.setProgress(BigDecimal.valueOf(percentage).divide(BigDecimal.valueOf(100), 4, BigDecimal.ROUND_HALF_UP));
            run.setPhase("traffic_shifting");
            runMapper.updateById(run);

            log.info("Adjusted canary traffic: {} to {}%", runId, percentage);
            return run;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TrafficStrategyRun> startBlueGreenDeploy(BlueGreenDeployRequest request) {
        return getStrategy(request.getStrategyId())
                .flatMap(strategy -> {
                    if (!"bluegreen".equals(strategy.getType())) {
                        return Mono.error(BusinessException.validationError("策略类型不是蓝绿部署"));
                    }
                    return Mono.fromCallable(() -> {
                        TrafficStrategyRun run = new TrafficStrategyRun();
                        run.setRunId("tsr-" + UUID.randomUUID().toString().substring(0, 8));
                        run.setStrategyId(request.getStrategyId());
                        run.setPhase("preparing");
                        run.setProgress(BigDecimal.ZERO);
                        run.setTrafficPercentage(0);
                        run.setStartedAt(LocalDateTime.now());

                        Map<String, Object> metrics = new HashMap<>();
                        metrics.put("blueVersion", request.getBlueVersion());
                        metrics.put("greenVersion", request.getGreenVersion());
                        metrics.put("testHeaders", request.getTestHeaders());
                        metrics.put("autoSwitch", request.getAutoSwitch());
                        run.setMetrics(metrics);

                        runMapper.insert(run);

                        strategy.setEnabled(true);
                        strategy.setStatus("running");
                        strategyMapper.updateById(strategy);

                        log.info("Started blue-green deploy: {} for strategy: {}", run.getRunId(), request.getStrategyId());
                        return run;
                    });
                });
    }

    @Transactional
    public Mono<TrafficStrategyRun> switchBlueGreen(String runId, boolean toGreen) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategyRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TrafficStrategyRun::getRunId, runId);
            TrafficStrategyRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("运行实例不存在: " + runId);
            }

            run.setTrafficPercentage(toGreen ? 100 : 0);
            run.setProgress(BigDecimal.ONE);
            run.setPhase(toGreen ? "switched_to_green" : "switched_to_blue");
            runMapper.updateById(run);

            log.info("Switched blue-green: {} to {} environment", runId, toGreen ? "green" : "blue");
            return run;
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TrafficStrategyRun> configureCircuitBreaker(CircuitBreakerConfigRequest request) {
        return getStrategy(request.getStrategyId())
                .flatMap(strategy -> {
                    if (!"circuit".equals(strategy.getType())) {
                        return Mono.error(BusinessException.validationError("策略类型不是熔断配置"));
                    }
                    return Mono.fromCallable(() -> {
                        Map<String, Object> config = new HashMap<>();
                        config.put("failureThreshold", request.getFailureThreshold());
                        config.put("failureThresholdPercentage", request.getFailureThresholdPercentage());
                        config.put("waitDurationInOpenState", request.getWaitDurationInOpenState());
                        config.put("permittedNumberOfCallsInHalfOpenState", request.getPermittedNumberOfCallsInHalfOpenState());
                        config.put("slidingWindowSize", request.getSlidingWindowSize());
                        config.put("slidingWindowType", request.getSlidingWindowType());
                        config.put("slowCallDurationThreshold", request.getSlowCallDurationThreshold());
                        config.put("slowCallRateThreshold", request.getSlowCallRateThreshold());

                        strategy.setConfig(config);
                        strategy.setEnabled(true);
                        strategy.setStatus("active");
                        strategyMapper.updateById(strategy);

                        TrafficStrategyRun run = new TrafficStrategyRun();
                        run.setRunId("tsr-" + UUID.randomUUID().toString().substring(0, 8));
                        run.setStrategyId(request.getStrategyId());
                        run.setPhase("configured");
                        run.setProgress(BigDecimal.ONE);
                        run.setStartedAt(LocalDateTime.now());
                        run.setCompletedAt(LocalDateTime.now());
                        runMapper.insert(run);

                        log.info("Configured circuit breaker for strategy: {}", request.getStrategyId());
                        return run;
                    });
                });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TrafficStrategyRun> startTrafficMirror(String strategyId, String targetService) {
        return getStrategy(strategyId)
                .flatMap(strategy -> {
                    if (!"mirror".equals(strategy.getType())) {
                        return Mono.error(BusinessException.validationError("策略类型不是流量镜像"));
                    }
                    return Mono.fromCallable(() -> {
                        Map<String, Object> config = strategy.getConfig();
                        if (config == null) {
                            config = new HashMap<>();
                        }
                        config.put("targetService", targetService);
                        strategy.setConfig(config);
                        strategy.setEnabled(true);
                        strategy.setStatus("running");
                        strategyMapper.updateById(strategy);

                        TrafficStrategyRun run = new TrafficStrategyRun();
                        run.setRunId("tsr-" + UUID.randomUUID().toString().substring(0, 8));
                        run.setStrategyId(strategyId);
                        run.setPhase("mirroring");
                        run.setProgress(BigDecimal.ONE);
                        run.setStartedAt(LocalDateTime.now());
                        run.setTrafficPercentage(100);
                        runMapper.insert(run);

                        log.info("Started traffic mirror: {} -> {}", strategyId, targetService);
                        return run;
                    });
                });
    }

    @Transactional
    public Mono<TrafficStrategyRun> completeStrategyRun(String runId, boolean success, String errorDetail) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategyRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TrafficStrategyRun::getRunId, runId);
            TrafficStrategyRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("运行实例不存在: " + runId);
            }

            run.setPhase(success ? "completed" : "failed");
            run.setProgress(BigDecimal.ONE);
            run.setCompletedAt(LocalDateTime.now());
            run.setErrorDetail(errorDetail);
            runMapper.updateById(run);

            LambdaQueryWrapper<TrafficStrategy> strategyWrapper = new LambdaQueryWrapper<>();
            strategyWrapper.eq(TrafficStrategy::getStrategyId, run.getStrategyId());
            TrafficStrategy strategy = strategyMapper.selectOne(strategyWrapper);
            if (strategy != null) {
                strategy.setStatus(success ? "completed" : "failed");
                strategy.setEnabled(false);
                strategyMapper.updateById(strategy);
            }

            log.info("Completed strategy run: {} success: {}", runId, success);
            return run;
        });
    }

    public Mono<TrafficStrategyRun> getStrategyRun(String runId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TrafficStrategyRun> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TrafficStrategyRun::getRunId, runId);
            TrafficStrategyRun run = runMapper.selectOne(wrapper);
            if (run == null) {
                throw BusinessException.notFound("运行实例不存在: " + runId);
            }
            return run;
        });
    }

    private void validateStrategyConfig(String type, Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw BusinessException.validationError("策略配置不能为空");
        }

        switch (type) {
            case "canary":
                if (!config.containsKey("stableVersion") || !config.containsKey("canaryVersion")) {
                    throw BusinessException.validationError("金丝雀发布需要配置stableVersion和canaryVersion");
                }
                break;
            case "bluegreen":
                if (!config.containsKey("serviceName")) {
                    throw BusinessException.validationError("蓝绿部署需要配置serviceName");
                }
                break;
            case "mirror":
                if (!config.containsKey("sourceService")) {
                    throw BusinessException.validationError("流量镜像需要配置sourceService");
                }
                break;
            case "circuit":
                break;
            default:
                throw BusinessException.validationError("不支持的策略类型: " + type);
        }
    }
}
