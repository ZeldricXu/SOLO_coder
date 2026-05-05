package com.paygateway.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "channel_config", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"merchantId", "channel"})
})
public class ChannelConfig {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;
    
    @Column(name = "channel", nullable = false, length = 32)
    private String channel;
    
    @Column(name = "channel_merchant_id", nullable = false, length = 128)
    private String channelMerchantId;
    
    @Column(name = "app_id", nullable = false, length = 128)
    private String appId;
    
    @Column(name = "private_key", columnDefinition = "TEXT")
    private String privateKey;
    
    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;
    
    @Column(name = "notify_url", length = 255)
    private String notifyUrl;
    
    @Column(name = "enabled")
    private Boolean enabled = true;
    
    @Column(name = "priority")
    private Integer priority = 0;
    
    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
