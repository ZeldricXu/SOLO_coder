package com.logmanager.service.pipeline;

import com.logmanager.domain.model.LogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogRouterChain {

    private final LogDestinationRegistry destinationRegistry;

    private final Map<String, LogRouter> routers = new ConcurrentHashMap<>();

    public void addRouter(String name, LogRouter router) {
        routers.put(name, router);
        log.info("Added log router: {}", name);
    }

    public void removeRouter(String name) {
        routers.remove(name);
        log.info("Removed log router: {}", name);
    }

    public Mono<Void> route(LogEntry logEntry) {
        return Flux.fromIterable(routers.entrySet())
                .flatMap(entry -> {
                    Optional<String> destinationName = entry.getValue().route(logEntry);
                    if (destinationName.isPresent()) {
                        LogDestination destination = destinationRegistry.getDestination(destinationName.get());
                        if (destination != null) {
                            return destination.deliver(logEntry)
                                    .doOnError(error -> log.error("Failed to route log to destination: {}", destinationName.get(), error))
                                    .onErrorResume(error -> Mono.empty());
                        }
                    }
                    return Mono.empty();
                })
                .then();
    }

    public int size() {
        return routers.size();
    }
}
