package com.tsdbproxy.timeseries.controller;

import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.timeseries.dto.TimeSeriesPoint;
import com.tsdbproxy.timeseries.dto.TimeSeriesQueryRequest;
import com.tsdbproxy.timeseries.dto.TimeSeriesWriteRequest;
import com.tsdbproxy.timeseries.service.TimeSeriesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/timeseries")
@RequiredArgsConstructor
public class TimeSeriesController {

    private final TimeSeriesService timeSeriesService;

    @PostMapping("/write")
    public Mono<Result<Void>> write(@RequestBody TimeSeriesWriteRequest request) {
        return timeSeriesService.write(request)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/query")
    public Flux<TimeSeriesPoint> query(@RequestBody TimeSeriesQueryRequest request) {
        return timeSeriesService.query(request);
    }
}
