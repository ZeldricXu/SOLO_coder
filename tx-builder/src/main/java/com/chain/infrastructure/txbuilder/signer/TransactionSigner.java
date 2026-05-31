package com.chain.infrastructure.txbuilder.signer;

import com.chain.infrastructure.common.util.IdGenerator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.AbstractMap;
import java.util.Map;

@Component
public class TransactionSigner {

    public Mono<Map.Entry<String, String>> sign(String txData, String privateKey) {
        return Mono.fromCallable(() -> {
            String signedTx = IdGenerator.generateHash(txData + privateKey);
            String txHash = IdGenerator.generateHash(signedTx);
            return new AbstractMap.SimpleEntry<>(signedTx, txHash);
        });
    }
}
