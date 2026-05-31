package com.iotplatform.dataaccess.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.dataaccess.dto.QueryResult;
import com.iotplatform.dataaccess.dto.SqlQueryDTO;
import com.iotplatform.dataaccess.service.DataAccessService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessServiceImpl implements DataAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final Cache<String, List<Map<String, Object>>> queryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .recordStats()
            .build();

    private static final String SQL_INJECTION_PATTERN = ".*(--|;|/\\*|\\*/|xp_|sp_|exec\\s|execute\\s).*";

    @Override
    public Mono<QueryResult> executeQuery(SqlQueryDTO dto) {
        return Mono.fromCallable(() -> {
            validateSql(dto.getSql());
            Timer.Sample sample = Timer.start(meterRegistry);

            try {
                String cacheKey = buildCacheKey(dto.getSql(), dto.getParams());
                List<Map<String, Object>> rows;

                if (dto.getPageNum() != null && dto.getPageSize() != null) {
                    rows = executePagedQuery(dto);
                } else {
                    rows = queryCache.getIfPresent(cacheKey);
                    if (rows == null) {
                        rows = dto.getParams() != null && !dto.getParams().isEmpty()
                                ? jdbcTemplate.queryForList(dto.getSql(), dto.getParams().toArray())
                                : jdbcTemplate.queryForList(dto.getSql());
                        queryCache.put(cacheKey, rows);
                        meterRegistry.counter("dataaccess.query.cache.miss").increment();
                    } else {
                        meterRegistry.counter("dataaccess.query.cache.hit").increment();
                    }
                }

                long total = rows.size();
                long pages = dto.getPageSize() != null ? (total + dto.getPageSize() - 1) / dto.getPageSize() : 1;

                QueryResult result = new QueryResult();
                result.setRows(rows);
                result.setTotal(total);
                result.setPageNum(dto.getPageNum() != null ? dto.getPageNum() : 1);
                result.setPageSize(dto.getPageSize() != null ? dto.getPageSize() : total);
                result.setPages(pages);

                meterRegistry.counter("dataaccess.query.success").increment();
                return result;
            } catch (Exception e) {
                log.error("Query execution failed: {}", e.getMessage());
                meterRegistry.counter("dataaccess.query.failed").increment();
                throw new BusinessException("查询执行失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("dataaccess.query.latency"));
            }
        });
    }

    @Override
    public Mono<List<Map<String, Object>>> executeQuery(String sql, Object... params) {
        return Mono.fromCallable(() -> {
            validateSql(sql);
            String cacheKey = buildCacheKey(sql, params);
            List<Map<String, Object>> cached = queryCache.getIfPresent(cacheKey);
            if (cached != null) {
                meterRegistry.counter("dataaccess.query.cache.hit").increment();
                return cached;
            }

            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, params);
            queryCache.put(cacheKey, result);
            meterRegistry.counter("dataaccess.query.cache.miss").increment();
            return result;
        });
    }

    @Override
    public Mono<Map<String, Object>> executeQueryOne(String sql, Object... params) {
        return executeQuery(sql, params)
                .flatMap(list -> list.isEmpty()
                        ? Mono.just(new HashMap<>())
                        : Mono.just(list.get(0)));
    }

    @Override
    public Mono<Integer> executeUpdate(String sql, Object... params) {
        return Mono.fromCallable(() -> {
            validateSql(sql);
            queryCache.invalidateAll();
            return jdbcTemplate.update(sql, params);
        });
    }

    @Override
    public Mono<Long> executeInsert(String sql, Object... params) {
        return Mono.fromCallable(() -> {
            validateSql(sql);
            queryCache.invalidateAll();
            jdbcTemplate.update(sql, params);
            return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        });
    }

    @Override
    public Mono<Boolean> execute(String sql, Object... params) {
        return Mono.fromCallable(() -> {
            validateSql(sql);
            queryCache.invalidateAll();
            jdbcTemplate.execute(sql);
            return true;
        });
    }

    @Override
    public Mono<List<Map<String, Object>>> executeNamedQuery(String sql, Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            validateSql(sql);
            return namedParameterJdbcTemplate.queryForList(sql, params);
        });
    }

    @Override
    public Mono<Void> executeBatch(String sql, List<Object[]> batchParams) {
        return Mono.fromRunnable(() -> {
            validateSql(sql);
            queryCache.invalidateAll();
            jdbcTemplate.batchUpdate(sql, batchParams);
        });
    }

    @Override
    public Mono<Map<String, Object>> getConnectionStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("queryCacheSize", queryCache.estimatedSize());
            stats.put("queryCacheHitCount", queryCache.stats().hitCount());
            stats.put("queryCacheMissCount", queryCache.stats().missCount());
            stats.put("queryCacheHitRate", queryCache.stats().hitRate());
            return stats;
        });
    }

    @Override
    public Mono<Void> clearQueryCache() {
        return Mono.fromRunnable(() -> {
            queryCache.invalidateAll();
            log.info("Query cache cleared");
        });
    }

    private void validateSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new BusinessException(400, "SQL语句不能为空");
        }

        String lowerSql = sql.toLowerCase();
        if (lowerSql.matches(SQL_INJECTION_PATTERN)) {
            log.warn("Potential SQL injection detected: {}", sql);
            throw new BusinessException(400, "SQL语句包含非法字符");
        }

        if (lowerSql.startsWith("drop") || lowerSql.startsWith("truncate") || lowerSql.startsWith("alter")) {
            throw new BusinessException(403, "不允许执行DDL操作");
        }
    }

    private List<Map<String, Object>> executePagedQuery(SqlQueryDTO dto) {
        int offset = (dto.getPageNum() - 1) * dto.getPageSize();
        String pagedSql = dto.getSql() + " LIMIT " + dto.getPageSize() + " OFFSET " + offset;

        if (dto.getParams() != null && !dto.getParams().isEmpty()) {
            return jdbcTemplate.queryForList(pagedSql, dto.getParams().toArray());
        } else {
            return jdbcTemplate.queryForList(pagedSql);
        }
    }

    private String buildCacheKey(String sql, Object... params) {
        StringBuilder key = new StringBuilder(sql);
        if (params != null) {
            for (Object param : params) {
                key.append(":").append(param != null ? param.toString() : "null");
            }
        }
        return key.toString();
    }
}
