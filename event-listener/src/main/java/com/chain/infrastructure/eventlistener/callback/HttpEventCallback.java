package com.chain.infrastructure.eventlistener.callback;

import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.eventlistener.dto.EventLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpEventCallback implements EventCallback {

    private final WebClient.Builder webClientBuilder;

    @Override
    public void onEvent(EventLog eventLog) {
        log.info("Event received: eventName={}, contract={}, txHash={}",
                eventLog.getEventName(), eventLog.getContractAddress(), eventLog.getTxHash());
    }

    public void invokeWebhook(String callbackUrl, EventLog eventLog) {
        try {
            webClientBuilder.build()
                    .post()
                    .uri(callbackUrl)
                    .bodyValue(JsonUtils.toJson(eventLog))
                    .retrieve()
                    .bodyToMono(String.class)
                    .subscribe(
                            response -> log.debug("Webhook success: url={}, response={}", callbackUrl, response),
                            error -> log.error("Webhook failed: url={}, error={}", callbackUrl, error.getMessage())
                    );
        } catch (Exception e) {
            log.error("Webhook invocation failed: url={}, error={}", callbackUrl, e.getMessage());
        }
    }
}
