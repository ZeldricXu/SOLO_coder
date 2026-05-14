package com.supplychain.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.config.ApprovalTimeoutConfig;
import com.supplychain.common.config.ApprovalWorkflowConfig;
import com.supplychain.common.dto.OrderCreateRequest;
import com.supplychain.common.dto.OrderItemRequest;
import com.supplychain.common.entity.OrderItem;
import com.supplychain.common.entity.PurchaseOrder;
import com.supplychain.common.enums.OrderStatus;
import com.supplychain.common.exception.BusinessException;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.purchase.mapper.PurchaseOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderMapper orderMapper;
    private final SupplierClientService supplierClientService;

    @Value("${approval.timeout.urgent:30}")
    private int urgentTimeoutMinutes;

    @Value("${approval.timeout.normal:120}")
    private int normalTimeoutMinutes;

    @Value("${approval.timeout.low:480}")
    private int lowTimeoutMinutes;

    private final List<String> timeoutNotifications = new ArrayList<>();

    private final Map<String, ApprovalTimeoutConfig> timeoutConfigCache = new ConcurrentHashMap<>();
    private final Map<String, ApprovalWorkflowConfig> workflowConfigCache = new ConcurrentHashMap<>();

    private final Map<String, Map<String, LocalDateTime>> lastNotificationTimeMap = new ConcurrentHashMap<>();

    @Transactional
    public PurchaseOrder createOrder(OrderCreateRequest request) {
        if (request.getSupplierId() != null && !request.getSupplierId().isEmpty()) {
            supplierClientService.validateSupplier(request.getSupplierId());
        } else {
            throw new BusinessException("请指定供应商");
        }

        String orderType = request.getOrderType() != null ? request.getOrderType() : "purchase";

        PurchaseOrder order = PurchaseOrder.builder()
                .orderId(IdGenerator.generateOrderId())
                .supplierId(request.getSupplierId())
                .orderType(orderType)
                .orderItems(convertToOrderItems(request.getOrderItems()))
                .orderAmount(calculateTotalAmount(request.getOrderItems()))
                .orderStatus(OrderStatus.PENDING_APPROVAL.getCode())
                .createdAt(LocalDateTime.now())
                .build();

        orderMapper.insert(order);
        log.info("创建采购订单成功: orderId={}, orderType={}", order.getOrderId(), orderType);

        initializeOrderApprovalWorkflow(order);

        return order;
    }

    private void initializeOrderApprovalWorkflow(PurchaseOrder order) {
        ApprovalWorkflowConfig workflow = getApprovalWorkflow(order.getOrderType());
        if (workflow != null && workflow.isEnabled()) {
            log.info("订单 {} 已关联审批流程: {}", order.getOrderId(), workflow.getWorkflowName());
        }
    }

    private List<OrderItem> convertToOrderItems(List<OrderItemRequest> items) {
        return items.stream()
                .map(item -> OrderItem.builder()
                        .itemId(item.getItemId())
                        .itemName(item.getItemName())
                        .quantity(item.getQuantity())
                        .price(item.getPrice())
                        .build())
                .collect(Collectors.toList());
    }

    private BigDecimal calculateTotalAmount(List<OrderItemRequest> items) {
        return items.stream()
                .map(item -> item.getPrice() != null && item.getQuantity() != null
                        ? item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                        : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public PurchaseOrder getOrder(String orderId) {
        PurchaseOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(404, "采购订单不存在");
        }
        return order;
    }

    public List<PurchaseOrder> listOrders(String status, String supplierId) {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(PurchaseOrder::getOrderStatus, status);
        }
        if (supplierId != null && !supplierId.isEmpty()) {
            wrapper.eq(PurchaseOrder::getSupplierId, supplierId);
        }
        wrapper.orderByDesc(PurchaseOrder::getCreatedAt);
        return orderMapper.selectList(wrapper);
    }

    @Transactional
    public PurchaseOrder approveOrder(String orderId, String approver) {
        PurchaseOrder order = getOrder(orderId);
        if (!OrderStatus.PENDING_APPROVAL.getCode().equals(order.getOrderStatus())) {
            throw new BusinessException("订单当前状态不允许审批");
        }

        ApprovalWorkflowConfig workflow = getApprovalWorkflow(order.getOrderType());
        if (workflow != null && workflow.isEnabled()) {
            validateApprovalPermission(order, approver, workflow);
            executeApprovalActions(order, approver, workflow);
        }

        order.setOrderStatus(OrderStatus.CONFIRMED.getCode());
        order.setApprover(approver);
        order.setConfirmedAt(LocalDateTime.now());
        orderMapper.updateById(order);

        log.info("订单审批通过: orderId={}, approver={}", orderId, approver);
        return order;
    }

    private void validateApprovalPermission(PurchaseOrder order, String approver, ApprovalWorkflowConfig workflow) {
        List<ApprovalWorkflowConfig.ApprovalStep> steps = workflow.getSteps();
        if (steps == null || steps.isEmpty()) {
            return;
        }

        double orderAmount = order.getOrderAmount() != null ? order.getOrderAmount().doubleValue() : 0;
        List<ApprovalWorkflowConfig.ApprovalStep> applicableSteps = workflow.getStepsForAmount(orderAmount);

        if (!applicableSteps.isEmpty()) {
            boolean hasPermission = false;
            for (ApprovalWorkflowConfig.ApprovalStep step : applicableSteps) {
                if (step.getApproverUsers() != null && step.getApproverUsers().contains(approver)) {
                    hasPermission = true;
                    break;
                }
            }
            if (!hasPermission) {
                log.warn("审批人 {} 无权限审批订单 {}", approver, order.getOrderId());
            }
        }
    }

    private void executeApprovalActions(PurchaseOrder order, String approver, ApprovalWorkflowConfig workflow) {
        log.info("执行审批动作: orderId={}, workflow={}, approver={}",
                order.getOrderId(), workflow.getWorkflowName(), approver);
    }

    @Transactional
    public PurchaseOrder rejectOrder(String orderId, String approver, String reason) {
        PurchaseOrder order = getOrder(orderId);
        if (!OrderStatus.PENDING_APPROVAL.getCode().equals(order.getOrderStatus())) {
            throw new BusinessException("订单当前状态不允许审批");
        }

        ApprovalWorkflowConfig workflow = getApprovalWorkflow(order.getOrderType());
        if (workflow != null && workflow.isEnabled()) {
            log.info("执行拒绝动作: orderId={}, workflow={}, approver={}",
                    order.getOrderId(), workflow.getWorkflowName(), approver);
        }

        order.setOrderStatus(OrderStatus.REJECTED.getCode());
        order.setApprover(approver);
        order.setRejectReason(reason);
        orderMapper.updateById(order);

        log.info("订单审批拒绝: orderId={}, approver={}, reason={}", orderId, approver, reason);
        return order;
    }

    @Transactional
    public PurchaseOrder updateOrderStatus(String orderId, OrderStatus status) {
        PurchaseOrder order = getOrder(orderId);
        order.setOrderStatus(status.getCode());

        if (OrderStatus.RECEIVED.getCode().equals(status.getCode())) {
            order.setReceivedAt(LocalDateTime.now());
        }
        if (OrderStatus.CONFIRMED.getCode().equals(status.getCode())) {
            order.setConfirmedAt(LocalDateTime.now());
        }

        orderMapper.updateById(order);
        log.info("订单状态更新: orderId={}, status={}", orderId, status.getCode());
        return order;
    }

    public void updateOrder(PurchaseOrder order) {
        orderMapper.updateById(order);
    }

    @Scheduled(fixedRate = 60000)
    public void checkApprovalTimeout() {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getOrderStatus, OrderStatus.PENDING_APPROVAL.getCode());
        List<PurchaseOrder> pendingOrders = orderMapper.selectList(wrapper);

        for (PurchaseOrder order : pendingOrders) {
            checkOrderTimeout(order);
        }
    }

    private void checkOrderTimeout(PurchaseOrder order) {
        ApprovalTimeoutConfig config = getApprovalTimeoutConfig(order.getOrderType());

        if (!config.isEnabled()) {
            log.debug("订单类型 {} 超时提醒已禁用", order.getOrderType());
            return;
        }

        int timeoutMinutes = config.getTimeoutMinutes();
        Duration duration = Duration.between(order.getCreatedAt(), LocalDateTime.now());

        if (duration.toMinutes() >= timeoutMinutes) {
            if (shouldSendNotification(order, config)) {
                sendTimeoutNotification(order, config);
                updateLastNotificationTime(order);
            }
        }
    }

    private boolean shouldSendNotification(PurchaseOrder order, ApprovalTimeoutConfig config) {
        String orderId = order.getOrderId();
        Map<String, LocalDateTime> lastNotifications = lastNotificationTimeMap
                .computeIfAbsent(orderId, k -> new ConcurrentHashMap<>());

        LocalDateTime lastNotifyTime = lastNotifications.get(order.getOrderType());
        if (lastNotifyTime == null) {
            return true;
        }

        Duration sinceLastNotify = Duration.between(lastNotifyTime, LocalDateTime.now());
        return sinceLastNotify.toMinutes() >= config.getNotificationIntervalMinutes();
    }

    private void updateLastNotificationTime(PurchaseOrder order) {
        String orderId = order.getOrderId();
        Map<String, LocalDateTime> lastNotifications = lastNotificationTimeMap
                .computeIfAbsent(orderId, k -> new ConcurrentHashMap<>());
        lastNotifications.put(order.getOrderType(), LocalDateTime.now());
    }

    public int getTimeoutMinutes(String orderType) {
        ApprovalTimeoutConfig config = getApprovalTimeoutConfig(orderType);
        return config.getTimeoutMinutes();
    }

    public ApprovalTimeoutConfig getApprovalTimeoutConfig(String orderType) {
        return timeoutConfigCache.computeIfAbsent(orderType,
                key -> loadTimeoutConfigFromSource(key));
    }

    private ApprovalTimeoutConfig loadTimeoutConfigFromSource(String orderType) {
        ApprovalTimeoutConfig defaultConfig = ApprovalTimeoutConfig.getDefaultConfig(orderType);
        log.debug("加载审批超时配置: orderType={}, timeout={}分钟",
                orderType, defaultConfig.getTimeoutMinutes());
        return defaultConfig;
    }

    public void updateApprovalTimeoutConfig(ApprovalTimeoutConfig config) {
        timeoutConfigCache.put(config.getOrderType(), config);
        log.info("审批超时配置已更新: orderType={}, timeout={}分钟",
                config.getOrderType(), config.getTimeoutMinutes());
    }

    public Map<String, ApprovalTimeoutConfig> getAllTimeoutConfigs() {
        return new HashMap<>(timeoutConfigCache);
    }

    public void refreshAllTimeoutConfigs() {
        timeoutConfigCache.clear();
        log.info("审批超时配置缓存已刷新");
    }

    public ApprovalWorkflowConfig getApprovalWorkflow(String orderType) {
        return workflowConfigCache.computeIfAbsent(orderType,
                key -> loadWorkflowConfigFromSource(key));
    }

    private ApprovalWorkflowConfig loadWorkflowConfigFromSource(String orderType) {
        ApprovalWorkflowConfig defaultConfig = ApprovalWorkflowConfig.getWorkflowByOrderType(orderType);
        log.debug("加载审批流程配置: orderType={}, workflow={}",
                orderType, defaultConfig != null ? defaultConfig.getWorkflowName() : "default");
        return defaultConfig;
    }

    public void updateApprovalWorkflowConfig(ApprovalWorkflowConfig config) {
        workflowConfigCache.put(config.getOrderType(), config);
        log.info("审批流程配置已更新: orderType={}, workflow={}",
                config.getOrderType(), config.getWorkflowName());
    }

    public Map<String, ApprovalWorkflowConfig> getAllWorkflowConfigs() {
        return new HashMap<>(workflowConfigCache);
    }

    public void refreshAllWorkflowConfigs() {
        workflowConfigCache.clear();
        log.info("审批流程配置缓存已刷新");
    }

    public Map<String, Object> getOrderApprovalInfo(String orderId) {
        PurchaseOrder order = getOrder(orderId);
        Map<String, Object> info = new HashMap<>();

        info.put("orderId", order.getOrderId());
        info.put("orderType", order.getOrderType());
        info.put("orderStatus", order.getOrderStatus());
        info.put("orderAmount", order.getOrderAmount());
        info.put("createdAt", order.getCreatedAt());

        ApprovalTimeoutConfig timeoutConfig = getApprovalTimeoutConfig(order.getOrderType());
        info.put("timeoutConfig", Map.of(
                "configId", timeoutConfig.getConfigId(),
                "timeoutMinutes", timeoutConfig.getTimeoutMinutes(),
                "description", timeoutConfig.getDescription(),
                "notificationInterval", timeoutConfig.getNotificationIntervalMinutes(),
                "maxNotifications", timeoutConfig.getMaxNotifications()
        ));

        ApprovalWorkflowConfig workflowConfig = getApprovalWorkflow(order.getOrderType());
        if (workflowConfig != null) {
            info.put("workflowConfig", Map.of(
                    "workflowId", workflowConfig.getWorkflowId(),
                    "workflowName", workflowConfig.getWorkflowName(),
                    "version", workflowConfig.getVersion(),
                    "stepCount", workflowConfig.getSteps() != null ? workflowConfig.getSteps().size() : 0
            ));

            if (workflowConfig.getSteps() != null && !workflowConfig.getSteps().isEmpty()) {
                double amount = order.getOrderAmount() != null ? order.getOrderAmount().doubleValue() : 0;
                List<ApprovalWorkflowConfig.ApprovalStep> applicableSteps = workflowConfig.getStepsForAmount(amount);
                info.put("applicableSteps", applicableSteps.stream()
                        .map(step -> Map.of(
                                "stepId", step.getStepId(),
                                "stepName", step.getStepName(),
                                "stepOrder", step.getStepOrder(),
                                "approverRole", step.getApproverRole(),
                                "approvalType", step.getApprovalType().getCode(),
                                "timeoutMinutes", step.getTimeoutMinutes()
                        ))
                        .collect(Collectors.toList()));
            }
        }

        if (OrderStatus.PENDING_APPROVAL.getCode().equals(order.getOrderStatus())) {
            Duration waitingTime = Duration.between(order.getCreatedAt(), LocalDateTime.now());
            long waitingMinutes = waitingTime.toMinutes();
            int timeoutMinutes = getTimeoutMinutes(order.getOrderType());

            info.put("waitingMinutes", waitingMinutes);
            info.put("timeoutMinutes", timeoutMinutes);
            info.put("isTimeout", waitingMinutes >= timeoutMinutes);
            info.put("remainingMinutes", Math.max(0, timeoutMinutes - waitingMinutes));

            BigDecimal progress = BigDecimal.valueOf(waitingMinutes)
                    .divide(BigDecimal.valueOf(Math.max(timeoutMinutes, 1)), 2, RoundingMode.HALF_UP);
            info.put("timeoutProgress", Math.min(1.0, progress.doubleValue()));
        }

        return info;
    }

    private void sendTimeoutNotification(PurchaseOrder order, ApprovalTimeoutConfig config) {
        String notification = String.format(
                "[审批超时提醒] 订单ID: %s, 订单类型: %s, 配置名称: %s, " +
                "等待时间: %d分钟, 超时阈值: %d分钟, 创建时间: %s",
                order.getOrderId(),
                order.getOrderType(),
                config.getDescription(),
                Duration.between(order.getCreatedAt(), LocalDateTime.now()).toMinutes(),
                config.getTimeoutMinutes(),
                order.getCreatedAt()
        );
        timeoutNotifications.add(notification);
        log.warn(notification);
    }

    public List<String> getTimeoutNotifications() {
        return new ArrayList<>(timeoutNotifications);
    }

    public void clearTimeoutNotifications() {
        timeoutNotifications.clear();
    }

    public boolean isOrderTimeout(PurchaseOrder order) {
        if (!OrderStatus.PENDING_APPROVAL.getCode().equals(order.getOrderStatus())) {
            return false;
        }
        int timeoutMinutes = getTimeoutMinutes(order.getOrderType());
        Duration duration = Duration.between(order.getCreatedAt(), LocalDateTime.now());
        return duration.toMinutes() >= timeoutMinutes;
    }

    public Map<String, Object> getApprovalStatusFlow(String orderId) {
        PurchaseOrder order = getOrder(orderId);
        Map<String, Object> flow = new HashMap<>();
        flow.put("orderId", order.getOrderId());
        flow.put("currentStatus", order.getOrderStatus());
        flow.put("createdAt", order.getCreatedAt());
        flow.put("approvedAt", order.getConfirmedAt());
        flow.put("receivedAt", order.getReceivedAt());

        List<String> validTransitions = new ArrayList<>();
        String currentStatus = order.getOrderStatus();

        if (OrderStatus.PENDING_APPROVAL.getCode().equals(currentStatus)) {
            validTransitions.add(OrderStatus.CONFIRMED.getCode());
            validTransitions.add(OrderStatus.REJECTED.getCode());
        } else if (OrderStatus.CONFIRMED.getCode().equals(currentStatus)) {
            validTransitions.add(OrderStatus.SHIPPED.getCode());
            validTransitions.add(OrderStatus.RECEIVED.getCode());
        } else if (OrderStatus.SHIPPED.getCode().equals(currentStatus)) {
            validTransitions.add(OrderStatus.RECEIVED.getCode());
        } else if (OrderStatus.RECEIVED.getCode().equals(currentStatus)) {
            validTransitions.add(OrderStatus.COMPLETED.getCode());
        }

        flow.put("validNextStatuses", validTransitions);
        return flow;
    }

    public boolean isValidStatusTransition(String fromStatus, String toStatus) {
        if (OrderStatus.PENDING_APPROVAL.getCode().equals(fromStatus)) {
            return OrderStatus.CONFIRMED.getCode().equals(toStatus)
                    || OrderStatus.REJECTED.getCode().equals(toStatus);
        } else if (OrderStatus.CONFIRMED.getCode().equals(fromStatus)) {
            return OrderStatus.SHIPPED.getCode().equals(toStatus)
                    || OrderStatus.RECEIVED.getCode().equals(toStatus);
        } else if (OrderStatus.SHIPPED.getCode().equals(fromStatus)) {
            return OrderStatus.RECEIVED.getCode().equals(toStatus);
        } else if (OrderStatus.RECEIVED.getCode().equals(fromStatus)) {
            return OrderStatus.COMPLETED.getCode().equals(toStatus);
        }
        return false;
    }

    @Transactional
    public PurchaseOrder transitToStatus(String orderId, OrderStatus targetStatus) {
        PurchaseOrder order = getOrder(orderId);
        String currentStatus = order.getOrderStatus();

        if (!isValidStatusTransition(currentStatus, targetStatus.getCode())) {
            throw new BusinessException("无效的订单状态流转: " + currentStatus + " -> " + targetStatus.getCode());
        }

        order.setOrderStatus(targetStatus.getCode());
        if (OrderStatus.CONFIRMED.getCode().equals(targetStatus.getCode())) {
            order.setConfirmedAt(LocalDateTime.now());
        }
        if (OrderStatus.RECEIVED.getCode().equals(targetStatus.getCode())) {
            order.setReceivedAt(LocalDateTime.now());
        }

        orderMapper.updateById(order);
        log.info("订单状态流转成功: {} -> {}", currentStatus, targetStatus.getCode());
        return order;
    }

    public List<PurchaseOrder> getTimeoutOrders() {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getOrderStatus, OrderStatus.PENDING_APPROVAL.getCode());
        List<PurchaseOrder> pendingOrders = orderMapper.selectList(wrapper);

        return pendingOrders.stream()
                .filter(this::isOrderTimeout)
                .collect(Collectors.toList());
    }

    public Map<String, Object> getApprovalDashboard() {
        Map<String, Object> dashboard = new HashMap<>();

        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getOrderStatus, OrderStatus.PENDING_APPROVAL.getCode());
        List<PurchaseOrder> pendingOrders = orderMapper.selectList(wrapper);

        int totalPending = pendingOrders.size();
        List<PurchaseOrder> timeoutOrders = pendingOrders.stream()
                .filter(this::isOrderTimeout)
                .collect(Collectors.toList());

        Map<String, Long> ordersByType = pendingOrders.stream()
                .collect(Collectors.groupingBy(PurchaseOrder::getOrderType, Collectors.counting()));

        Map<String, Long> timeoutByType = timeoutOrders.stream()
                .collect(Collectors.groupingBy(PurchaseOrder::getOrderType, Collectors.counting()));

        dashboard.put("totalPendingOrders", totalPending);
        dashboard.put("timeoutOrders", timeoutOrders.size());
        dashboard.put("ordersByType", ordersByType);
        dashboard.put("timeoutByType", timeoutByType);

        Map<String, Map<String, Object>> configSummary = new HashMap<>();
        for (Map.Entry<String, ApprovalTimeoutConfig> entry : ApprovalTimeoutConfig.getDefaultConfigs().entrySet()) {
            ApprovalTimeoutConfig config = entry.getValue();
            configSummary.put(entry.getKey(), Map.of(
                    "description", config.getDescription(),
                    "timeoutMinutes", config.getTimeoutMinutes(),
                    "enabled", config.isEnabled()
            ));
        }
        dashboard.put("timeoutConfigs", configSummary);

        return dashboard;
    }
}
