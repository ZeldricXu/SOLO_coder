package com.hotelbooking.service;

import com.hotelbooking.config.FeeValidationConfig;
import com.hotelbooking.config.FeeValidationConfig.FeeTypeConfig;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.CheckIn;
import com.hotelbooking.model.Room;
import com.hotelbooking.model.ServiceRecord;
import com.hotelbooking.repository.ServiceRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class FeeValidationService {
    private static final Logger logger = LoggerFactory.getLogger(FeeValidationService.class);

    private final ServiceRecordRepository serviceRecordRepository;
    private final FeeValidationConfig feeValidationConfig;

    public FeeValidationService(ServiceRecordRepository serviceRecordRepository,
                                 FeeValidationConfig feeValidationConfig) {
        this.serviceRecordRepository = serviceRecordRepository;
        this.feeValidationConfig = feeValidationConfig;
    }

    public enum FeeType {
        ROOM("ROOM"),
        SERVICE("SERVICE"),
        TOTAL("TOTAL");

        private final String code;

        FeeType(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public ValidationResult validateRoomCharge(Room room, long actualDays, double calculatedRoomCharge) {
        FeeTypeConfig config = feeValidationConfig.getConfig(FeeType.ROOM.getCode());
        
        if (!config.isEnabled()) {
            logger.info("住宿费用校验已禁用，跳过校验");
            return ValidationResult.success();
        }

        logger.info("校验住宿费用: 房间价格={}, 实际天数={}, 计算费用={}", 
                room.getRoomPrice(), actualDays, calculatedRoomCharge);

        List<String> rules = config.getRules();

        if (rules.contains("POSITIVE_DAYS")) {
            if (actualDays <= 0) {
                logger.error("住宿天数校验失败: 实际天数={}", actualDays);
                return ValidationResult.fail("住宿天数必须大于0");
            }
        }

        if (rules.contains("MINIMUM_CHARGE")) {
            String minDaysStr = config.getParameter("min-days");
            int minDays = minDaysStr != null ? Integer.parseInt(minDaysStr) : 1;
            if (actualDays < minDays) {
                logger.error("住宿天数小于最小天数: 实际={}, 最小={}", actualDays, minDays);
                return ValidationResult.fail("住宿天数不能少于" + minDays + "天");
            }
        }

        if (rules.contains("DAYS_MULTIPLIER")) {
            double expectedRoomCharge = room.getRoomPrice() * actualDays;
            double tolerance = feeValidationConfig.getTolerance();
            
            if (Math.abs(expectedRoomCharge - calculatedRoomCharge) > tolerance) {
                logger.error("住宿费用校验失败: 期望={}, 实际={}", expectedRoomCharge, calculatedRoomCharge);
                return ValidationResult.fail(String.format("住宿费用计算错误: 期望=%.2f, 实际=%.2f", 
                        expectedRoomCharge, calculatedRoomCharge));
            }
        }

        logger.info("住宿费用校验通过");
        return ValidationResult.success();
    }

    public ValidationResult validateServiceCharge(String roomId, double calculatedServiceCharge) {
        FeeTypeConfig config = feeValidationConfig.getConfig(FeeType.SERVICE.getCode());
        
        if (!config.isEnabled()) {
            logger.info("服务费用校验已禁用，跳过校验");
            return ValidationResult.success();
        }

        logger.info("校验服务费用: 房间ID={}, 计算费用={}", roomId, calculatedServiceCharge);

        List<String> rules = config.getRules();
        List<ServiceRecord> serviceRecords = serviceRecordRepository.findByRoomId(roomId);
        
        if (rules.contains("COMPLETED_ONLY")) {
            serviceRecords = serviceRecords.stream()
                    .filter(s -> "completed".equals(s.getServiceStatus()))
                    .toList();
        }

        if (rules.contains("CHARGE_VALIDATION")) {
            for (ServiceRecord record : serviceRecords) {
                Double charge = record.getServiceCharge();
                if (charge == null || charge < 0) {
                    logger.error("服务费用记录无效: serviceId={}, charge={}", 
                            record.getServiceId(), charge);
                    return ValidationResult.fail("服务费用记录存在无效值: " + record.getServiceId());
                }
            }
        }

        if (rules.contains("ITEM_SUM")) {
            double expectedServiceCharge = serviceRecords.stream()
                    .mapToDouble(s -> s.getServiceCharge() != null ? s.getServiceCharge() : 0.0)
                    .sum();

            double tolerance = feeValidationConfig.getTolerance();
            if (Math.abs(expectedServiceCharge - calculatedServiceCharge) > tolerance) {
                logger.error("服务费用校验失败: 期望={}, 实际={}", expectedServiceCharge, calculatedServiceCharge);
                return ValidationResult.fail(String.format("服务费用计算错误: 期望=%.2f, 实际=%.2f", 
                        expectedServiceCharge, calculatedServiceCharge));
            }
        }

        logger.info("服务费用校验通过");
        return ValidationResult.success();
    }

    public ValidationResult validateTotalAmount(double roomCharge, double serviceCharge, double totalAmount) {
        FeeTypeConfig config = feeValidationConfig.getConfig(FeeType.TOTAL.getCode());
        
        if (!config.isEnabled()) {
            logger.info("总费用校验已禁用，跳过校验");
            return ValidationResult.success();
        }

        logger.info("校验总费用: 住宿费用={}, 服务费用={}, 总费用={}", 
                roomCharge, serviceCharge, totalAmount);

        List<String> rules = config.getRules();

        if (rules.contains("NON_NEGATIVE")) {
            if (roomCharge < 0 || serviceCharge < 0 || totalAmount < 0) {
                logger.error("费用值校验失败: 住宿费用={}, 服务费用={}, 总费用={}", 
                        roomCharge, serviceCharge, totalAmount);
                return ValidationResult.fail("费用值不能为负数");
            }
        }

        if (rules.contains("SUM_EQUALITY")) {
            double expectedTotal = roomCharge + serviceCharge;
            double tolerance = feeValidationConfig.getTolerance();
            
            if (Math.abs(expectedTotal - totalAmount) > tolerance) {
                logger.error("总费用校验失败: 期望={}, 实际={}", expectedTotal, totalAmount);
                return ValidationResult.fail(String.format("总费用计算错误: 期望=%.2f, 实际=%.2f", 
                        expectedTotal, totalAmount));
            }
        }

        logger.info("总费用校验通过");
        return ValidationResult.success();
    }

    public ValidationResult validateSettlement(Booking booking, CheckIn checkIn, Room room,
                                                double roomCharge, double serviceCharge, double totalAmount) {
        long actualDays = ChronoUnit.DAYS.between(
                checkIn.getCheckinTime().toLocalDate(),
                java.time.LocalDate.now()
        );
        if (actualDays <= 0) {
            actualDays = 1;
        }

        ValidationResult roomResult = validateRoomCharge(room, actualDays, roomCharge);
        if (!roomResult.isValid()) {
            logger.error("住宿费用校验失败，拒绝结算: {}", roomResult.getMessage());
            return roomResult;
        }

        ValidationResult serviceResult = validateServiceCharge(booking.getRoomId(), serviceCharge);
        if (!serviceResult.isValid()) {
            logger.error("服务费用校验失败，拒绝结算: {}", serviceResult.getMessage());
            return serviceResult;
        }

        ValidationResult totalResult = validateTotalAmount(roomCharge, serviceCharge, totalAmount);
        if (!totalResult.isValid()) {
            logger.error("总费用校验失败，拒绝结算: {}", totalResult.getMessage());
            return totalResult;
        }

        logger.info("所有费用校验通过，可以进行结算");
        return ValidationResult.success();
    }

    public double getTolerance() {
        return feeValidationConfig.getTolerance();
    }

    public FeeTypeConfig getFeeTypeConfig(String feeType) {
        return feeValidationConfig.getConfig(feeType);
    }

    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, "校验通过");
        }

        public static ValidationResult fail(String message) {
            logger.error("费用校验失败: {}", message);
            return new ValidationResult(false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public String getMessage() {
            return message;
        }
    }
}
