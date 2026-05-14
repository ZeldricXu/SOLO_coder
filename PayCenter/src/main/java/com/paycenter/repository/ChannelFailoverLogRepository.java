package com.paycenter.repository;

import com.paycenter.entity.ChannelFailoverLog;
import com.paycenter.enums.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChannelFailoverLogRepository extends JpaRepository<ChannelFailoverLog, Long> {
    List<ChannelFailoverLog> findByMerchantIdAndChannelTypeOrderByCreatedAtDesc(
            String merchantId, ChannelType channelType);
    
    @Query("SELECT COUNT(f) FROM ChannelFailoverLog f WHERE f.merchantId = :merchantId " +
           "AND f.channelType = :channelType AND f.createdAt >= :since")
    Long countRecentFailures(@Param("merchantId") String merchantId,
                              @Param("channelType") ChannelType channelType,
                              @Param("since") LocalDateTime since);
}
