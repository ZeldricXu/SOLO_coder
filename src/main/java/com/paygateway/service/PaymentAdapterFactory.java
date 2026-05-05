package com.paygateway.service;

import com.paygateway.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PaymentAdapterFactory {
    
    private final Map<String, PaymentAdapter> adapterMap;
    
    public PaymentAdapterFactory(List<PaymentAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(PaymentAdapter::getChannel, Function.identity()));
    }
    
    public PaymentAdapter getAdapter(String channel) {
        PaymentAdapter adapter = adapterMap.get(channel);
        if (adapter == null) {
            throw new BusinessException(400, "不支持的支付渠道：" + channel);
        }
        return adapter;
    }
    
    public boolean supports(String channel) {
        return adapterMap.containsKey(channel);
    }
}
