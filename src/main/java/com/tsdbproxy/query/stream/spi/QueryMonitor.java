package com.tsdbproxy.query.stream.spi;

import com.tsdbproxy.query.stream.model.ParseResult;
import com.tsdbproxy.query.stream.model.QueryStatement;

import java.time.Duration;
import java.util.Map;

public interface QueryMonitor {

    void recordParseSuccess(QueryStatement statement, ParseResult result, Duration totalTime,
                            Duration parseTime, Duration optimizeTime, Duration translateTime);

    void recordParseFailure(QueryStatement statement, Exception e, Duration totalTime);

    void recordStage(String stage, Duration duration);

    Map<String, Object> getMetrics();

    String getStatus();
}
