package com.paycenter.controller;

import com.paycenter.dto.ApiResponse;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.service.PaymentChannelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

    @Autowired
    private PaymentChannelService paymentChannelService;

    @PostMapping
    public ApiResponse<PaymentChannel> createChannel(@RequestBody PaymentChannel channel) {
        PaymentChannel created = paymentChannelService.createChannel(channel);
        return ApiResponse.success(created);
    }

    @PutMapping("/{channelId}")
    public ApiResponse<PaymentChannel> updateChannel(
            @PathVariable String channelId,
            @RequestBody PaymentChannel channel) {
        channel.setChannelId(channelId);
        PaymentChannel updated = paymentChannelService.updateChannel(channel);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{channelId}")
    public ApiResponse<Void> deleteChannel(@PathVariable String channelId) {
        paymentChannelService.deleteChannel(channelId);
        return ApiResponse.success();
    }

    @GetMapping
    public ApiResponse<List<PaymentChannel>> getAllChannels() {
        List<PaymentChannel> channels = paymentChannelService.getAllActiveChannels();
        return ApiResponse.success(channels);
    }

    @GetMapping("/{channelId}")
    public ApiResponse<PaymentChannel> getChannel(@PathVariable String channelId) {
        Optional<PaymentChannel> channel = paymentChannelService.getChannelById(channelId);
        return channel.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.error(404, "渠道不存在"));
    }
}
