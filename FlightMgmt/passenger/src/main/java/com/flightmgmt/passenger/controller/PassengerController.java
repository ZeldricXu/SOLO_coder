package com.flightmgmt.passenger.controller;

import com.flightmgmt.common.model.Passenger;
import com.flightmgmt.passenger.service.PassengerService;

import java.util.List;

public class PassengerController {
    private PassengerService passengerService = new PassengerService();

    public Passenger createPassenger(Passenger passenger) {
        return passengerService.createPassenger(passenger);
    }

    public Passenger getPassenger(String passengerId) {
        return passengerService.getPassenger(passengerId);
    }

    public List<Passenger> getAllPassengers() {
        return passengerService.getAllPassengers();
    }

    public Passenger updatePassenger(String passengerId, Passenger passenger) {
        return passengerService.updatePassenger(passengerId, passenger);
    }

    public boolean deletePassenger(String passengerId) {
        return passengerService.deletePassenger(passengerId);
    }
}
