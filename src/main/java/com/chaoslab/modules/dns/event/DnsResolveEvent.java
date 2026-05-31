package com.chaoslab.modules.dns.event;

import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

@Getter
public class DnsResolveEvent extends ApplicationEvent {

    private final String eventType;
    private final DnsAsyncTask task;
    private final DnsResolveResponse response;
    private final Throwable error;
    private final LocalDateTime timestamp;

    public DnsResolveEvent(Object source, String eventType, DnsAsyncTask task,
                           DnsResolveResponse response, Throwable error) {
        super(source);
        this.eventType = eventType;
        this.task = task;
        this.response = response;
        this.error = error;
        this.timestamp = LocalDateTime.now();
    }

    public static DnsResolveEvent success(Object source, DnsAsyncTask task, DnsResolveResponse response) {
        return new DnsResolveEvent(source, "DNS_RESOLVE_SUCCESS", task, response, null);
    }

    public static DnsResolveEvent failure(Object source, DnsAsyncTask task, Throwable error) {
        return new DnsResolveEvent(source, "DNS_RESOLVE_FAILURE", task, null, error);
    }

    public static DnsResolveEvent timeout(Object source, DnsAsyncTask task) {
        return new DnsResolveEvent(source, "DNS_RESOLVE_TIMEOUT", task, null,
                new java.util.concurrent.TimeoutException("DNS resolution timed out"));
    }

    public static DnsResolveEvent retry(Object source, DnsAsyncTask task, int retryCount) {
        return new DnsResolveEvent(source, "DNS_RESOLVE_RETRY", task, null, null);
    }
}
