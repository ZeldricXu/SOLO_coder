package com.web3platform.crosschainbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.web3platform.crosschainbridge.constant.BridgeConstants;
import com.web3platform.crosschainbridge.exception.BridgeErrorCode;
import com.web3platform.crosschainbridge.exception.BridgeException;
import com.web3platform.crosschainbridge.model.*;
import com.web3platform.crosschainbridge.pool.PooledRpcConnection;
import com.web3platform.crosschainbridge.pool.ResourcePoolManager;
import com.web3platform.crosschainbridge.pool.RpcConnectionPool;
import com.web3platform.crosschainbridge.util.CryptoUtils;
import com.web3platform.persistence.mapper.CrossChainLockMapper;
import com.web3platform.persistence.mapper.CrossChainMintMapper;
import com.web3platform.persistence.model.entity.CrossChainLock;
import com.web3platform.persistence.model.entity.CrossChainMint;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class BridgeCoordinator {

    private final AtomicBridgeService atomicBridgeService;
    private final CrossChainMessageService crossChainMessageService;
    private final CrossChainLockMapper crossChainLockMapper;
    private final CrossChainMintMapper crossChainMintMapper;
    private final RpcConnectionPool rpcConnectionPool;
    private final ResourcePoolManager resourcePoolManager;

    @Value("${cross-chain-bridge.bridge.private-key:}")
    private String bridgePrivateKey;

    private final Map<String, CrossChainMessage> pendingMessages = new ConcurrentHashMap<>();
    private final AtomicLong nonceCounter = new AtomicLong(0);

    @Transactional
    public BridgeResult initiateBridge(@NonNull LockRequest lockRequest) {
        log.info("Initiating cross-chain bridge: {} -> {}, amount={}",
                lockRequest.getSourceChain(), lockRequest.getTargetChain(), lockRequest.getAmount());

        try {
            BridgeResult lockResult = atomicBridgeService.lockAssets(lockRequest);
            if (!lockResult.isSuccess()) {
                log.error("Asset lock failed: {}", lockResult.getError());
                return lockResult;
            }

            CrossChainLock lock = validateLockExists(lockResult.getLockTxHash());
            createAndRegisterPendingMessage(lockRequest, lock);

            log.info("Bridge initiated: lockId={}, lockTxHash={}", lock.getId(), lockResult.getLockTxHash());
            return buildSuccessResult(lockResult.getLockTxHash(), null);

        } catch (BridgeException e) {
            log.error("Bridge initiation failed: {}", e.getMessage());
            return buildFailureResult(null, null, e.getMessage());
        } catch (Exception e) {
            log.error("Bridge initiation failed", e);
            return buildFailureResult(null, null, "Bridge initiation failed: " + e.getMessage());
        }
    }

    private CrossChainLock validateLockExists(String txHash) {
        CrossChainLock lock = findLockByTxHash(txHash);
        if (lock == null) {
            throw new BridgeException(BridgeErrorCode.LOCK_NOT_FOUND,
                    "Lock record not found for txHash: " + txHash);
        }
        return lock;
    }

    private CrossChainMessage createAndRegisterPendingMessage(LockRequest lockRequest, CrossChainLock lock) {
        long nonce = nonceCounter.incrementAndGet();
        CrossChainMessage message = crossChainMessageService.createMessage(lockRequest, nonce);
        message = signMessageIfNeeded(message);
        pendingMessages.put(message.getMessageId(), message);
        log.debug("Registered pending message: messageId={}, lockId={}", message.getMessageId(), lock.getId());
        return message;
    }

    private CrossChainMessage signMessageIfNeeded(CrossChainMessage message) {
        if (bridgePrivateKey != null && !bridgePrivateKey.isEmpty()) {
            return crossChainMessageService.signMessage(message, bridgePrivateKey);
        }
        return message;
    }

    private BridgeResult buildSuccessResult(String lockTxHash, String mintTxHash) {
        return BridgeResult.builder()
                .success(true)
                .lockTxHash(lockTxHash)
                .mintTxHash(mintTxHash)
                .error(null)
                .build();
    }

    private BridgeResult buildFailureResult(String lockTxHash, String mintTxHash, String error) {
        return BridgeResult.builder()
                .success(false)
                .lockTxHash(lockTxHash)
                .mintTxHash(mintTxHash)
                .error(error)
                .build();
    }

    @Transactional
    public BridgeResult processLockEvent(@NonNull String sourceChain, @NonNull String txHash,
                                          @NonNull String lockerAddress, @NonNull BigDecimal amount,
                                          @NonNull String targetChain, String proof) {
        log.info("Processing lock event: sourceChain={}, txHash={}", sourceChain, txHash);

        PooledRpcConnection rpcConnection = null;
        try {
            rpcConnection = rpcConnectionPool.borrowConnection(sourceChain);
            log.debug("Borrowed RPC connection for sourceChain: {}", sourceChain);

            CrossChainLock lock = validateLockByTxHash(txHash);
            CrossChainMessage message = createBridgeMessage(sourceChain, targetChain, lockerAddress, amount);
            verifyMessageProof(message, proof, sourceChain);
            BridgeResult mintResult = executeMint(lock, lockerAddress, amount, proof);
            checkAtomicity(lock, mintResult);

            log.info("Bridge completed successfully: lockId={}, lockTxHash={}, mintTxHash={}",
                    lock.getId(), txHash, mintResult.getMintTxHash());

            return buildSuccessResult(txHash, mintResult.getMintTxHash());

        } catch (BridgeException e) {
            log.error("Error processing lock event: {}", e.getMessage());
            invalidateConnectionOnError(rpcConnection);
            return buildFailureResult(txHash, null, e.getMessage());
        } catch (Exception e) {
            log.error("Error processing lock event", e);
            invalidateConnectionOnError(rpcConnection);
            return buildFailureResult(txHash, null, "Event processing failed: " + e.getMessage());
        } finally {
            returnConnectionSafely(rpcConnection);
        }
    }

    private CrossChainLock validateLockByTxHash(String txHash) {
        CrossChainLock lock = findLockByTxHash(txHash);
        if (lock == null) {
            throw new BridgeException(BridgeErrorCode.LOCK_NOT_FOUND,
                    "Lock record not found for txHash: " + txHash);
        }
        return lock;
    }

    private CrossChainMessage createBridgeMessage(String sourceChain, String targetChain,
                                                  String lockerAddress, BigDecimal amount) {
        LockRequest lockRequest = buildLockRequest(sourceChain, targetChain, lockerAddress, amount);
        long nonce = nonceCounter.incrementAndGet();
        CrossChainMessage message = crossChainMessageService.createMessage(lockRequest, nonce);
        return signMessageIfNeeded(message);
    }

    private LockRequest buildLockRequest(String sourceChain, String targetChain,
                                         String lockerAddress, BigDecimal amount) {
        return LockRequest.builder()
                .sourceChain(sourceChain)
                .targetChain(targetChain)
                .lockerAddress(lockerAddress)
                .amount(amount.toBigInteger())
                .assetAddress(null)
                .build();
    }

    private void verifyMessageProof(CrossChainMessage message, String proof, String sourceChain) {
        VerificationResult verificationResult = atomicBridgeService.verifyMessageWithProof(
                message, proof, CryptoUtils.isEVMChain(sourceChain));

        if (!verificationResult.isValid()) {
            throw new BridgeException(BridgeErrorCode.PROOF_VERIFICATION_FAILED,
                    "Proof verification failed: " + verificationResult.getReason());
        }
    }

    private BridgeResult executeMint(CrossChainLock lock, String lockerAddress,
                                     BigDecimal amount, String proof) {
        MintRequest mintRequest = MintRequest.builder()
                .lockId(lock.getId())
                .recipient(lockerAddress)
                .amount(amount.toBigInteger())
                .proof(proof)
                .build();

        BridgeResult mintResult = atomicBridgeService.mintAssets(mintRequest);
        if (!mintResult.isSuccess()) {
            log.error("Mint failed after lock event: {}", mintResult.getError());
            throw new BridgeException(BridgeErrorCode.MINT_FAILED, mintResult.getError());
        }
        return mintResult;
    }

    private void checkAtomicity(CrossChainLock lock, BridgeResult mintResult) {
        boolean atomicityCheck = atomicBridgeService.ensureAtomicity(lock.getId());
        if (!atomicityCheck) {
            log.warn("Atomicity check failed after mint, initiating rollback: lockId={}", lock.getId());
            atomicBridgeService.rollbackLock(lock.getId());
            throw new BridgeException(BridgeErrorCode.ATOMICITY_VIOLATION,
                    "Atomicity check failed, rollback initiated");
        }
    }

    private void invalidateConnectionOnError(PooledRpcConnection rpcConnection) {
        if (rpcConnection != null) {
            try {
                rpcConnectionPool.invalidateConnection(rpcConnection);
            } catch (Exception e) {
                log.warn("Failed to invalidate RPC connection", e);
            }
        }
    }

    private void returnConnectionSafely(PooledRpcConnection rpcConnection) {
        if (rpcConnection != null) {
            try {
                rpcConnectionPool.returnConnection(rpcConnection);
            } catch (Exception e) {
                log.warn("Failed to return RPC connection to pool", e);
            }
        }
    }

    @Transactional
    public BridgeResult completeBridge(@NonNull String messageId, String proof) {
        log.info("Completing bridge for messageId: {}", messageId);

        try {
            CrossChainMessage message = getPendingMessageOrThrow(messageId);
            verifyMessageProof(message, proof, message.getSourceChain());

            CrossChainLock lock = findLockByMessage(message);
            BridgeResult mintResult = executeMintForMessage(lock, message, proof);
            checkAtomicity(lock, mintResult);

            pendingMessages.remove(messageId);
            return buildSuccessResult(lock.getTxHash(), mintResult.getMintTxHash());

        } catch (BridgeException e) {
            log.error("Error completing bridge: {}", e.getMessage());
            return buildFailureResult(null, null, e.getMessage());
        } catch (Exception e) {
            log.error("Error completing bridge", e);
            return buildFailureResult(null, null, "Bridge completion failed: " + e.getMessage());
        }
    }

    private CrossChainMessage getPendingMessageOrThrow(String messageId) {
        CrossChainMessage message = pendingMessages.get(messageId);
        if (message == null) {
            throw new BridgeException(BridgeErrorCode.MESSAGE_NOT_FOUND,
                    "Pending message not found: " + messageId);
        }
        return message;
    }

    private CrossChainLock findLockByMessage(CrossChainMessage message) {
        QueryWrapper<CrossChainLock> lockQuery = new QueryWrapper<>();
        lockQuery.eq("source_chain", message.getSourceChain());
        lockQuery.eq("target_chain", message.getTargetChain());
        lockQuery.eq("locker_address", message.getSender());
        CrossChainLock lock = crossChainLockMapper.selectOne(lockQuery);

        if (lock == null) {
            throw new BridgeException(BridgeErrorCode.LOCK_NOT_FOUND,
                    "Lock record not found for message");
        }
        return lock;
    }

    private BridgeResult executeMintForMessage(CrossChainLock lock, CrossChainMessage message, String proof) {
        MintRequest mintRequest = MintRequest.builder()
                .lockId(lock.getId())
                .recipient(message.getRecipient())
                .amount(message.getAmount())
                .proof(proof)
                .build();

        BridgeResult mintResult = atomicBridgeService.mintAssets(mintRequest);
        if (!mintResult.isSuccess()) {
            throw new BridgeException(BridgeErrorCode.MINT_FAILED, mintResult.getError());
        }
        return mintResult;
    }

    private CrossChainLock findLockByTxHash(String txHash) {
        QueryWrapper<CrossChainLock> query = new QueryWrapper<>();
        query.eq("tx_hash", txHash);
        return crossChainLockMapper.selectOne(query);
    }

    public CrossChainMessage getPendingMessage(@NonNull String messageId) {
        return pendingMessages.get(messageId);
    }
}
