package com.chain.infrastructure.zkverifier.verifier;

import com.chain.infrastructure.zkverifier.dto.ZkProofRequest;
import reactor.core.publisher.Mono;

public interface ZkVerifier {

    String getSchemeType();

    Mono<Boolean> verify(ZkProofRequest request);
}
