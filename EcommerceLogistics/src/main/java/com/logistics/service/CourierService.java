package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Courier;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.CourierRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CourierService {

    private final CourierRepository courierRepository;

    public Courier createCourier(Courier courier) {
        if (courier.getCourierId() == null) {
            courier.setCourierId(IdGenerator.generateCourierId());
        }
        if (courier.getCourierStatus() == null) {
            courier.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
        }
        return courierRepository.save(courier);
    }

    public Courier getCourierById(String courierId) {
        return courierRepository.findById(courierId)
                .orElseThrow(() -> new LogisticsException("配送员不存在"));
    }

    public List<Courier> getAllCouriers() {
        return courierRepository.findAll();
    }

    public List<Courier> getCouriersByStation(String stationId) {
        return courierRepository.findByCourierStation(stationId);
    }

    public List<Courier> getAvailableCouriers() {
        return courierRepository.findByCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
    }

    public List<Courier> getAvailableCouriersByStation(String stationId) {
        return courierRepository.findByCourierStationAndCourierStatus(stationId, LogisticsConstants.COURIER_STATUS_AVAILABLE);
    }

    public Optional<Courier> selectBestCourier(String stationId) {
        List<Courier> availableCouriers = getAvailableCouriersByStation(stationId);
        if (availableCouriers.isEmpty()) {
            return Optional.empty();
        }

        return availableCouriers.stream()
                .filter(courier -> courier.getCourierCurrent() < courier.getCourierCapacity())
                .min(Comparator.comparing(Courier::getCourierCurrent)
                        .thenComparing(Comparator.comparing(Courier::getCourierRating).reversed()));
    }

    @Transactional
    public Courier updateCourier(String courierId, Courier courierDetails) {
        Courier courier = getCourierById(courierId);
        if (courierDetails.getCourierName() != null) {
            courier.setCourierName(courierDetails.getCourierName());
        }
        if (courierDetails.getCourierPhone() != null) {
            courier.setCourierPhone(courierDetails.getCourierPhone());
        }
        if (courierDetails.getCourierStation() != null) {
            courier.setCourierStation(courierDetails.getCourierStation());
        }
        if (courierDetails.getCourierCapacity() != null) {
            courier.setCourierCapacity(courierDetails.getCourierCapacity());
        }
        return courierRepository.save(courier);
    }

    @Transactional
    public void updateCourierStatus(String courierId, String status) {
        Courier courier = getCourierById(courierId);
        courier.setCourierStatus(status);
        courierRepository.save(courier);
    }

    @Transactional
    public void incrementCourierCurrent(String courierId) {
        Courier courier = getCourierById(courierId);
        courier.setCourierCurrent(courier.getCourierCurrent() + 1);
        courierRepository.save(courier);
    }

    @Transactional
    public void decrementCourierCurrent(String courierId) {
        Courier courier = getCourierById(courierId);
        if (courier.getCourierCurrent() > 0) {
            courier.setCourierCurrent(courier.getCourierCurrent() - 1);
            courierRepository.save(courier);
        }
    }

    @Transactional
    public void checkAndSetAvailable(String courierId) {
        Courier courier = getCourierById(courierId);
        if (courier.getCourierCurrent() <= 0) {
            courier.setCourierStatus(LogisticsConstants.COURIER_STATUS_AVAILABLE);
            courierRepository.save(courier);
        }
    }

    @Transactional
    public void deleteCourier(String courierId) {
        updateCourierStatus(courierId, LogisticsConstants.COURIER_STATUS_OFFLINE);
    }
}
