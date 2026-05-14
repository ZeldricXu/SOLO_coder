package com.logistics.service;

import com.logistics.entity.DeliveryType;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.DeliveryTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryTypeService {

    private final DeliveryTypeRepository deliveryTypeRepository;
    private final Map<String, DeliveryType> deliveryTypeCache = new ConcurrentHashMap<>();

    public static final String DEFAULT_TYPE_CODE = "STANDARD";
    public static final String URGENT_TYPE_CODE = "URGENT";
    public static final String SUPER_URGENT_TYPE_CODE = "SUPER_URGENT";

    @Transactional
    public DeliveryType createDeliveryType(DeliveryType deliveryType) {
        if (deliveryTypeRepository.findByTypeCode(deliveryType.getTypeCode()).isPresent()) {
            throw new LogisticsException("配送类型已存在: " + deliveryType.getTypeCode());
        }
        DeliveryType saved = deliveryTypeRepository.save(deliveryType);
        refreshCache();
        log.info("创建配送类型: {}", deliveryType.getTypeCode());
        return saved;
    }

    @Transactional
    @CacheEvict(value = "deliveryTypes", allEntries = true)
    public DeliveryType updateDeliveryType(String typeCode, DeliveryType deliveryType) {
        DeliveryType existing = getDeliveryType(typeCode);
        
        if (deliveryType.getTypeName() != null) {
            existing.setTypeName(deliveryType.getTypeName());
        }
        if (deliveryType.getDescription() != null) {
            existing.setDescription(deliveryType.getDescription());
        }
        if (deliveryType.getUrgencyLevel() != null) {
            existing.setUrgencyLevel(deliveryType.getUrgencyLevel());
        }
        if (deliveryType.getPriority() != null) {
            existing.setPriority(deliveryType.getPriority());
        }
        if (deliveryType.getBaseFee() != null) {
            existing.setBaseFee(deliveryType.getBaseFee());
        }
        if (deliveryType.getDistanceRate() != null) {
            existing.setDistanceRate(deliveryType.getDistanceRate());
        }
        if (deliveryType.getTimeRate() != null) {
            existing.setTimeRate(deliveryType.getTimeRate());
        }
        if (deliveryType.getMinFee() != null) {
            existing.setMinFee(deliveryType.getMinFee());
        }
        if (deliveryType.getMaxFee() != null) {
            existing.setMaxFee(deliveryType.getMaxFee());
        }
        if (deliveryType.getIsActive() != null) {
            existing.setIsActive(deliveryType.getIsActive());
        }

        DeliveryType saved = deliveryTypeRepository.save(existing);
        refreshCache();
        log.info("更新配送类型: {}", typeCode);
        return saved;
    }

    @Transactional
    @CacheEvict(value = "deliveryTypes", allEntries = true)
    public void deleteDeliveryType(String typeCode) {
        DeliveryType existing = getDeliveryType(typeCode);
        existing.setIsActive(false);
        deliveryTypeRepository.save(existing);
        refreshCache();
        log.info("删除配送类型: {}", typeCode);
    }

    public DeliveryType getDeliveryType(String typeCode) {
        DeliveryType cached = deliveryTypeCache.get(typeCode);
        if (cached != null && cached.getIsActive()) {
            return cached;
        }
        
        DeliveryType type = deliveryTypeRepository.findByTypeCodeAndIsActiveTrue(typeCode)
                .orElseThrow(() -> new LogisticsException("配送类型不存在或未启用: " + typeCode));
        deliveryTypeCache.put(typeCode, type);
        return type;
    }

    @Cacheable(value = "deliveryTypes")
    public List<DeliveryType> getAllDeliveryTypes() {
        return deliveryTypeRepository.findByIsActiveTrueOrderByPriorityAsc();
    }

    public DeliveryType getDefaultDeliveryType() {
        return getDeliveryType(DEFAULT_TYPE_CODE);
    }

    public Optional<DeliveryType> findDeliveryType(String typeCode) {
        return deliveryTypeRepository.findByTypeCodeAndIsActiveTrue(typeCode);
    }

    private void refreshCache() {
        deliveryTypeCache.clear();
        List<DeliveryType> activeTypes = deliveryTypeRepository.findByIsActiveTrue();
        for (DeliveryType type : activeTypes) {
            deliveryTypeCache.put(type.getTypeCode(), type);
        }
    }

    public void initializeDefaultTypes() {
        if (deliveryTypeRepository.count() > 0) {
            log.info("配送类型已初始化，跳过默认配置");
            return;
        }

        DeliveryType standard = createDefaultType(
                DEFAULT_TYPE_CODE, "标准配送", "普通配送，正常时效",
                "NORMAL", 10, 5.0, 2.0, 0.5, 8.0, 50.0);

        DeliveryType urgent = createDefaultType(
                URGENT_TYPE_CODE, "紧急配送", "加急配送，优先处理",
                "URGENT", 5, 10.0, 3.0, 1.0, 15.0, 100.0);

        DeliveryType superUrgent = createDefaultType(
                SUPER_URGENT_TYPE_CODE, "超紧急配送", "立即配送，最高优先级",
                "SUPER_URGENT", 1, 20.0, 5.0, 2.0, 25.0, 200.0);

        log.info("初始化默认配送类型: 标准配送, 紧急配送, 超紧急配送");
    }

    private DeliveryType createDefaultType(String code, String name, String description,
                                            String urgency, int priority,
                                            double baseFee, double distanceRate, double timeRate,
                                            double minFee, double maxFee) {
        DeliveryType type = new DeliveryType();
        type.setTypeCode(code);
        type.setTypeName(name);
        type.setDescription(description);
        type.setUrgencyLevel(urgency);
        type.setPriority(priority);
        type.setBaseFee(baseFee);
        type.setDistanceRate(distanceRate);
        type.setTimeRate(timeRate);
        type.setMinFee(minFee);
        type.setMaxFee(maxFee);
        type.setIsActive(true);
        return deliveryTypeRepository.save(type);
    }
}
