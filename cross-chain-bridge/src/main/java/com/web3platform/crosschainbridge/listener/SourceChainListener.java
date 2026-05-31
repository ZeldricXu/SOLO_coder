package com.web3platform.crosschainbridge.listener;

import com.web3platform.crosschainbridge.model.BridgeResult;
import com.web3platform.crosschainbridge.service.BridgeCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SourceChainListener {

    private final BridgeCoordinator bridgeCoordinator;
    private final ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = false;

    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        if (running) {
            return;
        }
        running = true;
        log.info("Starting SourceChainListener...");

        listenerExecutor.submit(this::listenForLockEvents);
    }

    private void listenForLockEvents() {
        log.info("SourceChainListener started, listening for lock events...");

        while (running) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("SourceChainListener interrupted");
                break;
            } catch (Exception e) {
                log.error("Error in lock event polling loop", e);
            }
        }

        log.info("SourceChainListener stopped");
    }

    public BridgeResult handleLockEvent(String sourceChain, String txHash,
                                        String lockerAddress, BigDecimal amount,
                                        String targetChain, String proof) {
        log.info("Handling lock event: sourceChain={}, txHash={}, locker={}, amount={}",
                sourceChain, txHash, lockerAddress, amount);

        return bridgeCoordinator.processLockEvent(
                sourceChain, txHash, lockerAddress, amount, targetChain, proof);
    }

    public void stopListening() {
        running = false;
        listenerExecutor.shutdown();
        log.info("SourceChainListener shutdown initiated");
    }

    public boolean isRunning() {
        return running;
    }
}
