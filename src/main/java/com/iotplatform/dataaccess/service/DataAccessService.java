package com.iotplatform.dataaccess.service;

import com.iotplatform.dataaccess.dto.QueryResult;
import com.iotplatform.dataaccess.dto.SqlQueryDTO;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DataAccessService {

    Mono<QueryResult> executeQuery(SqlQueryDTO dto);

    Mono<List<Map<String, Object>>> executeQuery(String sql, Object... params);

    Mono<Map<String, Object>> executeQueryOne(String sql, Object... params);

    Mono<Integer> executeUpdate(String sql, Object... params);

    Mono<Long> executeInsert(String sql, Object... params);

    Mono<Boolean> execute(String sql, Object... params);

    Mono<List<Map<String, Object>>> executeNamedQuery(String sql, Map<String, Object> params);

    Mono<Void> executeBatch(String sql, List<Object[]> batchParams);

    Mono<Map<String, Object>> getConnectionStats();

    Mono<Void> clearQueryCache();
}
