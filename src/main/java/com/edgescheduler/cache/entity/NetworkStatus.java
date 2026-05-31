package com.edgescheduler.cache.entity;

import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("network_status")
public class NetworkStatus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String statusId;
    private String networkName;
    private String connectionType;
    private String status;
    private Double signalStrength;
    private Integer latencyMs;
    private Double packetLossRate;
    private Long bandwidthUp;
    private Long bandwidthDown;
    private LocalDateTime lastConnectedAt;
    private LocalDateTime lastDisconnectedAt;
    private Long totalOfflineTime;
    private Long totalOnlineTime;

    public interface Status {
        String ONLINE = "online";
        String OFFLINE = "offline";
        String DEGRADED = "degraded";
        String UNKNOWN = "unknown";
    }

    public interface ConnectionType {
        String WIFI = "wifi";
        String ETHERNET = "ethernet";
        String CELLULAR_4G = "4g";
        String CELLULAR_5G = "5g";
        String LORA = "lora";
        String SATELLITE = "satellite";
    }
}
