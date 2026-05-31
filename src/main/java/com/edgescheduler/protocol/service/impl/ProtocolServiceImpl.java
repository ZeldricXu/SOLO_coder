package com.edgescheduler.protocol.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.protocol.dto.ProtocolAdapterDTO;
import com.edgescheduler.protocol.dto.ProtocolDriverDTO;
import com.edgescheduler.protocol.entity.ProtocolAdapter;
import com.edgescheduler.protocol.entity.ProtocolDriver;
import com.edgescheduler.protocol.mapper.ProtocolAdapterMapper;
import com.edgescheduler.protocol.mapper.ProtocolDriverMapper;
import com.edgescheduler.protocol.service.ProtocolService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProtocolServiceImpl implements ProtocolService {

    private final ProtocolDriverMapper driverMapper;
    private final ProtocolAdapterMapper adapterMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public ProtocolDriverDTO registerDriver(ProtocolDriverDTO driverDTO) {
        ProtocolDriver driver = new ProtocolDriver();
        BeanUtils.copyProperties(driverDTO, driver);
        driver.setDriverId("drv_" + IdUtil.getSnowflakeNextIdStr());
        driver.setStatus(ProtocolDriver.Status.LOADED);
        driverMapper.insert(driver);
        meterRegistry.counter("protocol.driver.register.total").increment();
        log.info("Protocol driver registered: {}", driver.getDriverId());
        return convertDriverToDTO(driver);
    }

    @Override
    public ProtocolDriverDTO getDriver(String driverId) {
        ProtocolDriver driver = getDriverEntity(driverId);
        return convertDriverToDTO(driver);
    }

    @Override
    public List<ProtocolDriver> listDrivers(String protocolType, String status) {
        LambdaQueryWrapper<ProtocolDriver> wrapper = new LambdaQueryWrapper<>();
        if (protocolType != null) wrapper.eq(ProtocolDriver::getProtocolType, protocolType);
        if (status != null) wrapper.eq(ProtocolDriver::getStatus, status);
        wrapper.orderByDesc(ProtocolDriver::getCreatedAt);
        return driverMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public ProtocolDriverDTO updateDriverStatus(String driverId, String status) {
        ProtocolDriver driver = getDriverEntity(driverId);
        driver.setStatus(status);
        driverMapper.updateById(driver);
        log.info("Protocol driver status updated: {} -> {}", driverId, status);
        return convertDriverToDTO(driver);
    }

    @Override
    @Transactional
    public void deleteDriver(String driverId) {
        List<ProtocolAdapter> adapters = adapterMapper.selectByDriverId(driverId);
        if (!adapters.isEmpty()) {
            throw new BusinessException("Cannot delete driver with active adapters");
        }
        ProtocolDriver driver = getDriverEntity(driverId);
        driverMapper.deleteById(driver.getId());
        log.info("Protocol driver deleted: {}", driverId);
    }

    @Override
    @Transactional
    public ProtocolAdapterDTO createAdapter(ProtocolAdapterDTO adapterDTO) {
        ProtocolDriver driver = driverMapper.selectByDriverId(adapterDTO.getDriverId());
        if (driver == null) {
            throw BusinessException.notFound("Driver not found: " + adapterDTO.getDriverId());
        }

        ProtocolAdapter adapter = new ProtocolAdapter();
        BeanUtils.copyProperties(adapterDTO, adapter);
        adapter.setAdapterId("adp_" + IdUtil.getSnowflakeNextIdStr());
        adapter.setStatus(ProtocolAdapter.Status.CREATED);
        adapter.setTotalMessages(0L);
        adapter.setErrorMessages(0L);
        adapterMapper.insert(adapter);

        meterRegistry.counter("protocol.adapter.create.total").increment();
        log.info("Protocol adapter created: {}", adapter.getAdapterId());

        return convertAdapterToDTO(adapter);
    }

    @Override
    public ProtocolAdapterDTO getAdapter(String adapterId) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        return convertAdapterToDTO(adapter);
    }

    @Override
    public List<ProtocolAdapter> listAdapters(String driverId, String deviceKey, String status) {
        LambdaQueryWrapper<ProtocolAdapter> wrapper = new LambdaQueryWrapper<>();
        if (driverId != null) wrapper.eq(ProtocolAdapter::getDriverId, driverId);
        if (deviceKey != null) wrapper.eq(ProtocolAdapter::getDeviceKey, deviceKey);
        if (status != null) wrapper.eq(ProtocolAdapter::getStatus, status);
        wrapper.orderByDesc(ProtocolAdapter::getCreatedAt);
        return adapterMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public ProtocolAdapterDTO updateAdapterConfig(String adapterId, Map<String, Object> config) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        adapter.setAdapterConfig(config);
        adapterMapper.updateById(adapter);
        log.info("Protocol adapter config updated: {}", adapterId);
        return convertAdapterToDTO(adapter);
    }

    @Override
    @Transactional
    public ProtocolAdapterDTO connectAdapter(String adapterId) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        if (ProtocolAdapter.Status.CONNECTED.equals(adapter.getStatus())) {
            return convertAdapterToDTO(adapter);
        }

        adapter.setStatus(ProtocolAdapter.Status.CONNECTING);
        adapterMapper.updateById(adapter);

        adapter.setStatus(ProtocolAdapter.Status.CONNECTED);
        adapter.setLastConnectedAt(LocalDateTime.now());
        adapterMapper.updateById(adapter);

        log.info("Protocol adapter connected: {}", adapterId);
        return convertAdapterToDTO(adapter);
    }

    @Override
    @Transactional
    public ProtocolAdapterDTO disconnectAdapter(String adapterId) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        adapter.setStatus(ProtocolAdapter.Status.DISCONNECTED);
        adapter.setLastDisconnectedAt(LocalDateTime.now());
        adapterMapper.updateById(adapter);
        log.info("Protocol adapter disconnected: {}", adapterId);
        return convertAdapterToDTO(adapter);
    }

    @Override
    @Transactional
    public ProtocolAdapterDTO updateAdapterStatus(String adapterId, String status) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        adapter.setStatus(status);
        if (ProtocolAdapter.Status.CONNECTED.equals(status)) {
            adapter.setLastConnectedAt(LocalDateTime.now());
        } else if (ProtocolAdapter.Status.DISCONNECTED.equals(status) || ProtocolAdapter.Status.ERROR.equals(status)) {
            adapter.setLastDisconnectedAt(LocalDateTime.now());
        }
        adapterMapper.updateById(adapter);
        log.info("Protocol adapter status updated: {} -> {}", adapterId, status);
        return convertAdapterToDTO(adapter);
    }

    @Override
    @Transactional
    public void deleteAdapter(String adapterId) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        if (ProtocolAdapter.Status.CONNECTED.equals(adapter.getStatus())) {
            disconnectAdapter(adapterId);
        }
        adapterMapper.deleteById(adapter.getId());
        log.info("Protocol adapter deleted: {}", adapterId);
    }

    @Override
    public Map<String, Object> convertData(String driverId, Map<String, Object> rawData) {
        ProtocolDriver driver = getDriverEntity(driverId);
        Map<String, Object> dataMapping = driver.getDataMapping();

        if (dataMapping == null || dataMapping.isEmpty()) {
            return rawData;
        }

        Map<String, Object> converted = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawData.entrySet()) {
            String sourceKey = entry.getKey();
            Object value = entry.getValue();
            String targetKey = dataMapping.containsKey(sourceKey) ?
                    (String) dataMapping.get(sourceKey) : sourceKey;
            converted.put(targetKey, value);
        }

        meterRegistry.counter("protocol.data.convert.total").increment();
        return converted;
    }

    @Override
    public Map<String, Object> sendCommand(String adapterId, Map<String, Object> command) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        if (!ProtocolAdapter.Status.CONNECTED.equals(adapter.getStatus())) {
            throw new BusinessException("Adapter is not connected: " + adapter.getStatus());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "sent");
        result.put("adapterId", adapterId);
        result.put("command", command);
        result.put("timestamp", LocalDateTime.now().toString());

        meterRegistry.counter("protocol.command.send.total").increment();
        log.info("Command sent via adapter {}: {}", adapterId, command);

        return result;
    }

    @Override
    @Transactional
    public void recordMessage(String adapterId, boolean success, String error) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        adapter.setTotalMessages(adapter.getTotalMessages() + 1);
        if (!success) {
            adapter.setErrorMessages(adapter.getErrorMessages() + 1);
            adapter.setLastError(error);
        }
        adapterMapper.updateById(adapter);
    }

    @Override
    public List<ProtocolAdapter> getDeviceAdapters(String deviceKey) {
        return adapterMapper.selectByDeviceKey(deviceKey);
    }

    @Override
    public Map<String, Object> getAdapterMetrics(String adapterId) {
        ProtocolAdapter adapter = getAdapterEntity(adapterId);
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("adapterId", adapterId);
        metrics.put("status", adapter.getStatus());
        metrics.put("totalMessages", adapter.getTotalMessages());
        metrics.put("errorMessages", adapter.getErrorMessages());
        metrics.put("lastError", adapter.getLastError());
        metrics.put("lastConnectedAt", adapter.getLastConnectedAt());
        metrics.put("lastDisconnectedAt", adapter.getLastDisconnectedAt());
        double errorRate = adapter.getTotalMessages() > 0 ?
                (double) adapter.getErrorMessages() / adapter.getTotalMessages() : 0.0;
        metrics.put("errorRate", errorRate);
        return metrics;
    }

    private ProtocolDriver getDriverEntity(String driverId) {
        ProtocolDriver driver = driverMapper.selectByDriverId(driverId);
        if (driver == null) {
            throw BusinessException.notFound("Driver not found: " + driverId);
        }
        return driver;
    }

    private ProtocolAdapter getAdapterEntity(String adapterId) {
        ProtocolAdapter adapter = adapterMapper.selectByAdapterId(adapterId);
        if (adapter == null) {
            throw BusinessException.notFound("Adapter not found: " + adapterId);
        }
        return adapter;
    }

    private ProtocolDriverDTO convertDriverToDTO(ProtocolDriver driver) {
        ProtocolDriverDTO dto = new ProtocolDriverDTO();
        BeanUtils.copyProperties(driver, dto);
        return dto;
    }

    private ProtocolAdapterDTO convertAdapterToDTO(ProtocolAdapter adapter) {
        ProtocolAdapterDTO dto = new ProtocolAdapterDTO();
        BeanUtils.copyProperties(adapter, dto);
        return dto;
    }
}
