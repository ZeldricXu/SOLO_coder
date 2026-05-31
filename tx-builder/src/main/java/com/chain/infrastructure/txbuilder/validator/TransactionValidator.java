package com.chain.infrastructure.txbuilder.validator;

import com.chain.infrastructure.common.exception.ValidationException;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Component
public class TransactionValidator {

    public Mono<Void> validateBuildRequest(TransactionRequest request) {
        return Mono.fromRunnable(() -> {
            if (request == null) {
                throw new ValidationException("Transaction request cannot be null");
            }
            if (request.getChainType() == null || request.getChainType().isBlank()) {
                throw new ValidationException("Chain type is required");
            }
            if (request.getFromAddress() == null || request.getFromAddress().isBlank()) {
                throw new ValidationException("From address is required");
            }
            if (request.getToAddress() == null || request.getToAddress().isBlank()) {
                throw new ValidationException("To address is required");
            }
            if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("Invalid amount");
            }
            if (request.getGasLimit() == null || request.getGasLimit() <= 0) {
                throw new ValidationException("Invalid gas limit");
            }
            if (request.getGasPrice() == null || request.getGasPrice().compareTo(BigDecimal.ZERO) < 0) {
                throw new ValidationException("Invalid gas price");
            }
        });
    }

    public Mono<Void> validateSignRequest(String txId, String privateKey) {
        return Mono.fromRunnable(() -> {
            if (txId == null || txId.isBlank()) {
                throw new ValidationException("Transaction ID is required");
            }
            if (privateKey == null || privateKey.isBlank()) {
                throw new ValidationException("Private key is required");
            }
        });
    }
}
