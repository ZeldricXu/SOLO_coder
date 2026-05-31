package com.solo.config.module.dns.plugin;

import com.solo.config.module.dns.DnsProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DnsPluginManager {

    private final List<DnsResolverPlugin> plugins;
    private final DnsProperties properties;
    private List<DnsResolverPlugin> sortedPlugins;

    @PostConstruct
    public void init() {
        sortedPlugins = plugins.stream()
                .filter(DnsResolverPlugin::isEnabled)
                .sorted(Comparator.comparingInt(DnsResolverPlugin::getPriority))
                .toList();
        log.info("DNS resolver plugins initialized: {}",
                sortedPlugins.stream().map(DnsResolverPlugin::getName).toList());
    }

    public List<String> resolve(String domain, String recordType) {
        DnsResolutionContext context = new DnsResolutionContext(domain, recordType);
        long startTimeNanos = System.nanoTime();

        try {
            for (DnsResolverPlugin plugin : sortedPlugins) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTimeNanos);
                if (elapsedMs > properties.getPlugin().getResolveTimeoutMs()) {
                    log.warn("DNS resolution timeout after {}ms for domain: {}, type: {}",
                            elapsedMs, domain, recordType);
                    break;
                }

                try {
                    List<String> results = plugin.resolve(domain, recordType, context);
                    if (!results.isEmpty()) {
                        long resolveTime = Duration.between(context.getStartTime(), LocalDateTime.now()).toMillis();
                        context.setResolveTimeMs(resolveTime);
                        context.setResolvedIps(results);

                        for (DnsResolverPlugin p : sortedPlugins) {
                            try {
                                p.onResolveSuccess(domain, recordType, results);
                            } catch (Exception e) {
                                log.warn("Plugin {} onResolveSuccess failed", p.getName(), e);
                            }
                        }

                        log.debug("DNS resolved by plugin {}, domain: {}, results: {}, time: {}ms",
                                plugin.getName(), domain, results, resolveTime);
                        return results;
                    }
                } catch (Exception e) {
                    log.warn("Plugin {} resolve failed for domain: {}", plugin.getName(), domain, e);
                    for (DnsResolverPlugin p : sortedPlugins) {
                        try {
                            p.onResolveFailure(domain, recordType, e);
                        } catch (Exception ex) {
                            log.warn("Plugin {} onResolveFailure failed", p.getName(), ex);
                        }
                    }
                }
            }

            log.warn("DNS resolution failed for domain: {}, type: {}", domain, recordType);
            return java.util.Collections.emptyList();
        } finally {
            cleanupContext(context);
        }
    }

    private void cleanupContext(DnsResolutionContext context) {
        if (context != null) {
            context.getAttributes().clear();
            context.getResolvedIps().clear();
        }
    }

    public List<DnsResolverPlugin> getPlugins() {
        return sortedPlugins;
    }
}
