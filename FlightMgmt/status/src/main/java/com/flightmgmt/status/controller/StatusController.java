package com.flightmgmt.status.controller;

import com.flightmgmt.common.model.FlightStatus;
import com.flightmgmt.status.service.StatusService;

import java.util.List;

public class StatusController {
    private StatusService statusService = new StatusService();

    public FlightStatus updateFlightStatus(String flightId, String statusType, String detail) {
        return statusService.updateFlightStatus(flightId, statusType, detail);
    }

    public List<FlightStatus> getFlightStatusHistory(String flightId) {
        return statusService.getFlightStatusHistory(flightId);
    }

    public FlightStatus getLatestFlightStatus(String flightId) {
        return statusService.getLatestFlightStatus(flightId);
    }
}
