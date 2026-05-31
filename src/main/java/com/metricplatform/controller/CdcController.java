package com.metricplatform.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysCdcConnector;
import com.metricplatform.entity.SysCdcEvent;
import com.metricplatform.service.CdcService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cdc")
@RequiredArgsConstructor
public class CdcController {

    private final CdcService cdcService;

    @GetMapping("/connectors")
    public Mono<ApiResponse<List<SysCdcConnector>>> getAllConnectors() {
        return Mono.just(ApiResponse.success(cdcService.getAllConnectors()));
    }

    @GetMapping("/connectors/{connectorId}")
    public Mono<ApiResponse<SysCdcConnector>> getConnector(@PathVariable String connectorId) {
        SysCdcConnector connector = cdcService.getConnectorById(connectorId);
        if (connector != null) {
            return Mono.just(ApiResponse.success(connector));
        } else {
            return Mono.just(ApiResponse.notFound("连接器不存在"));
        }
    }

    @PostMapping("/connectors")
    public Mono<ApiResponse<SysCdcConnector>> createConnector(@RequestBody Map<String, Object> request) {
        String name = (String) request.get("name");
        String sourceType = (String) request.get("sourceType");
        @SuppressWarnings("unchecked")
        Map<String, Object> sourceConfig = (Map<String, Object>) request.get("sourceConfig");
        String outputType = (String) request.get("outputType");
        @SuppressWarnings("unchecked")
        Map<String, Object> outputConfig = (Map<String, Object>) request.get("outputConfig");

        SysCdcConnector connector = cdcService.createConnector(
                name, sourceType, sourceConfig, outputType, outputConfig);
        return Mono.just(ApiResponse.created(connector));
    }

    @PostMapping("/connectors/{connectorId}/start")
    public Mono<ApiResponse<Map<String, Object>>> startConnector(@PathVariable String connectorId) {
        try {
            cdcService.startConnectorAsync(connectorId);

            Map<String, Object> result = new HashMap<>();
            result.put("connectorId", connectorId);
            result.put("message", "CDC连接器已异步启动");
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/connectors/{connectorId}/stop")
    public Mono<ApiResponse<Map<String, Object>>> stopConnector(@PathVariable String connectorId) {
        cdcService.stopConnector(connectorId);

        Map<String, Object> result = new HashMap<>();
        result.put("connectorId", connectorId);
        result.put("message", "CDC连接器已停止");
        return Mono.just(ApiResponse.success(result));
    }

    @DeleteMapping("/connectors/{connectorId}")
    public Mono<ApiResponse<Void>> deleteConnector(@PathVariable String connectorId) {
        boolean result = cdcService.deleteConnector(connectorId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("连接器不存在"));
        }
    }

    @GetMapping("/connectors/{connectorId}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getConnectorStats(@PathVariable String connectorId) {
        Map<String, Object> stats = cdcService.getConnectorStats(connectorId);
        return Mono.just(ApiResponse.success(stats));
    }

    @GetMapping("/connectors/{connectorId}/stats/events")
    public Mono<ApiResponse<List<Map<String, Object>>>> getEventStats(
            @PathVariable String connectorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<Map<String, Object>> stats = cdcService.getEventStats(connectorId, startTime, endTime);
        return Mono.just(ApiResponse.success(stats));
    }

    @GetMapping("/events")
    public Mono<ApiResponse<List<SysCdcEvent>>> getEvents(
            @RequestParam(required = false) String connectorId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String table,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "100") int limit) {
        List<SysCdcEvent> events = cdcService.getEvents(
                connectorId, operation, database, table, startTime, endTime, limit);
        return Mono.just(ApiResponse.success(events));
    }

    @GetMapping("/events/{eventId}/download")
    public Mono<ResponseEntity<byte[]>> downloadEvent(
            @PathVariable String eventId,
            @RequestParam(defaultValue = "json") String format) {
        List<SysCdcEvent> events = cdcService.getEvents(null, null, null, null, null, null, 1);
        SysCdcEvent event = events.stream()
                .filter(e -> eventId.equals(e.getEventId()))
                .findFirst()
                .orElse(null);

        if (event == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        try {
            byte[] data = cdcService.serializeEvent(event, format);
            MediaType mediaType = switch (format.toLowerCase()) {
                case "json" -> MediaType.APPLICATION_JSON;
                case "avro" -> MediaType.parseMediaType("application/avro");
                case "protobuf" -> MediaType.parseMediaType("application/protobuf");
                default -> MediaType.APPLICATION_JSON;
            };

            String filename = String.format("event_%s.%s", eventId, format.toLowerCase());

            return Mono.just(ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(mediaType)
                    .body(data));
        } catch (JsonProcessingException e) {
            return Mono.just(ResponseEntity.internalServerError().build());
        }
    }

    @PostMapping("/connectors/{connectorId}/position")
    public Mono<ApiResponse<Map<String, Object>>> updatePosition(
            @PathVariable String connectorId,
            @RequestBody Map<String, Object> request) {
        SysCdcConnector connector = cdcService.getConnectorById(connectorId);
        if (connector == null) {
            return Mono.just(ApiResponse.notFound("连接器不存在"));
        }

        String lsn = (String) request.get("lsn");
        connector.setCurrentLsn(lsn);
        cdcService.updateById(connector);

        Map<String, Object> result = new HashMap<>();
        result.put("connectorId", connectorId);
        result.put("currentLsn", lsn);
        return Mono.just(ApiResponse.success(result));
    }
}
