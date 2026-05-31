package com.chain.infrastructure.txbuilder.service;

import com.chain.infrastructure.persistence.entity.ChainTransaction;
import com.chain.infrastructure.txbuilder.constructor.TransactionConstructor;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import com.chain.infrastructure.txbuilder.dto.TransactionResult;
import com.chain.infrastructure.txbuilder.repository.TransactionRepository;
import com.chain.infrastructure.txbuilder.signer.TransactionSigner;
import com.chain.infrastructure.txbuilder.validator.TransactionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionBuilderService {

    private final TransactionValidator validator;
    private final TransactionConstructor constructor;
    private final TransactionSigner signer;
    private final TransactionRepository repository;
    private final GasOptimizationService gasOptimizationService;

    public Mono<TransactionResult> buildTransaction(TransactionRequest request) {
        return validator.validateBuildRequest(request)
                .then(constructor.buildTransactionId())
                .flatMap(txId -> gasOptimizationService.optimize(request, request.getGasPrice(), request.getGasLimit())
                        .flatMap(gasResult -> constructor.buildUnsignedTransaction(request, gasResult.optimizedGasPrice(), gasResult.optimizedGasLimit())
                                .flatMap(unsignedTx -> saveTransaction(txId, request, gasResult, unsignedTx))
                                .map(tx -> buildResult(tx, gasResult))
                        )
                )
                .doOnSuccess(result -> log.info("Transaction built: txId={}, strategy={}",
                        result.getTxId(), result.getGasPrice()));
    }

    private Mono<ChainTransaction> saveTransaction(String txId,
                                                   TransactionRequest request,
                                                   GasOptimizationService.GasOptimizationResult gasResult,
                                                   String unsignedTx) {
        ChainTransaction tx = new ChainTransaction();
        tx.setTxId(txId);
        tx.setChainType(request.getChainType());
        tx.setChainId(request.getChainId());
        tx.setFromAddress(request.getFromAddress());
        tx.setToAddress(request.getToAddress());
        tx.setAmount(request.getAmount());
        tx.setGasLimit(gasResult.optimizedGasLimit());
        tx.setGasPrice(gasResult.optimizedGasPrice());
        tx.setNonce(request.getNonce());
        tx.setTxData(unsignedTx);
        tx.setStatus("PENDING");
        tx.setMultisigWalletId(request.getMultisigWalletId());
        return repository.save(tx);
    }

    private TransactionResult buildResult(ChainTransaction tx,
                                           GasOptimizationService.GasOptimizationResult gasResult) {
        TransactionResult result = new TransactionResult();
        result.setTxId(tx.getTxId());
        result.setChainType(tx.getChainType());
        result.setFromAddress(tx.getFromAddress());
        result.setToAddress(tx.getToAddress());
        result.setAmount(tx.getAmount());
        result.setGasLimit(tx.getGasLimit());
        result.setGasPrice(tx.getGasPrice());
        result.setEstimatedFee(tx.getGasPrice().multiply(BigDecimal.valueOf(tx.getGasLimit())));
        result.setStatus("PENDING");
        return result;
    }

    public Mono<TransactionResult> signTransaction(String txId, String privateKey) {
        return validator.validateSignRequest(txId, privateKey)
                .then(repository.findById(txId))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Transaction not found: " + txId)))
                .flatMap(tx -> signer.sign(tx.getTxData(), privateKey)
                        .flatMap(signature -> updateSignedTransaction(tx, signature))
                        .map(this::buildSignedResult)
                )
                .doOnSuccess(result -> log.info("Transaction signed: txId={}, txHash={}",
                        result.getTxId(), result.getTxHash()));
    }

    private Mono<ChainTransaction> updateSignedTransaction(ChainTransaction tx,
                                                           java.util.Map.Entry<String, String> signature) {
        tx.setSignedTx(signature.getKey());
        tx.setTxHash(signature.getValue());
        tx.setStatus("SIGNED");
        return repository.update(tx);
    }

    private TransactionResult buildSignedResult(ChainTransaction tx) {
        TransactionResult result = new TransactionResult();
        result.setTxId(tx.getTxId());
        result.setChainType(tx.getChainType());
        result.setFromAddress(tx.getFromAddress());
        result.setToAddress(tx.getToAddress());
        result.setAmount(tx.getAmount());
        result.setGasLimit(tx.getGasLimit());
        result.setGasPrice(tx.getGasPrice());
        result.setSignedTx(tx.getSignedTx());
        result.setTxHash(tx.getTxHash());
        result.setStatus("SIGNED");
        return result;
    }

    public Mono<ChainTransaction> getTransaction(String txId) {
        return repository.findById(txId);
    }
}
