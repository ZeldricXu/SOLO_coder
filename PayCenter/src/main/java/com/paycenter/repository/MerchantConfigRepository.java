package com.paycenter.repository;

import com.paycenter.entity.MerchantConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantConfigRepository extends JpaRepository<MerchantConfig, Long> {
    Optional<MerchantConfig> findByMerchantId(String merchantId);
    
    @Query("SELECT COUNT(DISTINCT mc.merchantId) FROM MerchantConfig mc")
    Long countActiveMerchants();
}
