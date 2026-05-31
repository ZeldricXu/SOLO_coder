package com.web3platform.crosschainbridge.controller;

import com.web3platform.crosschainbridge.model.*;
import com.web3platform.crosschainbridge.pool.ResourcePoolManager;
import com.web3platform.crosschainbridge.service.AtomicBridgeService;
import com.web3platform.crosschainbridge.service.BridgeCoordinator;
import com.web3platform.crosschainbridge.service.CrossChainMessageService;
import com.web3platform.persistence.model.entity.CrossChainLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/bridge")
@RequiredArgsConstructor
public class BridgeController {

    private final BridgeCoordinator bridgeCoordinator;
    private final AtomicBridgeService atomicBridgeService;
    private final CrossChainMessageService crossChainMessageService;
    private final ResourcePoolManager resourcePoolManager;

    @PostMapping("/lock")
    public ResponseEntity<BridgeResult> lockAssets(@RequestBody LockRequest lockRequest) {
        log.info("Received lock request: sourceChain={}, targetChain={}, amount={}",
                lockRequest.getSourceChain(), lockRequest.getTargetChain(), lockRequest.getAmount());

        if (lockRequest.getSourceChain() == null || lockRequest.getTargetChain() == null) {
            return ResponseEntity.badRequest().body(BridgeResult.builder()
                    .success(false)
                    .error("Source chain and target chain are required")
                    .build());
        }

        if (lockRequest.getAmount() == null || lockRequest.getAmount().signum() <= 0) {
            return ResponseEntity.badRequest().body(BridgeResult.builder()
                    .success(false)
                    .error("Valid amount is required")
                    .build());
        }

        BridgeResult result = bridgeCoordinator.initiateBridge(lockRequest);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/mint")
    public ResponseEntity<BridgeResult> mintAssets(@RequestBody MintRequest mintRequest) {
        log.info("Received mint request: lockId={}, recipient={}, amount={}",
                mintRequest.getLockId(), mintRequest.getRecipient(), mintRequest.getAmount());

        if (mintRequest.getLockId() == null) {
            return ResponseEntity.badRequest().body(BridgeResult.builder()
                    .success(false)
                    .error("Lock ID is required")
                    .build());
        }

        if (mintRequest.getProof() == null || mintRequest.getProof().isEmpty()) {
            return ResponseEntity.badRequest().body(BridgeResult.builder()
                    .success(false)
                    .error("Proof is required for minting")
                    .build());
        }

        BridgeResult result = atomicBridgeService.mintAssets(mintRequest);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/lock/{lockId}")
    public ResponseEntity<Map<String, Object>> getLockStatus(@PathVariable Long lockId) {
        log.info("Querying lock status: lockId={}", lockId);

        CrossChainLock lock = atomicBridgeService.getLockStatus(lockId);
        if (lock == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", lock.getId());
        response.put("sourceChain", lock.getSourceChain());
        response.put("targetChain", lock.getTargetChain());
        response.put("txHash", lock.getTxHash());
        response.put("lockAmount", lock.getLockAmount());
        response.put("lockStatus", lock.getLockStatus());
        response.put("lockerAddress", lock.getLockerAddress());
        response.put("createdAt", lock.getCreatedAt());
        response.put("updatedAt", lock.getUpdatedAt());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/message/{messageId}/verify")
    public ResponseEntity<VerificationResult> verifyMessage(
            @PathVariable String messageId,
            @RequestParam String proof,
            @RequestParam(defaultValue = "true") boolean useMpt) {

        log.info("Verifying message: messageId={}, useMpt={}", messageId, useMpt);

        CrossChainMessage message = bridgeCoordinator.getPendingMessage(messageId);
        if (message == null) {
            return ResponseEntity.badRequest().body(VerificationResult.builder()
                    .valid(false)
                    .reason("Message not found or already processed")
                    .build());
        }

        VerificationResult result = atomicBridgeService.verifyMessageWithProof(message, proof, useMpt);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/atomicity/check/{lockId}")
    public ResponseEntity<Map<String, Object>> checkAtomicity(@PathVariable Long lockId) {
        log.info("Checking atomicity: lockId={}", lockId);

        boolean isAtomic = atomicBridgeService.ensureAtomicity(lockId);

        Map<String, Object> response = new HashMap<>();
        response.put("lockId", lockId);
        response.put("atomic", isAtomic);
        response.put("message", isAtomic
                ? "Atomicity check passed"
                : "Atomicity check failed - lock and mint records are inconsistent");

        return ResponseEntity.ok(response);
    }

    @PostMapping("/message/sign")
    public ResponseEntity<CrossChainMessage> signMessage(
            @RequestBody CrossChainMessage message,
            @RequestParam String privateKey) {

        log.info("Signing message: messageId={}", message.getMessageId());

        try {
            CrossChainMessage signedMessage = crossChainMessageService.signMessage(message, privateKey);
            return ResponseEntity.ok(signedMessage);
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            return ResponseEntity.badRequest().body(message);
        }
    }

    @GetMapping("/message/{messageId}/hash")
    public ResponseEntity<Map<String, String>> getMessageHash(@PathVariable String messageId) {
        CrossChainMessage message = bridgeCoordinator.getPendingMessage(messageId);
        if (message == null) {
            return ResponseEntity.notFound().build();
        }

        String hash = crossChainMessageService.hashMessageAsHex(message);
        Map<String, String> response = new HashMap<>();
        response.put("messageId", messageId);
        response.put("hash", hash);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/pool/stats")
    public ResponseEntity<Map<String, PoolStatistics>> getPoolStats() {
        log.info("Querying resource pool statistics");
        Map<String, PoolStatistics> stats = resourcePoolManager.getPoolStatistics();
        return ResponseEntity.ok(stats);
    }
}
