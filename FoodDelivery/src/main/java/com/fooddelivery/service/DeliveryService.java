package com.fooddelivery.service;

import com.fooddelivery.dto.DeliveryStatusResponse;
import com.fooddelivery.entity.*;
import com.fooddelivery.exception.BusinessException;
import com.fooddelivery.repository.DeliveryRepository;
import com.fooddelivery.util.IdGenerator;
import com.fooddelivery.util.RiderLockManager;
import com.fooddelivery.util.RiderLockManager.LockType;
import com.fooddelivery.util.RiderLockManager.RiderLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
public class DeliveryService {

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private RiderService riderService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private StatusService statusService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private RiderLockManager riderLockManager;

    @Transactional
    public Delivery createDelivery(Order order) {
        return createDelivery(order, "normal");
    }

    @Transactional
    public Delivery createDelivery(Order order, LockType lockType) {
        String urgency = convertLockTypeToUrgency(lockType);
        return createDelivery(order, urgency);
    }

    @Transactional
    public Delivery createDelivery(Order order, String urgency) {
        Rider rider = riderService.selectBestRider(order.getDeliveryRegion())
                .orElseThrow(() -> new BusinessException(400, "该区域暂无可用骑手"));

        RiderLock lock = riderLockManager.tryLock(rider.getRiderId(), order.getOrderId(), urgency);
        if (lock == null) {
            throw new BusinessException(409, "骑手正被其他订单锁定，请稍后重试");
        }

        try {
            if (!"available".equals(rider.getRiderStatus())) {
                riderLockManager.releaseLock(rider.getRiderId(), order.getOrderId());
                throw new BusinessException(400, "骑手不可用");
            }

            Delivery delivery = new Delivery();
            delivery.setDeliveryId(IdGenerator.generateDeliveryId());
            delivery.setOrderId(order.getOrderId());
            delivery.setRiderId(rider.getRiderId());
            delivery.setRestaurantId(order.getRestaurantId());
            delivery.setDeliveryStatus("pending_pickup");
            delivery.setCurrentLocation(order.getDeliveryRegion());
            Delivery saved = deliveryRepository.save(delivery);

            riderService.updateRiderStatusWithOrder(rider.getRiderId(), "busy", order.getOrderId());

            statusService.createNotify(order.getOrderId(), "status", "pending_pickup",
                    "订单已分配骑手：" + rider.getRiderName());

            historyService.recordHistory("delivery", saved.getDeliveryId(), "create",
                    "创建配送任务，骑手：" + rider.getRiderName() + "，订单紧急程度：" + urgency);

            riderLockManager.releaseLock(rider.getRiderId(), order.getOrderId());

            return saved;
        } catch (Exception e) {
            riderLockManager.releaseLock(rider.getRiderId(), order.getOrderId());
            throw e;
        }
    }

    private String convertLockTypeToUrgency(LockType lockType) {
        switch (lockType) {
            case URGENCY_ORDER:
                return "urgency";
            case SLOW_ORDER:
                return "slow";
            case NORMAL_ORDER:
            default:
                return "normal";
        }
    }

    public Optional<Delivery> getDeliveryById(String deliveryId) {
        return deliveryRepository.findByDeliveryId(deliveryId);
    }

    public Optional<Delivery> getDeliveryByOrderId(String orderId) {
        return deliveryRepository.findByOrderId(orderId);
    }

    public DeliveryStatusResponse getDeliveryStatusByOrderId(String orderId) {
        Delivery delivery = deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(404, "配送任务不存在"));
        DeliveryStatusResponse response = new DeliveryStatusResponse();
        response.setStatus(delivery.getDeliveryStatus());
        response.setLocation(delivery.getCurrentLocation());
        return response;
    }

    @Transactional
    public Delivery pickupDelivery(String deliveryId, String location) {
        Delivery delivery = deliveryRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new RuntimeException("配送任务不存在"));

        if ("delivered".equals(delivery.getDeliveryStatus())) {
            throw new BusinessException(400, "配送已完成");
        }

        delivery.setDeliveryStatus("picked_up");
        delivery.setCurrentLocation(location);
        delivery.setPickupTime(LocalDateTime.now());
        Delivery saved = deliveryRepository.save(delivery);

        riderService.updateRiderStatus(delivery.getRiderId(), "delivering");

        statusService.createNotify(saved.getOrderId(), "status", "picked_up",
                "骑手已取餐，位置：" + location);
        statusService.createTrack(saved.getDeliveryId(), "picked_up", location);

        historyService.recordHistory("delivery", saved.getDeliveryId(), "pickup",
                "骑手取餐完成，位置：" + location);

        return saved;
    }

    @Transactional
    public Delivery updateDeliveryLocation(String deliveryId, String location) {
        Delivery delivery = deliveryRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new RuntimeException("配送任务不存在"));

        if ("delivered".equals(delivery.getDeliveryStatus())) {
            throw new BusinessException(400, "配送已完成");
        }

        if ("pending_pickup".equals(delivery.getDeliveryStatus())) {
            delivery.setDeliveryStatus("delivering");
        }
        delivery.setCurrentLocation(location);
        Delivery saved = deliveryRepository.save(delivery);

        statusService.createNotify(saved.getOrderId(), "status", "delivering",
                "配送中，当前位置：" + location);
        statusService.createTrack(saved.getDeliveryId(), "delivering", location);

        historyService.recordHistory("delivery", saved.getDeliveryId(), "location_update",
                "位置更新：" + location);

        return saved;
    }

    @Transactional
    public Delivery completeDelivery(String deliveryId, String location) {
        Delivery delivery = deliveryRepository.findByDeliveryId(deliveryId)
                .orElseThrow(() -> new RuntimeException("配送任务不存在"));

        if ("delivered".equals(delivery.getDeliveryStatus())) {
            throw new BusinessException(400, "配送已完成");
        }

        delivery.setDeliveryStatus("delivered");
        delivery.setCurrentLocation(location);
        delivery.setDeliveryTime(LocalDateTime.now());
        Delivery saved = deliveryRepository.save(delivery);

        riderService.updateRiderStatusWithOrder(saved.getRiderId(), "available", null);
        riderService.incrementDeliveryCount(saved.getRiderId());

        orderService.updateOrderStatus(saved.getOrderId(), "delivered");

        statusService.createNotify(saved.getOrderId(), "status", "delivered",
                "订单已送达");
        statusService.createTrack(saved.getDeliveryId(), "delivered", location);

        int month = LocalDateTime.now().getMonth().getValue();
        analysisService.incrementDeliveryCount(month);
        if (delivery.getPickupTime() != null) {
            long deliveryMinutes = java.time.Duration.between(
                    delivery.getPickupTime(), LocalDateTime.now()).toMinutes();
            analysisService.addDeliveryTime(month, deliveryMinutes);
        }

        historyService.recordHistory("delivery", saved.getDeliveryId(), "complete",
                "配送完成，送达位置：" + location);

        return saved;
    }
}
