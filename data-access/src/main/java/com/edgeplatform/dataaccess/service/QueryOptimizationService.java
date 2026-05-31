package com.edgeplatform.dataaccess.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgeplatform.common.dto.PagedRequest;
import com.edgeplatform.common.dto.PagedResult;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryOptimizationService {

    private final ConcurrentHashMap<String, AtomicLong> queryStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> slowQueryThresholds = new ConcurrentHashMap<>();

    public void registerSlowQueryThreshold(String queryName, long thresholdMs) {
        slowQueryThresholds.put(queryName, thresholdMs);
    }

    @Timed(value = "db.query.execution", description = "Database query execution time")
    public <T> PagedResult<T> executePagedQuery(PagedRequest request,
                                                String queryName,
                                                IPageSupplier<T> pageSupplier) {
        long start = System.currentTimeMillis();
        try {
            validatePagingParams(request);

            if (request.getPageSize() > 1000) {
                request.setPageSize(1000);
                log.warn("Page size capped at 1000 for query: {}", queryName);
            }

            Page<T> page = new Page<>(request.getPageNum(), request.getPageSize());
            IPage<T> result = pageSupplier.get(page);

            return new PagedResult<>(result.getTotal(), request.getPageNum(), request.getPageSize(), result.getRecords());
        } finally {
            long duration = System.currentTimeMillis() - start;
            recordQueryStats(queryName, duration);
            checkSlowQuery(queryName, duration);
        }
    }

    @Timed(value = "db.query.execution", description = "Database query execution time")
    public <T> List<T> executeListQuery(String queryName, ListSupplier<T> listSupplier) {
        long start = System.currentTimeMillis();
        try {
            return listSupplier.get();
        } finally {
            long duration = System.currentTimeMillis() - start;
            recordQueryStats(queryName, duration);
            checkSlowQuery(queryName, duration);
        }
    }

    public <T> LambdaQueryWrapper<T> buildOptimizedWrapper(Class<T> entityClass) {
        return new LambdaQueryWrapper<>();
    }

    public void validatePagingParams(PagedRequest request) {
        if (request.getPageNum() <= 0) {
            request.setPageNum(1);
        }
        if (request.getPageSize() <= 0) {
            request.setPageSize(20);
        }
    }

    private void recordQueryStats(String queryName, long durationMs) {
        queryStats.computeIfAbsent(queryName, k -> new AtomicLong(0))
                .addAndGet(durationMs);
    }

    private void checkSlowQuery(String queryName, long durationMs) {
        Long threshold = slowQueryThresholds.get(queryName);
        if (threshold != null && durationMs > threshold) {
            log.warn("Slow query detected [{}]: {}ms exceeds threshold {}ms", queryName, durationMs, threshold);
        }
    }

    @FunctionalInterface
    public interface IPageSupplier<T> {
        IPage<T> get(Page<T> page);
    }

    @FunctionalInterface
    public interface ListSupplier<T> {
        List<T> get();
    }
}
