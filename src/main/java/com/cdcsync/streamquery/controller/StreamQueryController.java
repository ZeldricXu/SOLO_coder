package com.cdcsync.streamquery.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.streamquery.domain.StreamQuery;
import com.cdcsync.streamquery.service.StreamQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stream-queries")
@RequiredArgsConstructor
public class StreamQueryController {

    private final StreamQueryService streamQueryService;

    @PostMapping
    public Result<StreamQuery> create(@RequestBody StreamQuery streamQuery) {
        return Result.success(streamQueryService.create(streamQuery));
    }

    @PutMapping("/{id}")
    public Result<StreamQuery> update(@PathVariable String id, @RequestBody StreamQuery streamQuery) {
        streamQuery.setId(id);
        return Result.success(streamQueryService.update(streamQuery));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        streamQueryService.delete(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<StreamQuery> findById(@PathVariable String id) {
        return Result.success(streamQueryService.findById(id));
    }

    @GetMapping
    public Result<PageResult<StreamQuery>> findPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(streamQueryService.findPage(pageNum, pageSize));
    }

    @PostMapping("/parse")
    public Result<StreamQuery> parseSql(@RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        return Result.success(streamQueryService.parseSql(sql));
    }

    @PostMapping("/{id}/optimize")
    public Result<StreamQuery> optimizePlan(@PathVariable String id) {
        return Result.success(streamQueryService.optimizePlan(id));
    }

    @PostMapping("/{id}/generate")
    public Result<StreamQuery> generatePhysicalPlan(@PathVariable String id) {
        return Result.success(streamQueryService.generatePhysicalPlan(id));
    }

    @PostMapping("/{id}/execute")
    public Result<Object> executeQuery(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> params) {
        return Result.success(streamQueryService.executeQuery(id, params));
    }
}
