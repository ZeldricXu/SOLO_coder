package com.tsdbproxy.query.stream.app;

import com.tsdbproxy.query.stream.api.QueryParseUseCase;
import com.tsdbproxy.query.stream.impl.monitor.PrometheusQueryMonitor;
import com.tsdbproxy.query.stream.model.LogicalPlan;
import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.PhysicalPlan;
import com.tsdbproxy.query.stream.model.QueryStatement;
import com.tsdbproxy.query.stream.spi.LogicalOptimizer;
import com.tsdbproxy.query.stream.spi.PlanRepository;
import com.tsdbproxy.query.stream.spi.PlanTranslator;
import com.tsdbproxy.query.stream.spi.QueryMonitor;
import com.tsdbproxy.query.stream.spi.SqlSyntaxParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryParseService implements QueryParseUseCase {

    private final SqlSyntaxParser sqlSyntaxParser;
    private final LogicalOptimizer logicalOptimizer;
    private final PlanTranslator planTranslator;
    private final PlanRepository planRepository;
    private final QueryMonitor queryMonitor;

    @Override
    public Mono<ParseResult> execute(QueryStatement statement) {
        return Mono.fromCallable(() -> {
            long startTime = System.nanoTime();
            log.info("开始解析查询: {}", statement.getSql());

            if (queryMonitor instanceof PrometheusQueryMonitor) {
                ((PrometheusQueryMonitor) queryMonitor).incrementActive();
            }

            try {
                long parseStart = System.nanoTime();
                LogicalPlan logicalPlan = sqlSyntaxParser.parse(statement);
                Duration parseTime = Duration.ofNanos(System.nanoTime() - parseStart);
                queryMonitor.recordStage("parse", parseTime);

                long optimizeStart = System.nanoTime();
                List<String> optimizationRules = logicalOptimizer.optimize(logicalPlan);
                Duration optimizeTime = Duration.ofNanos(System.nanoTime() - optimizeStart);
                queryMonitor.recordStage("optimize", optimizeTime);

                long translateStart = System.nanoTime();
                PhysicalPlan physicalPlan = planTranslator.translate(logicalPlan);
                Duration translateTime = Duration.ofNanos(System.nanoTime() - translateStart);
                queryMonitor.recordStage("translate", translateTime);

                Duration totalTime = Duration.ofNanos(System.nanoTime() - startTime);

                ParseResult result = ParseResult.builder()
                        .sql(statement.getSql())
                        .logicalPlan(logicalPlan)
                        .physicalPlan(physicalPlan)
                        .optimizationRules(optimizationRules)
                        .executionTimeMs(totalTime.toMillis())
                        .parseTimeMs(parseTime.toMillis())
                        .optimizeTimeMs(optimizeTime.toMillis())
                        .translateTimeMs(translateTime.toMillis())
                        .build();

                planRepository.save(result);
                queryMonitor.recordParseSuccess(statement, result, totalTime, parseTime, optimizeTime, translateTime);

                log.info("查询解析完成, 总耗时: {}ms (解析:{}ms, 优化:{}ms, 翻译:{}ms)",
                        totalTime.toMillis(), parseTime.toMillis(), optimizeTime.toMillis(), translateTime.toMillis());
                return result;
            } catch (Exception e) {
                Duration totalTime = Duration.ofNanos(System.nanoTime() - startTime);
                queryMonitor.recordParseFailure(statement, e, totalTime);
                log.error("查询解析失败", e);
                throw e;
            } finally {
                if (queryMonitor instanceof PrometheusQueryMonitor) {
                    ((PrometheusQueryMonitor) queryMonitor).decrementActive();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
