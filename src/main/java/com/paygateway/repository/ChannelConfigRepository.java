package com.paygateway.repository;

import com.paygateway.entity.ChannelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelConfigRepository extends JpaRepository<ChannelConfig, Long> {
    
    Optional<ChannelConfig> findByMerchantIdAndChannel(String merchantId, String channel);
    
    List<ChannelConfig> findByMerchantId(String merchantId);
    
    List<ChannelConfig> findByMerchantIdAndEnabledTrue(String merchantId);
    
    boolean existsByMerchantIdAndChannel(String merchantId, String channel);
}
