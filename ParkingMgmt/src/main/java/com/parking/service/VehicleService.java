package com.parking.service;

import com.parking.entity.Vehicle;
import com.parking.exception.ParkingException;
import com.parking.repository.VehicleRepository;
import com.parking.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class VehicleService {

    @Autowired
    private VehicleRepository vehicleRepository;

    @Transactional
    public Vehicle createOrGetVehicle(String vehicleNumber, String vehicleType, String vehicleOwner, String vehiclePhone) {
        Optional<Vehicle> existingVehicle = vehicleRepository.findByVehicleNumber(vehicleNumber);
        
        if (existingVehicle.isPresent()) {
            return existingVehicle.get();
        }

        Vehicle vehicle = new Vehicle();
        vehicle.setVehicleId(IdGenerator.generateVehicleId());
        vehicle.setVehicleNumber(vehicleNumber);
        vehicle.setVehicleType(vehicleType != null ? vehicleType : "sedan");
        vehicle.setVehicleOwner(vehicleOwner);
        vehicle.setVehiclePhone(vehiclePhone);
        vehicle.setCurrentStatus("idle");

        return vehicleRepository.save(vehicle);
    }

    public Vehicle getVehicleById(String vehicleId) {
        return vehicleRepository.findByVehicleId(vehicleId)
                .orElseThrow(() -> new ParkingException(404, "车辆不存在: " + vehicleId));
    }

    public Vehicle getVehicleByNumber(String vehicleNumber) {
        return vehicleRepository.findByVehicleNumber(vehicleNumber)
                .orElse(null);
    }

    public List<Vehicle> getAllVehicles() {
        return vehicleRepository.findAll();
    }

    @Transactional
    public Vehicle updateVehicleStatus(String vehicleId, String status) {
        Vehicle vehicle = getVehicleById(vehicleId);
        vehicle.setCurrentStatus(status);
        return vehicleRepository.save(vehicle);
    }

    @Transactional
    public void deleteVehicle(String vehicleId) {
        Vehicle vehicle = getVehicleById(vehicleId);
        vehicleRepository.delete(vehicle);
    }
}
