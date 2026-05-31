package com.iotplatform.dataaccess.controller;

import com.iotplatform.common.dto.Result;
import com.iotplatform.dataaccess.dto.QueryResult;
import com.iotplatform.dataaccess.dto.SqlQueryDTO;
import com.iotplatform.dataaccess.service.DataAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataAccessController {

    private final DataAccessService dataAccessService;

    @PostMapping("/query")
    public Mono<Result<QueryResult>> executeQuery(@Valid @RequestBody SqlQueryDTO dto) {
        return dataAccessService.executeQuery(dto)
                .map(Result::success);
    }

    @GetMapping("/query")
    public Mono<Result<List<Map<String, Object>>>> executeGetQuery(@RequestParam String sql,
                                                                    @RequestParam(required = false) List<Object> params) {
        Object[] paramArray = params != null ? params.toArray() : new Object[0];
        return dataAccessService.executeQuery(sql, paramArray)
                .map(Result::success);
    }

    @GetMapping("/query/one")
    public Mono<Result<Map<String, Object>>> executeQueryOne(@RequestParam String sql,
                                                             @RequestParam(required = false) List<Object> params) {
        Object[] paramArray = params != null ? params.toArray() : new Object[0];
        return dataAccessService.executeQueryOne(sql, paramArray)
                .map(Result::success);
    }

    @PostMapping("/update")
    public Mono<Result<Integer>> executeUpdate(@RequestParam String sql,
                                               @RequestBody(required = false) List<Object> params) {
        Object[] paramArray = params != null ? params.toArray() : new Object[0];
        return dataAccessService.executeUpdate(sql, paramArray)
                .map(Result::success);
    }

    @PostMapping("/insert")
    public Mono<Result<Long>> executeInsert(@RequestParam String sql,
                                            @RequestBody(required = false) List<Object> params) {
        Object[] paramArray = params != null ? params.toArray() : new Object[0];
        return dataAccessService.executeInsert(sql, paramArray)
                .map(Result::success);
    }

    @PostMapping("/execute")
    public Mono<Result<Boolean>> execute(@RequestParam String sql,
                                         @RequestBody(required = false) List<Object> params) {
        Object[] paramArray = params != null ? params.toArray() : new Object[0];
        return dataAccessService.execute(sql, paramArray)
                .map(Result::success);
    }

    @PostMapping("/named-query")
    public Mono<Result<List<Map<String, Object>>>> executeNamedQuery(
            @RequestParam String sql,
            @RequestBody Map<String, Object> params) {
        return dataAccessService.executeNamedQuery(sql, params)
                .map(Result::success);
    }

    @PostMapping("/batch")
    public Mono<Result<Void>> executeBatch(@RequestParam String sql,
                                            @RequestBody List<Object[]> batchParams) {
        return dataAccessService.executeBatch(sql, batchParams)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Object>>> getConnectionStats() {
        return dataAccessService.getConnectionStats()
                .map(Result::success);
    }

    @PostMapping("/cache/clear")
    public Mono<Result<Void>> clearQueryCache() {
        return dataAccessService.clearQueryCache()
                .then(Mono.just(Result.success(null)));
    }
}
