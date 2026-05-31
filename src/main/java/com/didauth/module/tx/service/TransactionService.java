package com.didauth.module.tx.service;

import com.didauth.common.enums.ChainType;
import com.didauth.common.exception.BusinessException;
import com.didauth.core.entity.ChainTransaction;
import com.didauth.core.mapper.ChainTransactionMapper;
import com.didauth.module.tx.dto.BuildTransactionRequest;
import com.didauth.module.tx.dto.BuildTransactionResponse;
import com.didauth.module.tx.dto.SignTransactionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final ChainTransactionMapper transactionMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public Mono<BuildTransactionResponse> buildTransaction(BuildTransactionRequest request) {
        return Mono.fromCallable(() -> {
            ChainType chainType = ChainType.fromCode(request.getChainType());
            String txId = "tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            Map<String, Object> txData = new HashMap<>();
            txData.put("chainId", chainType.getChainId());
            txData.put("from", request.getFromAddress());
            txData.put("to", request.getToAddress());
            txData.put("value", request.getValue() != null ? request.getValue() : "0x0");
            txData.put("data", request.getData() != null ? request.getData() : "0x");
            txData.put("gasPrice", request.getGasPrice() != null ? request.getGasPrice() : "0x0");
            txData.put("gasLimit", request.getGasLimit() != null ? request.getGasLimit() : "0x5208");
            txData.put("nonce", request.getNonce() != null ? request.getNonce() : "0x0");

            ChainTransaction transaction = new ChainTransaction();
            transaction.setTxId(txId);
            transaction.setChainType(chainType.getCode());
            transaction.setFromAddress(request.getFromAddress());
            transaction.setToAddress(request.getToAddress());
            transaction.setValue(request.getValue());
            transaction.setGasPrice(request.getGasPrice());
            transaction.setGasLimit(request.getGasLimit());
            transaction.setNonce(request.getNonce());
            transaction.setData(request.getData());
            transaction.setSignType(request.getMultisigWalletId() != null ? "MULTISIG" : "SINGLE");
            transaction.setMultisigWalletId(request.getMultisigWalletId());
            transaction.setStatus("BUILT");

            transactionMapper.insert(transaction);

            meterRegistry.counter("transaction.build.count", "chain", chainType.getCode()).increment();

            BuildTransactionResponse response = new BuildTransactionResponse();
            response.setTxId(txId);
            response.setChainType(chainType.getCode());
            response.setFromAddress(request.getFromAddress());
            response.setToAddress(request.getToAddress());
            response.setValue(request.getValue());
            response.setGasPrice(request.getGasPrice());
            response.setGasLimit(request.getGasLimit());
            response.setNonce(request.getNonce());
            response.setData(request.getData());
            response.setRawTransaction(objectMapper.writeValueAsString(txData));
            response.setSignType(transaction.getSignType());
            response.setMultisigWalletId(request.getMultisigWalletId());

            return response;
        });
    }

    public Mono<String> signTransaction(SignTransactionRequest request) {
        return Mono.fromCallable(() -> {
            ChainTransaction transaction = transactionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainTransaction>()
                            .eq(ChainTransaction::getTxId, request.getTxId()));

            if (transaction == null) {
                throw BusinessException.notFound("Transaction not found: " + request.getTxId());
            }

            if (!"BUILT".equals(transaction.getStatus()) {
                throw BusinessException.paramError("Transaction is not in BUILT state");
            }

            String signedTx = generateSignedTransaction(transaction, request.getPrivateKey(), request.getSignType());

            transaction.setSignedTx(signedTx);
            transaction.setStatus("SIGNED");
            transactionMapper.updateById(transaction);

            meterRegistry.counter("transaction.sign.count", "chain", transaction.getChainType()).increment();

            return signedTx;
        });
    }

    private String generateSignedTransaction(ChainTransaction transaction, String privateKey, String signType) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String txContent = transaction.getTxId() + transaction.getChainType() + transaction.getFromAddress() +
                transaction.getToAddress() + transaction.getValue();
        byte[] hash = digest.digest(txContent.getBytes());

        StringBuilder signed = new StringBuilder("0x");
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) signed.append('0');
            signed.append(hex);
        }
        return signed.toString();
    }

    public Mono<String> submitTransaction(String txId) {
        return Mono.fromCallable(() -> {
            ChainTransaction transaction = transactionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainTransaction>()
                            .eq(ChainTransaction::getTxId, txId));

            if (transaction == null) {
                throw BusinessException.notFound("Transaction not found: " + txId);
            }

            if (!"SIGNED".equals(transaction.getStatus())) {
                throw BusinessException.paramError("Transaction is not in SIGNED state");
            }

            String txHash = "0x" + UUID.randomUUID().toString().replace("-", "");

            transaction.setTxHash(txHash);
            transaction.setStatus("SUBMITTED");
            transactionMapper.updateById(transaction);

            meterRegistry.counter("transaction.submit.count", "chain", transaction.getChainType()).increment();

            return txHash;
        });
    }

    public Mono<ChainTransaction> getTransaction(String txId) {
        return Mono.fromCallable(() -> {
            ChainTransaction transaction = transactionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainTransaction>()
                            .eq(ChainTransaction::getTxId, txId));
            if (transaction == null) {
                throw BusinessException.notFound("Transaction not found: " + txId);
            }
            return transaction;
        });
    }

    public Mono<List<ChainTransaction>> listTransactions(String chainType, String fromAddress, String status) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChainTransaction>();
            if (chainType != null) wrapper.eq(ChainTransaction::getChainType, chainType);
            if (fromAddress != null) wrapper.eq(ChainTransaction::getFromAddress, fromAddress);
            if (status != null) wrapper.eq(ChainTransaction::getStatus, status);
            wrapper.orderByDesc(ChainTransaction::getCreatedAt);
            return transactionMapper.selectList(wrapper);
        });
    }
}
