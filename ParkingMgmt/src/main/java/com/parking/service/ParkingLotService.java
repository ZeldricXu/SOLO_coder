package com.parking.service;

import com.parking.entity.ParkingLot;
import com.parking.exception.ParkingException;
import com.parking.repository.ParkingLotRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ParkingLotService {

    @Autowired
    private ParkingLotRepository parkingLotRepository;

    @Transactional
    public ParkingLot createParkingLot(String name, String address, int totalSpaces, double hourlyRate, String chargingType, Double fixedFee) {
        ParkingLot parkingLot = new ParkingLot();
        parkingLot.setParkingId(IdGenerator.generateParkingId());
        parkingLot.setName(name);
        parkingLot.setAddress(address);
        parkingLot.setTotalSpaces(totalSpaces);
        parkingLot.setHourlyRate(hourlyRate);
        parkingLot.setChargingType(chargingType != null ? chargingType : "hourly");
        parkingLot.setFixedFee(fixedFee);

        return parkingLotRepository.save(parkingLot);
    }

    public ParkingLot getParkingLotById(String parkingId) {
        return parkingLotRepository.findByParkingId(parkingId)
                .orElseThrow(() -> new ParkingException(404, "停车场不存在: " + parkingId));
    }

    public List<ParkingLot> getAllParkingLots() {
        return parkingLotRepository.findAll();
    }

    @Transactional
    public ParkingLot updateParkingLot(String parkingId, String name, String address, Integer totalSpaces, Double hourlyRate, String chargingType, Double fixedFee) {
        ParkingLot parkingLot = getParkingLotById(parkingId);

        if (name != null) parkingLot.setName(name);
        if (address != null) parkingLot.setAddress(address);
        if (totalSpaces != null) parkingLot.setTotalSpaces(totalSpaces);
        if (hourlyRate != null) parkingLot.setHourlyRate(hourlyRate);
        if (chargingType != null) parkingLot.setChargingType(chargingType);
        if (fixedFee != null) parkingLot.setFixedFee(fixedFee);

        return parkingLotRepository.save(parkingLot);
    }

    @Transactional
    public void deleteParkingLot(String parkingId) {
        ParkingLot parkingLot = getParkingLotById(parkingId);
        parkingLotRepository.delete(parkingLot);
    }
}
