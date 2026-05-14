package com.logistics.controller;

import com.logistics.entity.DeliveryType;
import com.logistics.service.DeliveryTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/delivery-types")
@RequiredArgsConstructor
public class DeliveryTypeController {

    private final DeliveryTypeService deliveryTypeService;

    @GetMapping
    public ResponseEntity<List<DeliveryType>> getAllDeliveryTypes() {
        List<DeliveryType> types = deliveryTypeService.getAllDeliveryTypes();
        return ResponseEntity.ok(types);
    }

    @GetMapping("/{typeCode}")
    public ResponseEntity<DeliveryType> getDeliveryType(@PathVariable String typeCode) {
        DeliveryType type = deliveryTypeService.getDeliveryType(typeCode);
        return ResponseEntity.ok(type);
    }

    @PostMapping
    public ResponseEntity<DeliveryType> createDeliveryType(@RequestBody DeliveryType deliveryType) {
        log.info("创建配送类型: {}", deliveryType.getTypeCode());
        DeliveryType created = deliveryTypeService.createDeliveryType(deliveryType);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{typeCode}")
    public ResponseEntity<DeliveryType> updateDeliveryType(
            @PathVariable String typeCode,
            @RequestBody DeliveryType deliveryType) {
        log.info("更新配送类型: {}", typeCode);
        DeliveryType updated = deliveryTypeService.updateDeliveryType(typeCode, deliveryType);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{typeCode}")
    public ResponseEntity<Void> deleteDeliveryType(@PathVariable String typeCode) {
        log.info("删除配送类型: {}", typeCode);
        deliveryTypeService.deleteDeliveryType(typeCode);
        return ResponseEntity.noContent().build();
    }
}
