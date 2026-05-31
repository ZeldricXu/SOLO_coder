package com.web3platform.crosschainbridge.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.web3platform.crosschainbridge.constant.BridgeConstants;
import com.web3platform.crosschainbridge.exception.BridgeErrorCode;
import com.web3platform.crosschainbridge.exception.BridgeException;
import com.web3platform.crosschainbridge.model.BridgeResult;
import com.web3platform.crosschainbridge.model.LockRequest;
import com.web3platform.crosschainbridge.model.MintRequest;
import com.web3platform.crosschainbridge.model.VerificationResult;
import com.web3platform.crosschainbridge.pool.VerifierPool;
import com.web3platform.persistence.mapper.CrossChainLockMapper;
import com.web3platform.persistence.mapper.CrossChainMintMapper;
import com.web3platform.persistence.model.entity.CrossChainLock;
import com.web3platform.persistence.model.entity.CrossChainMint;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AtomicBridgeService {

    private final CrossChainLockMapper crossChainLockMapper;
    private final CrossChainMintMapper crossChainMintMapper;
    private final CrossChainMessageService crossChainMessageService;
    private final VerifierPool verifierPool;

    @Transactional
    public BridgeResult lockAssets(@NonNull LockRequest lockRequest) {
        log.info("Initiating asset lock: sourceChain={}, targetChain={}, amount={}",
                lockRequest.getSourceChain(), lockRequest.getTargetChain(), lockRequest.getAmount());

        try {
            String lockTxHash = generateTxHash();
            CrossChainLock lock = buildLockRecord(lockRequest, lockTxHash);
            crossChainLockMapper.insert(lock);

            log.info("Asset lock recorded: lockId={}, txHash={}", lock.getId(), lockTxHash);

            return buildSuccessResult(lockTxHash, null);

        } catch (Exception e) {
            log.error("Failed to lock assets", e);
            return buildFailureResult(null, null, "Lock failed: " + e.getMessage());
        }
    }

    private CrossChainLock buildLockRecord(LockRequest lockRequest, String lockTxHash) {
        CrossChainLock lock = new CrossChainLock();
        lock.setSourceChain(lockRequest.getSourceChain());
        lock.setTargetChain(lockRequest.getTargetChain());
        lock.setTxHash(lockTxHash);
        lock.setLockAmount(new BigDecimal(lockRequest.getAmount()));
        lock.setLockStatus(BridgeConstants.LOCK_STATUS_CONFIRMED);
        lock.setLockerAddress(lockRequest.getLockerAddress());
        lock.setCreatedAt(LocalDateTime.now());
        lock.setUpdatedAt(LocalDateTime.now());
        return lock;
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
    public BridgeResult mintAssets(@NonNull MintRequest mintRequest) {
        log.info("Initiating asset mint: lockId={}, recipient={}, amount={}",
                mintRequest.getLockId(), mintRequest.getRecipient(), mintRequest.getAmount());

        try {
            CrossChainLock lock = validateLockRecord(mintRequest.getLockId());
            BigDecimal requestedAmount = validateAmount(lock, mintRequest.getAmount());
            CrossChainMint existingMint = checkDuplicateMint(mintRequest.getLockId());

            if (existingMint != null) {
                return buildFailureResult(lock.getTxHash(), existingMint.getMintTxHash(),
                        "Mint already exists for this lock");
            }

            String mintTxHash = executeMint(mintRequest, lock, requestedAmount);

            log.info("Asset mint recorded: lockId={}, txHash={}", mintRequest.getLockId(), mintTxHash);
            return buildSuccessResult(lock.getTxHash(), mintTxHash);

        } catch (BridgeException e) {
            log.error("Failed to mint assets: {}", e.getMessage());
            return buildFailureResult(null, null, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to mint assets", e);
            return buildFailureResult(null, null, "Mint failed: " + e.getMessage());
        }
    }

    private CrossChainLock validateLockRecord(Long lockId) {
        CrossChainLock lock = crossChainLockMapper.selectById(lockId);
        if (lock == null) {
            throw new BridgeException(BridgeErrorCode.LOCK_NOT_FOUND,
                    "Lock record not found for lockId: " + lockId);
        }
        if (!BridgeConstants.LOCK_STATUS_CONFIRMED.equals(lock.getLockStatus())) {
            throw new BridgeException(BridgeErrorCode.INVALID_LOCK_STATUS,
                    "Invalid lock status: " + lock.getLockStatus());
        }
        return lock;
    }

    private BigDecimal validateAmount(CrossChainLock lock, BigInteger amount) {
        BigDecimal requestedAmount = new BigDecimal(amount);
        if (requestedAmount.compareTo(lock.getLockAmount()) > 0) {
            throw new BridgeException(BridgeErrorCode.AMOUNT_MISMATCH,
                    "Mint amount exceeds locked amount");
        }
        return requestedAmount;
    }

    private CrossChainMint checkDuplicateMint(Long lockId) {
        QueryWrapper<CrossChainMint> mintQuery = new QueryWrapper<>();
        mintQuery.eq("lock_id", lockId);
        return crossChainMintMapper.selectOne(mintQuery);
    }

    private String executeMint(MintRequest mintRequest, CrossChainLock lock, BigDecimal requestedAmount) {
        String mintTxHash = generateTxHash();
        CrossChainMint mint = buildMintRecord(mintRequest, lock, requestedAmount, mintTxHash);
        crossChainMintMapper.insert(mint);
        return mintTxHash;
    }

    private CrossChainMint buildMintRecord(MintRequest mintRequest, CrossChainLock lock,
                                           BigDecimal requestedAmount, String mintTxHash) {
        CrossChainMint mint = new CrossChainMint();
        mint.setLockId(mintRequest.getLockId());
        mint.setTargetChain(lock.getTargetChain());
        mint.setMintTxHash(mintTxHash);
        mint.setMintAmount(requestedAmount);
        mint.setMintStatus(BridgeConstants.MINT_STATUS_CONFIRMED);
        mint.setMinterAddress(mintRequest.getRecipient());
        mint.setCreatedAt(LocalDateTime.now());
        mint.setUpdatedAt(LocalDateTime.now());
        return mint;
    }

    @Transactional
    public boolean ensureAtomicity(@NonNull Long lockId) {
        log.info("Checking atomicity for lockId: {}", lockId);

        try {
            CrossChainLock lock = crossChainLockMapper.selectById(lockId);
            if (lock == null) {
                log.warn("Lock record not found for atomicity check: lockId={}", lockId);
                return false;
            }

            CrossChainMint mint = findMintByLockId(lockId);

            if (BridgeConstants.LOCK_STATUS_CONFIRMED.equals(lock.getLockStatus())) {
                return checkConfirmedAtomicity(lock, mint);
            } else if (BridgeConstants.LOCK_STATUS_ROLLED_BACK.equals(lock.getLockStatus())) {
                return checkRolledBackAtomicity(lock, mint);
            }

            log.info("Atomicity check passed for lockId: {}", lockId);
            return true;

        } catch (Exception e) {
            log.error("Atomicity check failed for lockId: {}", lockId, e);
            return false;
        }
    }

    private CrossChainMint findMintByLockId(Long lockId) {
        QueryWrapper<CrossChainMint> mintQuery = new QueryWrapper<>();
        mintQuery.eq("lock_id", lockId);
        return crossChainMintMapper.selectOne(mintQuery);
    }

    private boolean checkConfirmedAtomicity(CrossChainLock lock, CrossChainMint mint) {
        if (mint == null || !BridgeConstants.MINT_STATUS_CONFIRMED.equals(mint.getMintStatus())) {
            log.warn("Atomicity violation: lock confirmed but mint not confirmed, lockId={}", lock.getId());
            return false;
        }

        BigDecimal lockAmount = lock.getLockAmount();
        BigDecimal mintAmount = mint.getMintAmount();

        if (lockAmount.compareTo(mintAmount) != 0) {
            log.warn("Atomicity violation: amount mismatch, lockAmount={}, mintAmount={}",
                    lockAmount, mintAmount);
            return false;
        }
        return true;
    }

    private boolean checkRolledBackAtomicity(CrossChainLock lock, CrossChainMint mint) {
        if (mint != null && BridgeConstants.MINT_STATUS_CONFIRMED.equals(mint.getMintStatus())) {
            log.warn("Atomicity violation: lock rolled back but mint confirmed, lockId={}", lock.getId());
            return false;
        }
        return true;
    }

    @Transactional
    public boolean rollbackLock(@NonNull Long lockId) {
        log.info("Initiating rollback for lockId: {}", lockId);

        try {
            CrossChainLock lock = crossChainLockMapper.selectById(lockId);
            if (lock == null) {
                log.warn("Lock record not found for rollback: lockId={}", lockId);
                return false;
            }

            if (BridgeConstants.LOCK_STATUS_ROLLED_BACK.equals(lock.getLockStatus())) {
                log.info("Lock already rolled back: lockId={}", lockId);
                return true;
            }

            if (!BridgeConstants.LOCK_STATUS_CONFIRMED.equals(lock.getLockStatus())) {
                log.warn("Cannot rollback lock in status: {}", lock.getLockStatus());
                return false;
            }

            CrossChainMint mint = findMintByLockId(lockId);
            if (mint != null && BridgeConstants.MINT_STATUS_CONFIRMED.equals(mint.getMintStatus())) {
                log.warn("Cannot rollback lock with confirmed mint: lockId={}", lockId);
                return false;
            }

            lock.setLockStatus(BridgeConstants.LOCK_STATUS_ROLLED_BACK);
            lock.setUpdatedAt(LocalDateTime.now());
            crossChainLockMapper.updateById(lock);

            log.info("Lock rolled back successfully: lockId={}", lockId);
            return true;

        } catch (Exception e) {
            log.error("Rollback failed for lockId: {}", lockId, e);
            return false;
        }
    }

    public CrossChainLock getLockStatus(@NonNull Long lockId) {
        return crossChainLockMapper.selectById(lockId);
    }

    public VerificationResult verifyMessageWithProof(
            @NonNull com.web3platform.crosschainbridge.model.CrossChainMessage message,
            String proof,
            boolean useMpt) {

        MessageVerifier verifier = null;
        try {
            verifier = useMpt ? verifierPool.borrowMptVerifier() : verifierPool.borrowMerkleVerifier();
            return verifier.verifyMessage(message, proof);
        } finally {
            if (verifier != null) {
                returnVerifier(verifier, useMpt);
            }
        }
    }

    private void returnVerifier(MessageVerifier verifier, boolean useMpt) {
        if (useMpt) {
            verifierPool.returnMptVerifier(verifier);
        } else {
            verifierPool.returnMerkleVerifier(verifier);
        }
    }

    private String generateTxHash() {
        return BridgeConstants.HEX_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
