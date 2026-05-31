package com.chain.infrastructure.txbuilder.constructor;

import com.chain.infrastructure.common.util.IdGenerator;
import com.chain.infrastructure.common.util.JsonUtils;
import com.chain.infrastructure.txbuilder.dto.TransactionRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

@Component
public class TransactionConstructor {

    public Mono<String> buildUnsignedTransaction(TransactionRequest request,
                                                  BigDecimal optimizedGasPrice,
                                                  Long optimizedGasLimit) {
        return Mono.fromCallable(() ->
                IdGenerator.generateHash(
                        JsonUtils.toJson(request) +
                                optimizedGasPrice +
                                optimizedGasLimit +
                                System.currentTimeMillis()
                )
        );
    }

    public Mono<String> buildTransactionId() {
        return Mono.fromCallable(() -> IdGenerator.generateId("tx"));
    }
}
