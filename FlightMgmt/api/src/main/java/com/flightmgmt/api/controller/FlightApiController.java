package com.flightmgmt.api.controller;

import com.flightmgmt.api.dto.ApiResponse;
import com.flightmgmt.api.dto.BookingRequest;
import com.flightmgmt.booking.service.BookingService;
import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.ConfigManager;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.NotificationQueueManager;
import com.flightmgmt.search.service.SearchService;
import com.flightmgmt.status.service.StatusService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class FlightApiController {
    private SearchService searchService = new SearchService();
    private BookingService bookingService = new BookingService();
    private StatusService statusService = new StatusService();
    private ConfigManager configManager = ConfigManager.getInstance();
    private NotificationQueueManager queueManager = NotificationQueueManager.getInstance();

    @GetMapping("/flights/search")
    public ApiResponse<Map<String, Object>> searchFlights(
            @RequestParam String departure,
            @RequestParam String destination,
            @RequestParam String date) {
        
        LocalDate searchDate = LocalDate.parse(date);
        List<Flight> flights = searchService.searchFlights(departure, destination, searchDate);
        
        List<Map<String, Object>> flightList = flights.stream().map(f -> {
            Map<String, Object> map = new HashMap<>();
            map.put("flight_number", f.getFlightNumber());
            map.put("available", f.getFlightAvailable());
            map.put("flight_id", f.getFlightId());
            map.put("price", f.getFlightPrice());
            map.put("route", f.getFlightRoute());
            map.put("status", f.getFlightStatus());
            map.put("flight_type", f.getFlightType());
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("flights", flightList);

        return ApiResponse.success(result);
    }

    @PostMapping("/bookings/create")
    public ApiResponse<Map<String, Object>> createBooking(@RequestBody BookingRequest request) {
        String paymentMethod = request.getPaymentMethod() != null ? 
            request.getPaymentMethod() : "alipay";
        int seats = request.getSeats() > 0 ? request.getSeats() : 1;

        Booking booking = bookingService.createBooking(
            request.getFlightId(),
            request.getPassengerName(),
            request.getPassengerIdNumber(),
            paymentMethod,
            seats
        );

        Map<String, Object> result = new HashMap<>();
        
        if ("flight_not_found".equals(booking.getBookingStatus())) {
            return ApiResponse.error(404, "航班不存在");
        }
        
        if ("flight_unavailable".equals(booking.getBookingStatus())) {
            return ApiResponse.error(400, "航班已取消，不可预订");
        }
        
        if ("seats_insufficient".equals(booking.getBookingStatus())) {
            return ApiResponse.error(400, "座位不足");
        }

        Flight flight = DataStore.getFlight(booking.getFlightId());
        String flightType = flight != null ? flight.getFlightType() : "domestic";
        int timeoutMinutes = bookingService.getPaymentTimeoutMinutes(flightType);

        result.put("booking_id", booking.getBookingId());
        result.put("status", booking.getBookingStatus());
        result.put("amount", booking.getBookingAmount());
        result.put("flight_id", booking.getFlightId());
        result.put("flight_type", flightType);
        result.put("payment_timeout_minutes", timeoutMinutes);

        return ApiResponse.success(result);
    }

    @GetMapping("/flights/status")
    public ApiResponse<Map<String, Object>> getFlightStatus(@RequestParam String flightNumber) {
        List<Flight> flights = searchService.searchFlightsByNumber(flightNumber);
        
        if (flights.isEmpty()) {
            return ApiResponse.error(404, "航班不存在");
        }

        Flight flight = flights.get(0);
        FlightStatus latestStatus = statusService.getLatestFlightStatus(flight.getFlightId());

        Map<String, Object> statusData = new HashMap<>();
        
        if (latestStatus != null) {
            statusData.put("type", latestStatus.getStatusType());
            statusData.put("detail", latestStatus.getStatusDetail());
            statusData.put("time", latestStatus.getStatusTime().toString());
        } else {
            statusData.put("type", flight.getFlightStatus());
            if ("scheduled".equals(flight.getFlightStatus())) {
                statusData.put("detail", "正常");
            } else if ("delayed".equals(flight.getFlightStatus())) {
                statusData.put("detail", "航班延误");
            } else if ("cancelled".equals(flight.getFlightStatus())) {
                statusData.put("detail", "航班已取消");
            } else {
                statusData.put("detail", "状态正常");
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", statusData);
        result.put("flight_number", flightNumber);
        result.put("available_seats", flight.getFlightAvailable());

        return ApiResponse.success(result);
    }

    @GetMapping("/bookings/{bookingId}")
    public ApiResponse<Booking> getBooking(@PathVariable String bookingId) {
        Booking booking = bookingService.getBooking(bookingId);
        if (booking == null) {
            return ApiResponse.error(404, "预订不存在");
        }
        return ApiResponse.success(booking);
    }

    @PostMapping("/bookings/{bookingId}/refund")
    public ApiResponse<Map<String, Object>> refundBooking(
            @PathVariable String bookingId,
            @RequestParam(required = false, defaultValue = "行程变更") String reason) {
        try {
            Class<?> changeServiceClass = Class.forName("com.flightmgmt.change.service.ChangeService");
            Object changeService = changeServiceClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method method = changeServiceClass.getMethod("processRefund", String.class, String.class);
            ChangeRecord record = (ChangeRecord) method.invoke(changeService, bookingId, reason);
            
            if (record == null) {
                return ApiResponse.error(404, "预订不存在");
            }
            
            if ("invalid_status".equals(record.getChangeStatus())) {
                return ApiResponse.error(400, "预订状态不支持退票");
            }

            Map<String, Object> result = new HashMap<>();
            result.put("change_id", record.getChangeId());
            result.put("status", record.getChangeStatus());
            result.put("refund_amount", record.getChangeAmount());
            result.put("change_type", record.getChangeType());

            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error(500, "退票处理失败: " + e.getMessage());
        }
    }

    @GetMapping("/statistics/monthly")
    public ApiResponse<FlightStatistics> getMonthlyStatistics(@RequestParam String month) {
        try {
            Class<?> analysisServiceClass = Class.forName("com.flightmgmt.analysis.service.AnalysisService");
            Object analysisService = analysisServiceClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method method = analysisServiceClass.getMethod("getMonthlyStatistics", String.class);
            FlightStatistics stats = (FlightStatistics) method.invoke(analysisService, month);
            return ApiResponse.success(stats);
        } catch (Exception e) {
            return ApiResponse.error(500, "统计查询失败: " + e.getMessage());
        }
    }

    @GetMapping("/flights")
    public ApiResponse<List<Flight>> getAllFlights() {
        List<Flight> flights = DataStore.getFlights().values().stream()
            .collect(Collectors.toList());
        return ApiResponse.success(flights);
    }

    @PostMapping("/flights")
    public ApiResponse<Flight> createFlight(@RequestBody Flight flight) {
        if (flight.getFlightNumber() == null || flight.getDeparture() == null || 
                flight.getDestination() == null || flight.getFlightDeparture() == null ||
                flight.getFlightArrival() == null) {
            return ApiResponse.error(400, "缺少必要的航班信息");
        }

        try {
            Class<?> flightServiceClass = Class.forName("com.flightmgmt.flight.service.FlightService");
            Object flightService = flightServiceClass.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method method = flightServiceClass.getMethod("createFlight", Flight.class);
            Flight created = (Flight) method.invoke(flightService, flight);
            return ApiResponse.success(created);
        } catch (Exception e) {
            return ApiResponse.error(500, "航班创建失败: " + e.getMessage());
        }
    }

    @PostMapping("/flights/{flightId}/status")
    public ApiResponse<FlightStatus> updateFlightStatus(
            @PathVariable String flightId,
            @RequestParam String statusType,
            @RequestParam(required = false, defaultValue = "") String detail) {
        FlightStatus status = statusService.updateFlightStatus(flightId, statusType, detail);
        if (status == null) {
            return ApiResponse.error(404, "航班不存在");
        }
        return ApiResponse.success(status);
    }

    @GetMapping("/config/payment-timeout")
    public ApiResponse<Map<String, Object>> getPaymentTimeoutConfig() {
        Map<String, Object> result = new HashMap<>();
        PaymentTimeoutConfig config = configManager.getPaymentTimeoutConfig();
        
        result.put("config_id", config.getConfigId());
        result.put("config_name", config.getConfigName());
        result.put("timeouts_by_type", config.getTimeoutMinutesByType());
        result.put("updated_at", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null);
        result.put("default_timeout_minutes", 15);
        
        return ApiResponse.success(result);
    }

    @PostMapping("/config/payment-timeout")
    public ApiResponse<Map<String, Object>> updatePaymentTimeoutConfig(
            @RequestBody Map<String, Object> request) {
        PaymentTimeoutConfig config = configManager.getPaymentTimeoutConfig();
        
        if (request.containsKey("domestic")) {
            config.getTimeoutMinutesByType().put("domestic", 
                ((Number) request.get("domestic")).intValue());
        }
        if (request.containsKey("international")) {
            config.getTimeoutMinutesByType().put("international", 
                ((Number) request.get("international")).intValue());
        }
        
        config.setUpdatedAt(LocalDateTime.now());
        configManager.updatePaymentTimeoutConfig(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "支付超时配置已更新");
        result.put("timeouts_by_type", config.getTimeoutMinutesByType());
        
        return ApiResponse.success(result);
    }

    @GetMapping("/config/change-rules")
    public ApiResponse<Map<String, Object>> getChangeRulesConfig() {
        Map<String, Object> result = new HashMap<>();
        ChangeRuleConfig config = configManager.getChangeRuleConfig();
        
        result.put("config_id", config.getConfigId());
        result.put("config_name", config.getConfigName());
        result.put("refund_fee_rates", config.getRefundFeeRates());
        result.put("rebook_fee_rates", config.getRebookFeeRates());
        result.put("free_cancel_hours", config.getFreeCancelHours());
        result.put("last_minute_hours", config.getLastMinuteHours());
        result.put("last_minute_fee_rate", config.getLastMinuteFeeRate());
        result.put("rebook_allowed", config.isRebookAllowed());
        result.put("updated_at", config.getUpdatedAt() != null ? config.getUpdatedAt().toString() : null);
        
        return ApiResponse.success(result);
    }

    @PostMapping("/config/change-rules")
    public ApiResponse<Map<String, Object>> updateChangeRulesConfig(
            @RequestBody Map<String, Object> request) {
        ChangeRuleConfig config = configManager.getChangeRuleConfig();
        
        if (request.containsKey("domestic_refund_fee")) {
            config.getRefundFeeRates().put("domestic", 
                ((Number) request.get("domestic_refund_fee")).doubleValue());
        }
        if (request.containsKey("international_refund_fee")) {
            config.getRefundFeeRates().put("international", 
                ((Number) request.get("international_refund_fee")).doubleValue());
        }
        if (request.containsKey("domestic_rebook_fee")) {
            config.getRebookFeeRates().put("domestic", 
                ((Number) request.get("domestic_rebook_fee")).doubleValue());
        }
        if (request.containsKey("international_rebook_fee")) {
            config.getRebookFeeRates().put("international", 
                ((Number) request.get("international_rebook_fee")).doubleValue());
        }
        if (request.containsKey("free_cancel_hours")) {
            config.setFreeCancelHours(((Number) request.get("free_cancel_hours")).intValue());
        }
        if (request.containsKey("last_minute_hours")) {
            config.setLastMinuteHours(((Number) request.get("last_minute_hours")).intValue());
        }
        if (request.containsKey("last_minute_fee_rate")) {
            config.setLastMinuteFeeRate(((Number) request.get("last_minute_fee_rate")).doubleValue());
        }
        if (request.containsKey("rebook_allowed")) {
            config.setRebookAllowed((Boolean) request.get("rebook_allowed"));
        }
        
        config.setUpdatedAt(LocalDateTime.now());
        configManager.updateChangeRuleConfig(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("message", "退改规则配置已更新");
        result.put("refund_fee_rates", config.getRefundFeeRates());
        result.put("rebook_fee_rates", config.getRebookFeeRates());
        result.put("free_cancel_hours", config.getFreeCancelHours());
        result.put("rebook_allowed", config.isRebookAllowed());
        
        return ApiResponse.success(result);
    }

    @GetMapping("/notifications/queue")
    public ApiResponse<Map<String, Object>> getNotificationQueueStatus() {
        Map<String, Object> result = new HashMap<>();
        
        int pending = queueManager.getPendingTaskCount();
        int inProgress = queueManager.getInProgressTaskCount();
        int confirmed = queueManager.getConfirmedTaskCount();
        int failed = queueManager.getFailedTaskCount();
        
        result.put("pending_tasks", pending);
        result.put("in_progress_tasks", inProgress);
        result.put("confirmed_tasks", confirmed);
        result.put("failed_tasks", failed);
        result.put("total_tasks", pending + inProgress + confirmed + failed);
        result.put("is_running", queueManager.isRunning());
        result.put("queue_size_limit", NotificationQueueManager.QUEUE_SIZE_LIMIT);
        
        return ApiResponse.success(result);
    }

    @PostMapping("/notifications/queue/start")
    public ApiResponse<Map<String, Object>> startNotificationQueue() {
        Map<String, Object> result = new HashMap<>();
        
        if (queueManager.isRunning()) {
            result.put("message", "通知队列已在运行");
        } else {
            queueManager.start();
            result.put("message", "通知队列已启动");
        }
        result.put("is_running", queueManager.isRunning());
        
        return ApiResponse.success(result);
    }

    @PostMapping("/notifications/queue/stop")
    public ApiResponse<Map<String, Object>> stopNotificationQueue() {
        Map<String, Object> result = new HashMap<>();
        
        if (queueManager.isRunning()) {
            queueManager.stop();
            result.put("message", "通知队列已停止");
        } else {
            result.put("message", "通知队列未运行");
        }
        result.put("is_running", queueManager.isRunning());
        
        return ApiResponse.success(result);
    }

    @PostMapping("/notifications/{taskId}/confirm")
    public ApiResponse<Map<String, Object>> confirmNotification(@PathVariable String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        boolean success = queueManager.markConfirmed(taskId);
        
        if (success) {
            result.put("message", "通知已确认");
            result.put("task_id", taskId);
            result.put("status", "confirmed");
            return ApiResponse.success(result);
        } else {
            return ApiResponse.error(404, "通知任务不存在或已确认");
        }
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getAllConfigs() {
        Map<String, Object> result = new HashMap<>();
        
        Map<String, Object> paymentConfig = new HashMap<>();
        paymentConfig.put("domestic", configManager.getPaymentTimeoutMinutes("domestic"));
        paymentConfig.put("international", configManager.getPaymentTimeoutMinutes("international"));
        result.put("payment_timeout", paymentConfig);
        
        Map<String, Object> refundConfig = new HashMap<>();
        refundConfig.put("domestic", configManager.getRefundFeeRate("domestic"));
        refundConfig.put("international", configManager.getRefundFeeRate("international"));
        result.put("refund_fee_rates", refundConfig);
        
        Map<String, Object> rebookConfig = new HashMap<>();
        rebookConfig.put("domestic", configManager.getRebookFeeRate("domestic"));
        rebookConfig.put("international", configManager.getRebookFeeRate("international"));
        result.put("rebook_fee_rates", rebookConfig);
        
        result.put("free_cancel_hours", configManager.getFreeCancelHours());
        result.put("rebook_allowed", configManager.isRebookAllowed());
        
        return ApiResponse.success(result);
    }
}
