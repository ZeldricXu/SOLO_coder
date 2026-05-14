package com.flightmgmt.passenger.service;

import com.flightmgmt.common.model.Passenger;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.common.util.IdGenerator;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class PassengerService {
    public Passenger createPassenger(Passenger passenger) {
        passenger.setPassengerId(IdGenerator.generatePassengerId());
        passenger.setCreatedAt(LocalDateTime.now());
        if (passenger.getPassengerIdType() == null) {
            passenger.setPassengerIdType("id_card");
        }
        DataStore.addPassenger(passenger);
        return passenger;
    }

    public Passenger getPassenger(String passengerId) {
        return DataStore.getPassenger(passengerId);
    }

    public List<Passenger> getAllPassengers() {
        return DataStore.getPassengers().values().stream().collect(Collectors.toList());
    }

    public Passenger findByIdNumber(String idNumber) {
        return DataStore.getPassengers().values().stream()
            .filter(p -> p.getPassengerIdNumber() != null && 
                    p.getPassengerIdNumber().equals(idNumber))
            .findFirst()
            .orElse(null);
    }

    public Passenger updatePassenger(String passengerId, Passenger passenger) {
        Passenger existing = DataStore.getPassenger(passengerId);
        if (existing == null) {
            return null;
        }
        existing.setPassengerName(passenger.getPassengerName());
        existing.setPassengerIdType(passenger.getPassengerIdType());
        existing.setPassengerIdNumber(passenger.getPassengerIdNumber());
        existing.setPassengerPhone(passenger.getPassengerPhone());
        return existing;
    }

    public boolean deletePassenger(String passengerId) {
        return DataStore.getPassengers().remove(passengerId) != null;
    }
}
