package com.chain.infrastructure.txbuilder.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chain.infrastructure.persistence.entity.ChainTransaction;
import com.chain.infrastructure.persistence.mapper.ChainTransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmer {

    private final MultilevelCache<String, ChainTransaction> transactionCache;
    private final ChainTransactionMapper transactionMapper;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmUpOnStartup() {
        log.info("Starting transaction cache warm-up...");
        warmRecentTransactions(1000)
                .doOnSuccess(count -> log.info("Cache warm-up completed: loaded {} transactions", count))
                .subscribe();
    }

    @Scheduled(cron = "0 */30 * * * *")
    public void periodicRefresh() {
        log.debug("Starting periodic cache refresh...");
        warmRecentTransactions(500)
                .doOnSuccess(count -> log.debug("Periodic refresh completed: {} transactions", count))
                .subscribe();
    }

    public Mono<Long> warmRecentTransactions(int limit) {
        return Mono.fromCallable(() -> {
            QueryWrapper<ChainTransaction> wrapper = new QueryWrapper<>();
            wrapper.orderByDesc("created_at")
                    .last("LIMIT " + limit);
            return transactionMapper.selectList(wrapper);
        }).flatMapMany(Flux::fromIterable)
                .flatMap(tx -> transactionCache.put(tx.getTxId(), tx))
                .count()
                .onErrorResume(e -> {
                    log.error("Cache warm-up failed: {}", e.getMessage());
                    return Mono.just(0L);
                });
    }

    public Mono<Void> invalidateExpired() {
        return Mono.fromRunnable(() -> {
            QueryWrapper<ChainTransaction> wrapper = new QueryWrapper<>();
            wrapper.lt("updated_at", LocalDateTime.now().minusDays(1))
                    .eq("status", "CONFIRMED");
            transactionMapper.selectList(wrapper).forEach(tx ->
                    transactionCache.evict(tx.getTxId()).subscribe()
            );
            log.debug("Expired cache entries invalidated");
        });
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledInvalidation() {
        invalidateExpired().subscribe();
    }
}
