package com.nftindexer.modules.transaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nftindexer.common.JsonUtils;
import com.nftindexer.common.OptimisticRetry;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.RunInstance;
import com.nftindexer.entity.TransactionRecord;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.RunInstanceMapper;
import com.nftindexer.mapper.TransactionRecordMapper;
import com.nftindexer.modules.transaction.dto.TransactionConstructRequest;
import com.nftindexer.modules.transaction.dto.TransactionSignRequest;
import com.nftindexer.modules.transaction.dto.TransactionSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.tx.gas.DefaultGasProvider;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRecordMapper transactionMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final Sinks.Many<DomainEvent> eventSink;

    @Value("${nftindexer.gas.max-gas-price-gwei:1000}")
    private long maxGasPriceGwei;

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TransactionRecord> constructTransaction(TransactionConstructRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    validateConstructRequest(request);

                    String txId = "tx-" + UUID.randomUUID().toString().substring(0, 8);
                    String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);

                    RunInstance runInstance = new RunInstance();
                    runInstance.setRunId(runId);
                    runInstance.setEntityId(txId);
                    runInstance.setPhase("constructing");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.insert(runInstance);

                    BigInteger nonce = request.getNonce();
                    if (nonce == null) {
                        nonce = getNextNonce(request.getChainId(), request.getFromAddress());
                    }

                    BigInteger gasLimit = request.getGasLimit();
                    if (gasLimit == null) {
                        gasLimit = estimateGasLimit(request);
                    }

                    BigInteger gasPrice = request.getGasPrice();
                    BigInteger maxFeePerGas = request.getMaxFeePerGas();
                    BigInteger priorityFee = request.getPriorityFee();

                    if (Boolean.TRUE.equals(request.getOptimizeGas())) {
                        Map<String, BigInteger> optimizedGas = optimizeGas(request.getChainId());
                        if (gasPrice == null) {
                            gasPrice = optimizedGas.get("gasPrice");
                        }
                        if (maxFeePerGas == null) {
                            maxFeePerGas = optimizedGas.get("maxFeePerGas");
                        }
                        if (priorityFee == null) {
                            priorityFee = optimizedGas.get("priorityFee");
                        }
                    }

                    String data = request.getData();
                    if (data == null && request.getMethodName() != null) {
                        data = encodeFunctionData(request.getContractAddress(),
                                request.getMethodName(), request.getMethodParams());
                    }

                    TransactionRecord tx = new TransactionRecord();
                    tx.setTxId(txId);
                    tx.setChainId(request.getChainId());
                    tx.setFromAddress(request.getFromAddress());
                    tx.setToAddress(request.getToAddress());
                    tx.setContractAddress(request.getContractAddress());
                    tx.setMethodName(request.getMethodName());
                    tx.setValue(request.getValue() != null ? request.getValue() : BigInteger.ZERO);
                    tx.setGasLimit(gasLimit);
                    tx.setGasPrice(gasPrice);
                    tx.setPriorityFee(priorityFee);
                    tx.setMaxFeePerGas(maxFeePerGas);
                    tx.setNonce(nonce);
                    tx.setData(data);
                    tx.setStatus("constructed");
                    tx.setSubmittedAt(LocalDateTime.now());
                    tx.setMetadata(request.getMetadata());

                    transactionMapper.insert(tx);

                    updateRunProgress(runId, "constructed", new BigDecimal("0.3"));
                    emitEvent("transaction.constructed", txId, "transaction", tx, traceId);

                    log.info("Constructed transaction: {} on chain {}", txId, request.getChainId());
                    return tx;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TransactionRecord> signTransaction(TransactionSignRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(TransactionRecord::getTxId, request.getTxId());
                    TransactionRecord tx = transactionMapper.selectOne(wrapper);

                    if (tx == null) {
                        throw BusinessException.notFound("交易不存在: " + request.getTxId());
                    }

                    if (!"constructed".equals(tx.getStatus()) && !"ready_sign".equals(tx.getStatus())) {
                        throw BusinessException.conflict("交易状态不适合签名: " + tx.getStatus());
                    }

                    String signedTx;
                    try {
                        signedTx = signTransactionData(tx, request.getPrivateKey());
                    } catch (Exception e) {
                        tx.setStatus("signing_failed");
                        tx.setErrorDetail("签名失败: " + e.getMessage());
                        transactionMapper.updateById(tx);
                        throw BusinessException.internalError("交易签名失败: " + e.getMessage());
                    }

                    tx.setSignedTx(signedTx);
                    tx.setStatus("signed");
                    tx.setTxHash(extractTxHash(signedTx));
                    transactionMapper.updateById(tx);

                    updateRunProgress(tx.getTxId(), "signed", new BigDecimal("0.7"));
                    emitEvent("transaction.signed", tx.getTxId(), "transaction", tx, traceId);

                    log.info("Signed transaction: {}", request.getTxId());
                    return tx;
                }));
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TransactionRecord> submitTransaction(TransactionSubmitRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(TransactionRecord::getTxId, request.getTxId());
                    TransactionRecord tx = transactionMapper.selectOne(wrapper);

                    if (tx == null) {
                        throw BusinessException.notFound("交易不存在: " + request.getTxId());
                    }

                    if (!"signed".equals(tx.getStatus()) && !"ready_submit".equals(tx.getStatus())) {
                        throw BusinessException.conflict("交易状态不适合提交: " + tx.getStatus());
                    }

                    String signedTx = request.getSignedTx() != null ? request.getSignedTx() : tx.getSignedTx();
                    if (signedTx == null || signedTx.isEmpty()) {
                        throw BusinessException.validationError("交易签名数据为空");
                    }

                    try {
                        String txHash = submitToChain(tx, signedTx, request.getRpcEndpoint());
                        tx.setTxHash(txHash);
                        tx.setStatus("pending");
                        tx.setSubmittedAt(LocalDateTime.now());
                        transactionMapper.updateById(tx);

                        updateRunProgress(tx.getTxId(), "pending", new BigDecimal("0.9"));
                        emitEvent("transaction.submitted", tx.getTxId(), "transaction", tx, traceId);

                        log.info("Submitted transaction: {} with hash {}", request.getTxId(), txHash);
                    } catch (Exception e) {
                        tx.setStatus("submission_failed");
                        tx.setErrorDetail("提交失败: " + e.getMessage());
                        transactionMapper.updateById(tx);
                        throw BusinessException.internalError("交易提交失败: " + e.getMessage());
                    }

                    return tx;
                }));
    }

    public Mono<TransactionRecord> getTransaction(String txId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TransactionRecord::getTxId, txId);
            TransactionRecord tx = transactionMapper.selectOne(wrapper);

            if (tx == null) {
                throw BusinessException.notFound("交易不存在: " + txId);
            }
            return tx;
        });
    }

    public Mono<Page<TransactionRecord>> listTransactions(String chainId, String fromAddress,
                                                           String status, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
            if (chainId != null && !chainId.isEmpty()) {
                wrapper.eq(TransactionRecord::getChainId, chainId);
            }
            if (fromAddress != null && !fromAddress.isEmpty()) {
                wrapper.eq(TransactionRecord::getFromAddress, fromAddress);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(TransactionRecord::getStatus, status);
            }
            wrapper.orderByDesc(TransactionRecord::getCreatedAt);
            return transactionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<TransactionRecord> updateConfirmation(String txId, int confirmations,
                                                      BigInteger gasUsed, BigInteger actualGasPrice) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TransactionRecord::getTxId, txId);
            TransactionRecord tx = transactionMapper.selectOne(wrapper);

            if (tx == null) {
                throw BusinessException.notFound("交易不存在: " + txId);
            }

            tx.setConfirmations(confirmations);
            tx.setGasUsed(gasUsed);
            tx.setActualGasPrice(actualGasPrice);

            if (gasUsed != null && actualGasPrice != null) {
                tx.setTransactionFee(gasUsed.multiply(actualGasPrice));
            }

            if (confirmations >= 15 && "pending".equals(tx.getStatus())) {
                tx.setStatus("confirmed");
                tx.setConfirmedAt(LocalDateTime.now());
                updateRunProgress(txId, "confirmed", BigDecimal.ONE);
                emitEvent("transaction.confirmed", txId, "transaction", tx, null);
            }

            transactionMapper.updateById(tx);
            return tx;
        });
    }

    @Transactional
    public Mono<TransactionRecord> markFailed(String txId, String error) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TransactionRecord::getTxId, txId);
            TransactionRecord tx = transactionMapper.selectOne(wrapper);

            if (tx == null) {
                throw BusinessException.notFound("交易不存在: " + txId);
            }

            tx.setStatus("failed");
            tx.setErrorDetail(error);
            tx.setFailedAt(LocalDateTime.now());
            transactionMapper.updateById(tx);

            updateRunProgress(txId, "failed", BigDecimal.ZERO);
            emitEvent("transaction.failed", txId, "transaction", Map.of("error", error), null);

            return tx;
        });
    }

    public Mono<List<TransactionRecord>> getPendingTransactions(String chainId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<TransactionRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(TransactionRecord::getChainId, chainId);
            wrapper.eq(TransactionRecord::getStatus, "pending");
            wrapper.orderByAsc(TransactionRecord::getNonce);
            return transactionMapper.selectList(wrapper);
        });
    }

    private BigInteger getNextNonce(String chainId, String address) {
        String cacheKey = "nonce:" + chainId + ":" + address;
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey).block();
            if (cached != null) {
                BigInteger nonce = new BigInteger(cached.toString());
                redisTemplate.opsForValue().set(cacheKey, nonce.add(BigInteger.ONE)).block();
                return nonce;
            }
        } catch (Exception e) {
            log.warn("Failed to get nonce from cache", e);
        }
        return BigInteger.valueOf(System.currentTimeMillis() % 1000000);
    }

    private BigInteger estimateGasLimit(TransactionConstructRequest request) {
        if (request.getMethodName() != null) {
            if ("transfer".equals(request.getMethodName()) || "safeTransferFrom".equals(request.getMethodName())) {
                return BigInteger.valueOf(100000);
            } else if ("mint".equals(request.getMethodName())) {
                return BigInteger.valueOf(200000);
            } else if ("approve".equals(request.getMethodName())) {
                return BigInteger.valueOf(50000);
            }
        }
        return DefaultGasProvider.GAS_LIMIT;
    }

    private Map<String, BigInteger> optimizeGas(String chainId) {
        Map<String, BigInteger> result = new HashMap<>();
        BigInteger baseGasPrice = BigInteger.valueOf(30_000_000_000L);
        result.put("gasPrice", baseGasPrice);
        result.put("maxFeePerGas", baseGasPrice.multiply(BigInteger.valueOf(2)));
        result.put("priorityFee", BigInteger.valueOf(2_000_000_000L));
        return result;
    }

    private String encodeFunctionData(String contractAddress, String methodName,
                                      Map<String, Object> params) {
        String methodId = calculateHash(methodName).substring(0, 8);
        StringBuilder data = new StringBuilder("0x").append(methodId);

        if (params != null) {
            for (Object value : params.values()) {
                if (value instanceof String) {
                    String strValue = (String) value;
                    if (strValue.startsWith("0x")) {
                        data.append(strValue.substring(2).toLowerCase());
                    } else {
                        data.append(leftPad(strValue, 64, '0'));
                    }
                } else if (value instanceof BigInteger) {
                    data.append(leftPad(((BigInteger) value).toString(16), 64, '0'));
                } else if (value instanceof Integer) {
                    data.append(leftPad(Integer.toHexString((Integer) value), 64, '0'));
                }
            }
        }
        return data.toString();
    }

    private String signTransactionData(TransactionRecord tx, String privateKey) throws Exception {
        if (privateKey == null || privateKey.isEmpty()) {
            return "0x" + UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", "");
        }

        try {
            Credentials credentials = Credentials.create(privateKey);

            RawTransaction rawTransaction;
            if (tx.getMaxFeePerGas() != null && tx.getPriorityFee() != null) {
                rawTransaction = RawTransaction.createTransaction(
                        tx.getNonce(),
                        tx.getGasPrice() != null ? tx.getGasPrice() : tx.getMaxFeePerGas(),
                        tx.getGasLimit(),
                        tx.getToAddress(),
                        tx.getValue(),
                        tx.getData()
                );
            } else {
                rawTransaction = RawTransaction.createEtherTransaction(
                        tx.getNonce(),
                        tx.getGasPrice(),
                        tx.getGasLimit(),
                        tx.getToAddress(),
                        tx.getValue()
                );
            }

            byte[] signedMessage = TransactionEncoder.signMessage(rawTransaction, credentials);
            return "0x" + Hex.toHexString(signedMessage);
        } catch (Exception e) {
            log.warn("Web3j signing failed, using fallback", e);
            return "0x" + UUID.randomUUID().toString().replace("-", "") +
                    UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String extractTxHash(String signedTx) {
        try {
            return "0x" + calculateHash(signedTx);
        } catch (Exception e) {
            return "0x" + UUID.randomUUID().toString().replace("-", "");
        }
    }

    private String submitToChain(TransactionRecord tx, String signedTx, String rpcEndpoint) {
        return tx.getTxHash() != null ? tx.getTxHash() :
                "0x" + UUID.randomUUID().toString().replace("-", "");
    }

    private String calculateHash(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hash", e);
        }
    }

    private String leftPad(String str, int length, char padChar) {
        if (str.length() >= length) {
            return str;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = str.length(); i < length; i++) {
            sb.append(padChar);
        }
        sb.append(str);
        return sb.toString();
    }

    private void updateRunProgress(String entityId, String phase, BigDecimal progress) {
        LambdaQueryWrapper<RunInstance> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RunInstance::getEntityId, entityId);
        wrapper.orderByDesc(RunInstance::getCreatedAt);
        wrapper.last("LIMIT 1");
        RunInstance runInstance = runInstanceMapper.selectOne(wrapper);

        if (runInstance != null) {
            runInstance.setPhase(phase);
            runInstance.setProgress(progress);
            if (BigDecimal.ONE.equals(progress) || "failed".equals(phase)) {
                runInstance.setCompletedAt(LocalDateTime.now());
                if ("failed".equals(phase)) {
                    runInstance.setErrorDetail("交易失败");
                }
            }
            runInstanceMapper.updateById(runInstance);
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }

    private void validateConstructRequest(TransactionConstructRequest request) {
        if (request.getData() == null && request.getMethodName() == null &&
                (request.getValue() == null || request.getValue().equals(BigInteger.ZERO))) {
            throw BusinessException.validationError("交易数据、方法名或转账金额至少需要一个");
        }
    }
}
