package com.enterprise.risk.gateway.ingress;

import com.enterprise.risk.common.event.RiskEvent;
import com.enterprise.risk.gateway.deserializer.RiskEventDeserializer;
import com.enterprise.risk.gateway.pipeline.EventIngestionPipeline;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件HTTP REST API入口控制器
 * 提供三种接入方式：
 * 1. POST /api/v1/events - 单条事件接入（JSON格式）
 * 2. POST /api/v1/events/batch - 批量事件接入（JSON格式）
 * 3. POST /api/v1/events/protobuf - Protobuf格式事件接入
 *
 * 所有接口统一返回202 Accepted状态码，表示事件已接收并进入处理流水线
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/events")
public class EventHttpController {

    private final EventIngestionPipeline ingestionPipeline;
    private final RiskEventDeserializer eventDeserializer;

    public EventHttpController(EventIngestionPipeline ingestionPipeline,
                               RiskEventDeserializer eventDeserializer) {
        this.ingestionPipeline = ingestionPipeline;
        this.eventDeserializer = eventDeserializer;
    }

    /**
     * 单条事件接入接口
     * POST /api/v1/events
     * Content-Type: application/json
     *
     * @param requestBody 原始请求体
     * @param request     HTTP请求对象，用于获取客户端IP和请求头
     * @return 202 Accepted，包含事件ID
     */
    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> receiveSingleEvent(
            @RequestBody String requestBody,
            HttpServletRequest request) {

        log.debug("接收单条事件请求, 来源IP: {}", getClientIp(request));

        RiskEvent event = eventDeserializer.deserializeJson(requestBody);
        enrichEventFromRequest(event, request);

        String eventId = ingestionPipeline.process(event);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 202);
        response.put("message", "Event accepted");
        response.put("event_id", eventId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 批量事件接入接口
     * POST /api/v1/events/batch
     * Content-Type: application/json
     *
     * @param requestBody 原始请求体（JSON数组格式）
     * @param request     HTTP请求对象
     * @return 202 Accepted，包含批量事件ID列表
     */
    @PostMapping(value = "/batch", consumes = "application/json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> receiveBatchEvents(
            @RequestBody String requestBody,
            HttpServletRequest request) {

        String clientIp = getClientIp(request);
        log.debug("接收批量事件请求, 来源IP: {}", clientIp);

        List<RiskEvent> events = eventDeserializer.deserializeJsonBatch(requestBody);
        List<String> eventIds = new ArrayList<>();

        for (RiskEvent event : events) {
            enrichEventFromRequest(event, request);
            String eventId = ingestionPipeline.process(event);
            eventIds.add(eventId);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("code", 202);
        response.put("message", "Batch events accepted");
        response.put("total", events.size());
        response.put("event_ids", eventIds);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Protobuf格式事件接入接口
     * POST /api/v1/events/protobuf
     * Content-Type: application/x-protobuf
     *
     * @param requestBody Protobuf二进制请求体
     * @param request     HTTP请求对象
     * @return 202 Accepted，包含事件ID
     */
    @PostMapping(value = "/protobuf", consumes = "application/x-protobuf", produces = "application/json")
    public ResponseEntity<Map<String, Object>> receiveProtobufEvent(
            @RequestBody byte[] requestBody,
            HttpServletRequest request) {

        String clientIp = getClientIp(request);
        log.debug("接收Protobuf事件请求, 来源IP: {}, 数据长度: {} bytes", clientIp, requestBody.length);

        RiskEvent event = eventDeserializer.deserializeProtobuf(requestBody);
        enrichEventFromRequest(event, request);

        String eventId = ingestionPipeline.process(event);

        Map<String, Object> response = new HashMap<>();
        response.put("code", 202);
        response.put("message", "Protobuf event accepted");
        response.put("event_id", eventId);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 从HTTP请求中提取信息并补充到事件对象
     * 包括：客户端IP、来源标识、Session信息等
     */
    private void enrichEventFromRequest(RiskEvent event, HttpServletRequest request) {
        if (event.getIp() == null || event.getIp().isEmpty()) {
            event.setIp(getClientIp(request));
        }

        String sourceHeader = request.getHeader("X-Source");
        if (sourceHeader != null && !sourceHeader.isEmpty() && event.getSource() == null) {
            event.setSource(sourceHeader);
        }

        String sessionHeader = request.getHeader("X-Session-Id");
        if (sessionHeader != null && !sessionHeader.isEmpty() && event.getSessionId() == null) {
            event.setSessionId(sessionHeader);
        }

        String userHeader = request.getHeader("X-User-Id");
        if (userHeader != null && !userHeader.isEmpty() && event.getUserId() == null) {
            event.setUserId(userHeader);
        }
    }

    /**
     * 获取客户端真实IP地址
     * 支持X-Forwarded-For、X-Real-IP等代理头
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            int index = xForwardedFor.indexOf(',');
            return index > 0 ? xForwardedFor.substring(0, index).trim() : xForwardedFor.trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty() && !"unknown".equalsIgnoreCase(xRealIp)) {
            return xRealIp.trim();
        }

        return request.getRemoteAddr();
    }
}
