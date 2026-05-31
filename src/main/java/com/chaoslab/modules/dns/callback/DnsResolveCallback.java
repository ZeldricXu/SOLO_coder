package com.chaoslab.modules.dns.callback;

import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;

@FunctionalInterface
public interface DnsResolveCallback {
    void onComplete(DnsAsyncTask task, DnsResolveResponse response, Throwable error);
}
