package com.logistics.service;

import com.logistics.entity.DeliveryType;
import com.logistics.entity.Logistics;
import com.logistics.entity.Track;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeeService {

    private final LogisticsService logisticsService;
    private final TrackService trackService;
    private final DeliveryTypeService deliveryTypeService;

    public double calculateFee(String logisticsId) {
        Logistics logistics = logisticsService.getLogisticsById(logisticsId);

        DeliveryType deliveryType = deliveryTypeService.getDeliveryType(logistics.getDeliveryTypeCode());

        List<Track> tracks = trackService.getRawTracksByLogisticsId(logisticsId);

        double distance = estimateDistance(tracks);
        double durationHours = calculateDuration(logistics, tracks);

        double fee = calculateFee(distance, durationHours, deliveryType);

        log.info("计算费用 - logisticsId: {}, deliveryType: {}, distance: {}, duration: {}, fee: {}",
                logisticsId, deliveryType.getTypeName(), distance, durationHours, fee);

        return fee;
    }

    private double estimateDistance(List<Track> tracks) {
        if (tracks == null || tracks.size() < 2) {
            return 1.0;
        }
        return Math.max(1.0, tracks.size() * 0.5);
    }

    private double calculateDuration(Logistics logistics, List<Track> tracks) {
        LocalDateTime startTime = logistics.getShippingTime();
        LocalDateTime endTime = logistics.getDeliveryTime();

        if (startTime == null || endTime == null) {
            return 1.0;
        }

        Duration duration = Duration.between(startTime, endTime);
        return Math.max(0.5, duration.toHours());
    }

    public double calculateFee(double distance, double durationHours, DeliveryType deliveryType) {
        double fee = deliveryType.getBaseFee() 
                + (distance * deliveryType.getDistanceRate()) 
                + (durationHours * deliveryType.getTimeRate());

        if (fee < deliveryType.getMinFee()) {
            fee = deliveryType.getMinFee();
        }
        if (fee > deliveryType.getMaxFee()) {
            fee = deliveryType.getMaxFee();
        }

        return Math.round(fee * 100.0) / 100.0;
    }

    public double calculateFee(double distance, double durationHours) {
        DeliveryType defaultType = deliveryTypeService.getDefaultDeliveryType();
        return calculateFee(distance, durationHours, defaultType);
    }
}
