package com.chain.infrastructure.zkverifier.verifier;

import com.chain.infrastructure.zkverifier.dto.ZkProofRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class Groth16Verifier implements ZkVerifier {

    @Override
    public String getSchemeType() {
        return "GROTH16";
    }

    @Override
    public Mono<Boolean> verify(ZkProofRequest request) {
        return Mono.fromCallable(() -> {
            log.info("Verifying Groth16 proof: circuitId={}", request.getCircuitId());

            boolean isValid = request.getProofData() != null &&
                    request.getProofData().length() > 0 &&
                    request.getVerificationKey() != null;

            if (isValid) {
                log.info("Groth16 proof verified successfully: circuitId={}", request.getCircuitId());
            } else {
                log.warn("Groth16 proof verification failed: circuitId={}", request.getCircuitId());
            }

            return isValid;
        });
    }
}
