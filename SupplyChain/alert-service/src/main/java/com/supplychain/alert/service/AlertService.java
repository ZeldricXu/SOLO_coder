package com.supplychain.alert.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.InventoryWarning;
import com.supplychain.common.enums.WarningLevel;
import com.supplychain.common.enums.WarningType;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.alert.mapper.InventoryWarningMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final InventoryWarningMapper warningMapper;

    @Transactional
    public InventoryWarning createWarning(InventoryWarning warning) {
        warning.setWarningId(IdGenerator.generateWarningId());
        warning.setTriggeredAt(LocalDateTime.now());
        if (warning.getStatus() == null) {
            warning.setStatus("active");
        }
        warningMapper.insert(warning);
        log.warn("创建预警: warningId={}, type={}, level={}", 
            warning.getWarningId(), warning.getWarningType(), warning.getWarningLevel());
        sendNotification(warning);
        return warning;
    }

    @Transactional
    public InventoryWarning createLowStockWarning(String itemId, int currentQty, int threshold) {
        String level = currentQty < threshold / 2 ? WarningLevel.HIGH.getCode() 
            : currentQty < threshold * 0.8 ? WarningLevel.MEDIUM.getCode() 
            : WarningLevel.LOW.getCode();

        InventoryWarning warning = InventoryWarning.builder()
            .itemId(itemId)
            .warningType(WarningType.LOW_STOCK.getCode())
            .warningLevel(level)
            .currentQuantity(currentQty)
            .warningThreshold(threshold)
            .build();
        return createWarning(warning);
    }

    public List<InventoryWarning> getActiveWarnings() {
        LambdaQueryWrapper<InventoryWarning> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryWarning::getStatus, "active")
               .orderByDesc(InventoryWarning::getTriggeredAt);
        return warningMapper.selectList(wrapper);
    }

    public List<InventoryWarning> getWarningsByType(String type) {
        LambdaQueryWrapper<InventoryWarning> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryWarning::getWarningType, type)
               .orderByDesc(InventoryWarning::getTriggeredAt);
        return warningMapper.selectList(wrapper);
    }

    public List<InventoryWarning> getWarningsByLevel(String level) {
        LambdaQueryWrapper<InventoryWarning> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(InventoryWarning::getWarningLevel, level)
               .orderByDesc(InventoryWarning::getTriggeredAt);
        return warningMapper.selectList(wrapper);
    }

    @Transactional
    public InventoryWarning handleWarning(String warningId, String handler, String handleNote) {
        InventoryWarning warning = warningMapper.selectById(warningId);
        if (warning != null) {
            warning.setStatus("handled");
            warning.setHandler(handler);
            warning.setHandledAt(LocalDateTime.now());
            warningMapper.updateById(warning);
            log.info("处理预警: warningId={}, handler={}", warningId, handler);
        }
        return warning;
    }

    @Scheduled(fixedRate = 300000)
    public void checkWarnings() {
        log.debug("执行预警检测定时任务");
        List<InventoryWarning> warnings = getActiveWarnings();
        if (!warnings.isEmpty()) {
            log.info("当前活跃预警数: {}", warnings.size());
        }
    }

    private void sendNotification(InventoryWarning warning) {
        log.info("发送预警通知: type={}, itemId={}, level={}", 
            warning.getWarningType(), warning.getItemId(), warning.getWarningLevel());
    }

    public Map<String, Object> getWarningStats() {
        long activeCount = warningMapper.selectCount(
            new LambdaQueryWrapper<InventoryWarning>().eq(InventoryWarning::getStatus, "active"));
        long handledCount = warningMapper.selectCount(
            new LambdaQueryWrapper<InventoryWarning>().eq(InventoryWarning::getStatus, "handled"));
        long highCount = warningMapper.selectCount(
            new LambdaQueryWrapper<InventoryWarning>().eq(InventoryWarning::getWarningLevel, "high"));
        
        return Map.of(
            "activeCount", activeCount,
            "handledCount", handledCount,
            "highCount", highCount,
            "totalCount", activeCount + handledCount
        );
    }

    public List<InventoryWarning> listWarnings(String status, String type, String level) {
        LambdaQueryWrapper<InventoryWarning> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(InventoryWarning::getStatus, status);
        }
        if (type != null && !type.isEmpty()) {
            wrapper.eq(InventoryWarning::getWarningType, type);
        }
        if (level != null && !level.isEmpty()) {
            wrapper.eq(InventoryWarning::getWarningLevel, level);
        }
        wrapper.orderByDesc(InventoryWarning::getTriggeredAt);
        return warningMapper.selectList(wrapper);
    }
}
