package com.fooddelivery.controller;

import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.dto.DeliveryStatusResponse;
import com.fooddelivery.entity.Delivery;
import com.fooddelivery.service.DeliveryService;
import com.fooddelivery.service.StatusService;
import com.fooddelivery.entity.Track;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private StatusService statusService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<DeliveryStatusResponse>> getDeliveryStatus(@RequestParam String order_id) {
        DeliveryStatusResponse response = deliveryService.getDeliveryStatusByOrderId(order_id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{deliveryId}")
    public ResponseEntity<ApiResponse<Delivery>> getDelivery(@PathVariable String deliveryId) {
        Optional<Delivery> delivery = deliveryService.getDeliveryById(deliveryId);
        if (delivery.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(delivery.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "配送任务不存在"));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<Delivery>> getDeliveryByOrder(@PathVariable String orderId) {
        Optional<Delivery> delivery = deliveryService.getDeliveryByOrderId(orderId);
        if (delivery.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(delivery.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "配送任务不存在"));
    }

    @PostMapping("/{deliveryId}/pickup")
    public ResponseEntity<ApiResponse<Delivery>> pickupDelivery(@PathVariable String deliveryId,
                                                                @RequestParam(required = false, defaultValue = "餐厅位置") String location) {
        Delivery delivery = deliveryService.pickupDelivery(deliveryId, location);
        return ResponseEntity.ok(ApiResponse.success(delivery));
    }

    @PostMapping("/{deliveryId}/location")
    public ResponseEntity<ApiResponse<Delivery>> updateLocation(@PathVariable String deliveryId,
                                                                @RequestParam String location) {
        Delivery delivery = deliveryService.updateDeliveryLocation(deliveryId, location);
        return ResponseEntity.ok(ApiResponse.success(delivery));
    }

    @PostMapping("/{deliveryId}/complete")
    public ResponseEntity<ApiResponse<Delivery>> completeDelivery(@PathVariable String deliveryId,
                                                                  @RequestParam(required = false, defaultValue = "用户位置") String location) {
        Delivery delivery = deliveryService.completeDelivery(deliveryId, location);
        return ResponseEntity.ok(ApiResponse.success(delivery));
    }

    @GetMapping("/{deliveryId}/tracks")
    public ResponseEntity<ApiResponse<List<Track>>> getTracks(@PathVariable String deliveryId) {
        List<Track> tracks = statusService.getTracksByDeliveryId(deliveryId);
        return ResponseEntity.ok(ApiResponse.success(tracks));
    }
}
