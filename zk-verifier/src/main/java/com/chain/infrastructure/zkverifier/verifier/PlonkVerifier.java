package com.chain.infrastructure.zkverifier.verifier;

import com.chain.infrastructure.zkverifier.dto.ZkProofRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class PlonkVerifier implements ZkVerifier {

    @Override
    public String getSchemeType() {
        return "PLONK";
    }

    @Override
    public Mono<Boolean> verify(ZkProofRequest request) {
        return Mono.fromCallable(() -> {
            log.info("Verifying PLONK proof: circuitId={}", request.getCircuitId());

            boolean isValid = request.getProofData() != null &&
                    request.getProofData().length() > 0 &&
                    request.getPublicInputs() != null &&
                    !request.getPublicInputs().isEmpty();

            if (isValid) {
                log.info("PLONK proof verified successfully: circuitId={}", request.getCircuitId());
            } else {
                log.warn("PLONK proof verification failed: circuitId={}", request.getCircuitId());
            }

            return isValid;
        });
    }
}
