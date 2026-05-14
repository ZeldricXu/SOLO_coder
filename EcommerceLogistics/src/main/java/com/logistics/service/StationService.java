package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Station;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.StationRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StationService {

    private final StationRepository stationRepository;

    public Station createStation(Station station) {
        if (station.getStationId() == null) {
            station.setStationId(IdGenerator.generateStationId());
        }
        if (station.getStationStatus() == null) {
            station.setStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
        }
        return stationRepository.save(station);
    }

    public Station getStationById(String stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new LogisticsException("网点不存在"));
    }

    public List<Station> getAllStations() {
        return stationRepository.findAll();
    }

    public List<Station> getActiveStations() {
        return stationRepository.findByStationStatus(LogisticsConstants.STATION_STATUS_ACTIVE);
    }

    @Transactional
    public Station updateStation(String stationId, Station stationDetails) {
        Station station = getStationById(stationId);
        if (stationDetails.getStationName() != null) {
            station.setStationName(stationDetails.getStationName());
        }
        if (stationDetails.getStationAddress() != null) {
            station.setStationAddress(stationDetails.getStationAddress());
        }
        if (stationDetails.getStationRegion() != null) {
            station.setStationRegion(stationDetails.getStationRegion());
        }
        if (stationDetails.getStationCapacity() != null) {
            station.setStationCapacity(stationDetails.getStationCapacity());
        }
        if (stationDetails.getStationStatus() != null) {
            station.setStationStatus(stationDetails.getStationStatus());
        }
        return stationRepository.save(station);
    }

    @Transactional
    public void incrementStationCurrent(String stationId) {
        Station station = getStationById(stationId);
        station.setStationCurrent(station.getStationCurrent() + 1);
        stationRepository.save(station);
    }

    @Transactional
    public void decrementStationCurrent(String stationId) {
        Station station = getStationById(stationId);
        if (station.getStationCurrent() > 0) {
            station.setStationCurrent(station.getStationCurrent() - 1);
            stationRepository.save(station);
        }
    }

    @Transactional
    public void deleteStation(String stationId) {
        Station station = getStationById(stationId);
        station.setStationStatus(LogisticsConstants.STATION_STATUS_CLOSED);
        stationRepository.save(station);
    }
}
