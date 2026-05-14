package com.paycenter.repository;

import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentChannelRepository extends JpaRepository<PaymentChannel, String> {
    Optional<PaymentChannel> findByChannelIdAndStatusTrue(String channelId);
    List<PaymentChannel> findByStatusTrue();
    Optional<PaymentChannel> findByChannelTypeAndStatusTrue(ChannelType channelType);
}
