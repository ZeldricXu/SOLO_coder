package com.iotconnect.service;

import com.iotconnect.entity.DeviceConnection;
import com.iotconnect.enums.ConnectionStatus;
import com.iotconnect.reconnect.DeviceReconnectManager;
import com.iotconnect.repository.DeviceConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ConnectionService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionService.class);

    private final DeviceConnectionRepository connectionRepository;
    private final DeviceService deviceService;
    private final DeviceReconnectManager reconnectManager;

    @Value("${heartbeat.timeout-seconds:120}")
    private int heartbeatTimeoutSeconds;

    public ConnectionService(DeviceConnectionRepository connectionRepository, 
                              DeviceService deviceService,
                              DeviceReconnectManager reconnectManager) {
        this.connectionRepository = connectionRepository;
        this.deviceService = deviceService;
        this.reconnectManager = reconnectManager;
    }

    @PostConstruct
    public void init() {
        reconnectManager.setReconnectSuccessCallback(this::onReconnectSuccess);
        reconnectManager.setReconnectFailureCallback(this::onReconnectFailure);
        logger.info("ConnectionService initialized with reconnect: enabled={}", 
                reconnectManager.isReconnectEnabled());
    }

    @Transactional
    public DeviceConnection handleDeviceConnect(String deviceId, String clientAddress, String protocolVersion) {
        reconnectManager.onDeviceConnect(deviceId);

        Optional<DeviceConnection> existingConn = connectionRepository.findByDeviceId(deviceId);
        
        DeviceConnection connection;
        if (existingConn.isPresent()) {
            connection = existingConn.get();
            connection.setConnectionStatus(ConnectionStatus.ONLINE.getValue());
            connection.setLastHeartbeat(LocalDateTime.now());
            if (clientAddress != null) {
                connection.setClientAddress(clientAddress);
            }
            if (protocolVersion != null) {
                connection.setProtocolVersion(protocolVersion);
            }
        } else {
            connection = new DeviceConnection();
            connection.setConnectionId(generateConnectionId());
            connection.setDeviceId(deviceId);
            connection.setConnectionStatus(ConnectionStatus.ONLINE.getValue());
            connection.setLastHeartbeat(LocalDateTime.now());
            connection.setConnectionTime(LocalDateTime.now());
            connection.setClientAddress(clientAddress);
            connection.setProtocolVersion(protocolVersion);
        }

        DeviceConnection savedConnection = connectionRepository.save(connection);
        deviceService.updateConnectionStatus(deviceId, ConnectionStatus.ONLINE);

        logger.info("Device connected: deviceId={}, connectionId={}", deviceId, savedConnection.getConnectionId());
        return savedConnection;
    }

    @Transactional
    public DeviceConnection handleDeviceDisconnect(String deviceId) {
        Optional<DeviceConnection> existingConn = connectionRepository.findByDeviceId(deviceId);
        
        if (existingConn.isPresent()) {
            DeviceConnection connection = existingConn.get();
            connection.setConnectionStatus(ConnectionStatus.OFFLINE.getValue());
            connection.setDisconnectionTime(LocalDateTime.now());
            
            DeviceConnection savedConnection = connectionRepository.save(connection);
            deviceService.updateConnectionStatus(deviceId, ConnectionStatus.OFFLINE);

            logger.info("Device disconnected: deviceId={}", deviceId);
            
            reconnectManager.onDeviceDisconnect(deviceId);

            return savedConnection;
        }
        
        return null;
    }

    @Transactional
    public DeviceConnection handleHeartbeat(String deviceId) {
        Optional<DeviceConnection> existingConn = connectionRepository.findByDeviceId(deviceId);
        
        if (existingConn.isPresent()) {
            DeviceConnection connection = existingConn.get();
            connection.setLastHeartbeat(LocalDateTime.now());
            
            if (!ConnectionStatus.ONLINE.getValue().equals(connection.getConnectionStatus())) {
                connection.setConnectionStatus(ConnectionStatus.ONLINE.getValue());
                deviceService.updateConnectionStatus(deviceId, ConnectionStatus.ONLINE);
                reconnectManager.onDeviceConnect(deviceId);
            }
            
            deviceService.updateLastActive(deviceId);
            DeviceConnection savedConnection = connectionRepository.save(connection);
            
            logger.debug("Heartbeat received: deviceId={}", deviceId);
            return savedConnection;
        }
        
        return handleDeviceConnect(deviceId, null, null);
    }

    private void onReconnectSuccess(String deviceId) {
        logger.info("Device reconnect success callback: deviceId={}", deviceId);
        
        Optional<DeviceConnection> connOpt = connectionRepository.findByDeviceId(deviceId);
        if (connOpt.isPresent()) {
            DeviceConnection conn = connOpt.get();
            conn.setConnectionStatus(ConnectionStatus.ONLINE.getValue());
            conn.setLastHeartbeat(LocalDateTime.now());
            conn.setConnectionTime(LocalDateTime.now());
            connectionRepository.save(conn);
            deviceService.updateConnectionStatus(deviceId, ConnectionStatus.ONLINE);
        }
    }

    private void onReconnectFailure(String deviceId) {
        logger.warn("Device reconnect max attempts reached, giving up: deviceId={}", deviceId);
    }

    public Optional<DeviceConnection> getConnection(String deviceId) {
        return connectionRepository.findByDeviceId(deviceId);
    }

    public List<DeviceConnection> getConnectionsByStatus(ConnectionStatus status) {
        return connectionRepository.findByConnectionStatus(status.getValue());
    }

    public boolean isDeviceOnline(String deviceId) {
        return connectionRepository.existsByDeviceIdAndConnectionStatus(deviceId, ConnectionStatus.ONLINE.getValue());
    }

    public long getOnlineConnectionCount() {
        return connectionRepository.countByConnectionStatus(ConnectionStatus.ONLINE.getValue());
    }

    public long getOfflineConnectionCount() {
        return connectionRepository.countByConnectionStatus(ConnectionStatus.OFFLINE.getValue());
    }

    @Scheduled(fixedDelayString = "${heartbeat.check-interval-seconds:30000}")
    @Transactional
    public void checkHeartbeatTimeout() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        List<DeviceConnection> expiredConnections = connectionRepository.findConnectionsWithExpiredHeartbeat(timeoutThreshold);

        for (DeviceConnection connection : expiredConnections) {
            connection.setConnectionStatus(ConnectionStatus.OFFLINE.getValue());
            connection.setDisconnectionTime(LocalDateTime.now());
            connectionRepository.save(connection);

            deviceService.updateConnectionStatus(connection.getDeviceId(), ConnectionStatus.OFFLINE);

            logger.warn("Device marked offline due to heartbeat timeout: deviceId={}", connection.getDeviceId());
            
            reconnectManager.onDeviceDisconnect(connection.getDeviceId());
        }
    }

    public List<DeviceConnection> getConnectionHistory(String deviceId) {
        return connectionRepository.findConnectionHistoryByDeviceId(deviceId);
    }

    public boolean isReconnectInProgress(String deviceId) {
        return reconnectManager.isReconnectInProgress(deviceId);
    }

    public DeviceReconnectManager.ReconnectState getReconnectState(String deviceId) {
        return reconnectManager.getReconnectState(deviceId);
    }

    public int getActiveReconnectCount() {
        return reconnectManager.getActiveReconnectCount();
    }

    public void cancelReconnect(String deviceId) {
        reconnectManager.cancelReconnect(deviceId);
    }

    private String generateConnectionId() {
        return "conn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
