package com.cdcsync.lineage.controller;

import com.cdcsync.common.api.Result;
import com.cdcsync.lineage.domain.LineageEdge;
import com.cdcsync.lineage.domain.LineageGraph;
import com.cdcsync.lineage.service.LineageService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/lineage")
@RequiredArgsConstructor
public class LineageController {

    private final LineageService lineageService;

    @PostMapping("/parse")
    public Result<LineageGraph> parseSql(@RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        String sourceIdentifier = request.getOrDefault("sourceIdentifier", "manual");
        return Result.success(lineageService.parseSql(sql, sourceIdentifier));
    }

    @GetMapping("/graph/{graphId}")
    public Result<LineageGraph> getGraph(@PathVariable String graphId) {
        return Result.success(lineageService.getGraph(graphId));
    }

    @GetMapping("/table/{tableName}")
    public Result<List<LineageEdge>> getLineageByTable(@PathVariable String tableName) {
        return Result.success(lineageService.getLineageByTable(tableName));
    }

    @GetMapping("/upstream")
    public Result<List<LineageEdge>> getUpstreamLineage(
            @RequestParam @NotBlank String tableName,
            @RequestParam @NotBlank String columnName) {
        return Result.success(lineageService.getUpstreamLineage(tableName, columnName));
    }

    @GetMapping("/downstream")
    public Result<List<LineageEdge>> getDownstreamLineage(
            @RequestParam @NotBlank String tableName,
            @RequestParam @NotBlank String columnName) {
        return Result.success(lineageService.getDownstreamLineage(tableName, columnName));
    }
}
