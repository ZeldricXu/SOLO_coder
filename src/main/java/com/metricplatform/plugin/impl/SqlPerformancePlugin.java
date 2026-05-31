package com.metricplatform.plugin.impl;

import com.metricplatform.plugin.MybatisPlugin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@RequiredArgsConstructor
public class SqlPerformancePlugin implements MybatisPlugin {

    private final MeterRegistry meterRegistry;

    @Value("${plugin.sql-performance.slow-sql-threshold:1000}")
    private long slowSqlThreshold;

    @Value("${plugin.sql-performance.enabled:true}")
    private boolean enabled;

    private final ConcurrentHashMap<String, Timer> sqlTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> sqlCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> slowSqlCounts = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "sql-performance";
    }

    @Override
    public String getDescription() {
        return "SQL性能监控插件，统计SQL执行时间、检测慢SQL、输出Metrics指标";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!isEnabled()) {
            return invocation.proceed();
        }

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String sqlId = ms.getId();
        SqlCommandType commandType = ms.getSqlCommandType();

        long startTime = System.nanoTime();
        boolean isSlow = false;

        try {
            Object result = invocation.proceed();

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            isSlow = elapsedMs > slowSqlThreshold;

            recordMetrics(sqlId, commandType.name(), elapsedMs, isSlow);

            if (isSlow) {
                Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
                log.warn("慢SQL检测 [{}ms] - {} | 参数: {}", elapsedMs, sqlId, parameter);
            } else if (log.isDebugEnabled()) {
                log.debug("SQL执行 [{}ms] - {}", elapsedMs, sqlId);
            }

            return result;

        } catch (Throwable t) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
            log.error("SQL执行异常 [{}ms] - {} | 错误: {}", elapsedMs, sqlId, t.getMessage());
            recordErrorMetrics(sqlId, commandType.name());
            throw t;
        }
    }

    private void recordMetrics(String sqlId, String commandType, long elapsedMs, boolean isSlow) {
        Timer timer = sqlTimers.computeIfAbsent(sqlId, k ->
                Timer.builder("mybatis.sql.execution")
                        .tag("sqlId", sqlId)
                        .tag("commandType", commandType)
                        .description("SQL执行时间")
                        .register(meterRegistry)
        );
        timer.record(elapsedMs, TimeUnit.MILLISECONDS);

        sqlCounts.computeIfAbsent(sqlId, k -> new AtomicLong(0)).incrementAndGet();

        if (isSlow) {
            slowSqlCounts.computeIfAbsent(sqlId, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    private void recordErrorMetrics(String sqlId, String commandType) {
        meterRegistry.counter("mybatis.sql.errors",
                "sqlId", sqlId,
                "commandType", commandType
        ).increment();
    }

    @Override
    public void setProperties(Properties properties) {
    }

    public long getSlowSqlThreshold() {
        return slowSqlThreshold;
    }

    public void setSlowSqlThreshold(long threshold) {
        this.slowSqlThreshold = threshold;
    }
}
