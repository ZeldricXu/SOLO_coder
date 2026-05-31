package com.tsdbproxy.query.stream.adapter.in.web;

import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.query.stream.api.QueryParseUseCase;
import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.QueryStatement;
import com.tsdbproxy.query.stream.spi.QueryMonitor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryParseController {

    private final QueryParseUseCase queryParseUseCase;
    private final QueryMonitor queryMonitor;

    @PostMapping("/parse")
    public Mono<Result<ParseResult>> parse(@RequestBody ParseWebRequest request) {
        QueryStatement statement = QueryStatement.builder()
                .sql(request.getSql())
                .build();

        return queryParseUseCase.execute(statement)
                .map(Result::success);
    }

    @GetMapping("/monitor/metrics")
    public Result<Map<String, Object>> getMetrics() {
        return Result.success(queryMonitor.getMetrics());
    }

    @GetMapping("/monitor/status")
    public Result<String> getStatus() {
        return Result.success(queryMonitor.getStatus());
    }

    @Data
    public static class ParseWebRequest {
        private String sql;
    }
}
