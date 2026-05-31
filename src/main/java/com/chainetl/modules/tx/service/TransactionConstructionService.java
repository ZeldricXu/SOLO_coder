package com.chainetl.modules.tx.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.util.IdGenerator;
import com.chainetl.modules.chain.service.ChainAdapterService;
import com.chainetl.modules.gas.dto.GasEstimateRequest;
import com.chainetl.modules.gas.service.GasEstimationService;
import com.chainetl.modules.tx.dto.ConstructTransactionRequest;
import com.chainetl.modules.tx.dto.SignTransactionRequest;
import com.chainetl.modules.tx.dto.SubmitTransactionRequest;
import com.chainetl.modules.tx.dto.TransactionResponse;
import com.chainetl.modules.tx.mapper.ConstructedTransactionMapper;
import com.chainetl.modules.tx.model.ConstructedTransaction;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionConstructionService {

    private final ConstructedTransactionMapper txMapper;
    private final ChainAdapterService chainAdapterService;
    private final GasEstimationService gasEstimationService;

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SIGNED = "SIGNED";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_CONFIRMED = "CONFIRMED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PENDING_MULTISIG = "PENDING_MULTISIG";

    private static final String GAS_STRATEGY_FAST = "FAST";
    private static final String GAS_STRATEGY_STANDARD = "STANDARD";
    private static final String GAS_STRATEGY_LOW = "LOW";
    private static final String GAS_STRATEGY_OPTIMAL = "OPTIMAL";

    @Transactional
    @Retry(name = "transaction", fallbackMethod = "constructTransactionFallback")
    @Timed(value = "tx.construct", description = "Time taken to construct transaction")
    public Mono<TransactionResponse> constructTransaction(ConstructTransactionRequest request) {
        return Mono.fromCallable(() -> {
            String txId = IdGenerator.generateTxId();
            Instant now = Instant.now();

            Long gasLimit = request.getGasLimit();
            Long gasPrice = request.getGasPrice();
            Long nonce = request.getNonce();
            String strategy = request.getGasOptimizationStrategy() != null ?
                    request.getGasOptimizationStrategy().toUpperCase() : GAS_STRATEGY_OPTIMAL;

            if (gasLimit == null || gasPrice == null) {
                GasEstimateRequest gasRequest = GasEstimateRequest.builder()
                        .chainId(request.getChainId())
                        .transactionType(determineTransactionType(request))
                        .fromAddress(request.getFromAddress())
                        .toAddress(request.getToAddress())
                        .value(request.getValue())
                        .data(request.getData())
                        .build();

                var gasEstimate = gasEstimationService.estimateGas(gasRequest).block();

                if (gasLimit == null) {
                    gasLimit = gasEstimate.getEstimatedGas();
                }

                if (gasPrice == null) {
                    gasPrice = switch (strategy) {
                        case GAS_STRATEGY_FAST -> gasEstimate.getGasPrice().getHigh();
                        case GAS_STRATEGY_LOW -> gasEstimate.getGasPrice().getLow();
                        case GAS_STRATEGY_STANDARD -> gasEstimate.getGasPrice().getMedium();
                        default -> gasEstimate.getGasPrice().getMedium();
                    };
                }
            }

            if (nonce == null) {
                nonce = getNextNonce(request.getChainId(), request.getFromAddress());
            }

            String status = request.getMultisigWalletId() != null ? STATUS_PENDING_MULTISIG : STATUS_DRAFT;

            ConstructedTransaction tx = ConstructedTransaction.builder()
                    .txId(txId)
                    .chainId(request.getChainId())
                    .fromAddress(request.getFromAddress())
                    .toAddress(request.getToAddress())
                    .value(request.getValue())
                    .gasLimit(gasLimit)
                    .gasPrice(gasPrice)
                    .nonce(nonce)
                    .data(request.getData())
                    .multisigWalletId(request.getMultisigWalletId())
                    .status(status)
                    .createdAt(now)
                    .build();

            txMapper.insert(tx);
            log.info("Constructed transaction: txId={}, chain={}, from={}, to={}, status={}",
                    txId, request.getChainId(), request.getFromAddress(), request.getToAddress(), status);

            return buildResponse(tx, strategy, gasPrice);
        });
    }

    @Transactional
    @Retry(name = "transaction", fallbackMethod = "signTransactionFallback")
    @Timed(value = "tx.sign", description = "Time taken to sign transaction")
    public Mono<TransactionResponse> signTransaction(SignTransactionRequest request) {
        return Mono.fromCallable(() -> {
            ConstructedTransaction tx = txMapper.selectById(request.getTxId());
            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + request.getTxId());
            }

            if (STATUS_SIGNED.equals(tx.getStatus()) || STATUS_SUBMITTED.equals(tx.getStatus())) {
                throw new BusinessException(400, "Transaction already signed or submitted");
            }

            String signature = request.getSignature() != null ? request.getSignature() :
                    generateSignature(tx, request.getSignerAddress());

            tx.setSignedTx(signature);
            tx.setStatus(STATUS_SIGNED);
            txMapper.updateById(tx);

            log.info("Signed transaction: txId={}, signer={}", request.getTxId(), request.getSignerAddress());
            return buildResponse(tx, null, tx.getGasPrice());
        });
    }

    @Transactional
    @Retry(name = "transaction", fallbackMethod = "submitTransactionFallback")
    @Timed(value = "tx.submit", description = "Time taken to submit transaction")
    public Mono<TransactionResponse> submitTransaction(SubmitTransactionRequest request) {
        return Mono.fromCallable(() -> {
            ConstructedTransaction tx = txMapper.selectById(request.getTxId());
            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + request.getTxId());
            }

            if (!STATUS_SIGNED.equals(tx.getStatus())) {
                throw new BusinessException(400, "Transaction must be signed before submission. Current status: " + tx.getStatus());
            }

            String signedTx = request.getSignedTx() != null ? request.getSignedTx() : tx.getSignedTx();
            if (signedTx == null || signedTx.isEmpty()) {
                throw new BusinessException(400, "No signed transaction data available");
            }

            int maxRetries = request.getMaxRetries() != null ? request.getMaxRetries() : 3;
            String txHash = null;
            Exception lastException = null;

            for (int i = 0; i < maxRetries; i++) {
                try {
                    txHash = chainAdapterService.sendRawTransaction(tx.getChainId(), signedTx).block();
                    if (txHash != null && !txHash.isEmpty()) {
                        break;
                    }
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Failed to submit transaction (attempt {}/{}): {}", i + 1, maxRetries, e.getMessage());
                    if (i < maxRetries - 1) {
                        Thread.sleep(1000 * (i + 1));
                    }
                }
            }

            if (txHash == null) {
                tx.setStatus(STATUS_FAILED);
                txMapper.updateById(tx);
                throw new BusinessException("Failed to submit transaction after " + maxRetries + " attempts: " +
                        (lastException != null ? lastException.getMessage() : "unknown error"));
            }

            tx.setTxHash(txHash);
            tx.setSignedTx(signedTx);
            tx.setStatus(STATUS_SUBMITTED);
            tx.setSubmittedAt(Instant.now());
            txMapper.updateById(tx);

            log.info("Submitted transaction: txId={}, txHash={}, chain={}", request.getTxId(), txHash, tx.getChainId());
            return buildResponse(tx, null, tx.getGasPrice());
        });
    }

    public Mono<TransactionResponse> getTransaction(String txId) {
        return Mono.fromCallable(() -> {
            ConstructedTransaction tx = txMapper.selectById(txId);
            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + txId);
            }
            return buildResponse(tx, null, tx.getGasPrice());
        });
    }

    public Mono<List<TransactionResponse>> listTransactions(
            String chainId, String fromAddress, String toAddress, String status, Integer limit) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ConstructedTransaction> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null) {
                wrapper.eq(ConstructedTransaction::getChainId, chainId);
            }
            if (fromAddress != null) {
                wrapper.eq(ConstructedTransaction::getFromAddress, fromAddress);
            }
            if (toAddress != null) {
                wrapper.eq(ConstructedTransaction::getToAddress, toAddress);
            }
            if (status != null) {
                wrapper.eq(ConstructedTransaction::getStatus, status.toUpperCase());
            }
            wrapper.orderByDesc(ConstructedTransaction::getCreatedAt)
                    .last("LIMIT " + (limit != null ? limit : 100));

            List<ConstructedTransaction> txs = txMapper.selectList(wrapper);
            return txs.stream()
                    .map(tx -> buildResponse(tx, null, tx.getGasPrice()))
                    .collect(Collectors.toList());
        });
    }

    @Transactional
    public Mono<TransactionResponse> updateTransactionStatus(String txId, String status, String txHash) {
        return Mono.fromCallable(() -> {
            ConstructedTransaction tx = txMapper.selectById(txId);
            if (tx == null) {
                throw new BusinessException(404, "Transaction not found: " + txId);
            }

            tx.setStatus(status.toUpperCase());
            if (txHash != null) {
                tx.setTxHash(txHash);
            }
            if (STATUS_CONFIRMED.equals(status.toUpperCase()) || STATUS_SUBMITTED.equals(status.toUpperCase())) {
                tx.setSubmittedAt(Instant.now());
            }
            txMapper.updateById(tx);

            log.info("Updated transaction status: txId={}, status={}", txId, status);
            return buildResponse(tx, null, tx.getGasPrice());
        });
    }

    public Mono<Map<String, Object>> getNonce(String chainId, String address) {
        return Mono.fromCallable(() -> {
            long nonce = getNextNonce(chainId, address);
            return Map.of(
                    "chainId", chainId,
                    "address", address,
                    "nextNonce", nonce,
                    "pendingNonce", nonce + 1
            );
        });
    }

    private String determineTransactionType(ConstructTransactionRequest request) {
        if (request.getToAddress() == null || request.getToAddress().isEmpty()) {
            return "CONTRACT_DEPLOY";
        }
        if (request.getData() == null || request.getData().isEmpty() || "0x".equals(request.getData())) {
            return "ETH_TRANSFER";
        }
        if (request.getData().startsWith("0xa9059cbb")) {
            return "ERC20_TRANSFER";
        }
        if (request.getData().startsWith("0x095ea7b3")) {
            return "ERC20_APPROVE";
        }
        return "CONTRACT_CALL";
    }

    private long getNextNonce(String chainId, String address) {
        try {
            BigInteger nonce = chainAdapterService.getTransactionCount(chainId, address).block();
            return nonce != null ? nonce.longValue() : 0;
        } catch (Exception e) {
            log.warn("Failed to get nonce from chain, using local count: {}", e.getMessage());

            LambdaQueryWrapper<ConstructedTransaction> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ConstructedTransaction::getChainId, chainId)
                    .eq(ConstructedTransaction::getFromAddress, address);
            Long count = txMapper.selectCount(wrapper);
            return count != null ? count : 0;
        }
    }

    private String generateSignature(ConstructedTransaction tx, String signerAddress) {
        try {
            String txData = tx.getChainId() + tx.getFromAddress() + tx.getToAddress() +
                    tx.getValue() + tx.getNonce() + tx.getGasPrice() + tx.getGasLimit() + tx.getData();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(txData.getBytes());
            return "0x" + HexFormat.of().formatHex(hash) + "00";
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException("Failed to generate signature: " + e.getMessage());
        }
    }

    private TransactionResponse buildResponse(ConstructedTransaction tx, String strategy, Long gasPrice) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
                .txId(tx.getTxId())
                .chainId(tx.getChainId())
                .fromAddress(tx.getFromAddress())
                .toAddress(tx.getToAddress())
                .value(tx.getValue())
                .gasLimit(tx.getGasLimit())
                .gasPrice(tx.getGasPrice())
                .nonce(tx.getNonce())
                .data(tx.getData())
                .signedTx(tx.getSignedTx())
                .multisigWalletId(tx.getMultisigWalletId())
                .status(tx.getStatus())
                .txHash(tx.getTxHash())
                .submittedAt(tx.getSubmittedAt())
                .createdAt(tx.getCreatedAt());

        if (strategy != null && gasPrice != null) {
            long baselinePrice = gasPrice;
            long optimizedPrice = applyGasStrategy(gasPrice, strategy);
            long savings = baselinePrice - optimizedPrice;
            double savingsPercent = baselinePrice > 0 ? (savings * 100.0 / baselinePrice) : 0;

            builder.gasOptimization(TransactionResponse.GasOptimizationInfo.builder()
                    .strategy(strategy)
                    .estimatedSavings(savings * tx.getGasLimit())
                    .savingsPercentage(Math.round(savingsPercent * 100.0) / 100.0)
                    .build());
        }

        return builder.build();
    }

    private long applyGasStrategy(long basePrice, String strategy) {
        return switch (strategy) {
            case GAS_STRATEGY_LOW -> Math.round(basePrice * 0.8);
            case GAS_STRATEGY_OPTIMAL -> Math.round(basePrice * 0.95);
            case GAS_STRATEGY_FAST -> Math.round(basePrice * 1.1);
            default -> basePrice;
        };
    }

    private Mono<TransactionResponse> constructTransactionFallback(ConstructTransactionRequest request, Exception e) {
        log.error("Construct transaction fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to construct transaction after retries: " + e.getMessage());
    }

    private Mono<TransactionResponse> signTransactionFallback(SignTransactionRequest request, Exception e) {
        log.error("Sign transaction fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to sign transaction after retries: " + e.getMessage());
    }

    private Mono<TransactionResponse> submitTransactionFallback(SubmitTransactionRequest request, Exception e) {
        log.error("Submit transaction fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to submit transaction after retries: " + e.getMessage());
    }
}
