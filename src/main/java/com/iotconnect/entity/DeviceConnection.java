package com.iotconnect.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_connections")
public class DeviceConnection {

    @Id
    @Column(name = "connection_id", length = 64)
    private String connectionId;

    @Column(name = "device_id", length = 64, nullable = false)
    private String deviceId;

    @Column(name = "connection_status", length = 32, nullable = false)
    private String connectionStatus;

    @Column(name = "last_heartbeat")
    private LocalDateTime lastHeartbeat;

    @Column(name = "connection_time")
    private LocalDateTime connectionTime;

    @Column(name = "disconnection_time")
    private LocalDateTime disconnectionTime;

    @Column(name = "client_address", length = 128)
    private String clientAddress;

    @Column(name = "protocol_version", length = 32)
    private String protocolVersion;

    public DeviceConnection() {
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public LocalDateTime getLastHeartbeat() {
        return lastHeartbeat;
    }

    public void setLastHeartbeat(LocalDateTime lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }

    public LocalDateTime getConnectionTime() {
        return connectionTime;
    }

    public void setConnectionTime(LocalDateTime connectionTime) {
        this.connectionTime = connectionTime;
    }

    public LocalDateTime getDisconnectionTime() {
        return disconnectionTime;
    }

    public void setDisconnectionTime(LocalDateTime disconnectionTime) {
        this.disconnectionTime = disconnectionTime;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }
}
