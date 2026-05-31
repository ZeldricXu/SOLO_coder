package com.solo.config.module.dns.plugin;

import java.util.List;

public interface DnsResolverPlugin {

    String getName();

    int getPriority();

    default boolean isEnabled() {
        return true;
    }

    List<String> resolve(String domain, String recordType, DnsResolutionContext context);

    default void onResolveSuccess(String domain, String recordType, List<String> results) {
    }

    default void onResolveFailure(String domain, String recordType, Exception e) {
    }
}
