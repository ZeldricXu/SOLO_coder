package com.edgescheduler.protocol.service;

import com.edgescheduler.protocol.dto.ProtocolAdapterDTO;
import com.edgescheduler.protocol.dto.ProtocolDriverDTO;
import com.edgescheduler.protocol.entity.ProtocolAdapter;
import com.edgescheduler.protocol.entity.ProtocolDriver;

import java.util.List;
import java.util.Map;

public interface ProtocolService {

    ProtocolDriverDTO registerDriver(ProtocolDriverDTO driverDTO);

    ProtocolDriverDTO getDriver(String driverId);

    List<ProtocolDriver> listDrivers(String protocolType, String status);

    ProtocolDriverDTO updateDriverStatus(String driverId, String status);

    void deleteDriver(String driverId);

    ProtocolAdapterDTO createAdapter(ProtocolAdapterDTO adapterDTO);

    ProtocolAdapterDTO getAdapter(String adapterId);

    List<ProtocolAdapter> listAdapters(String driverId, String deviceKey, String status);

    ProtocolAdapterDTO updateAdapterConfig(String adapterId, Map<String, Object> config);

    ProtocolAdapterDTO connectAdapter(String adapterId);

    ProtocolAdapterDTO disconnectAdapter(String adapterId);

    ProtocolAdapterDTO updateAdapterStatus(String adapterId, String status);

    void deleteAdapter(String adapterId);

    Map<String, Object> convertData(String driverId, Map<String, Object> rawData);

    Map<String, Object> sendCommand(String adapterId, Map<String, Object> command);

    void recordMessage(String adapterId, boolean success, String error);

    List<ProtocolAdapter> getDeviceAdapters(String deviceKey);

    Map<String, Object> getAdapterMetrics(String adapterId);
}
