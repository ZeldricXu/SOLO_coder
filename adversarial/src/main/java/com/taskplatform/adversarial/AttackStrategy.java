package com.taskplatform.adversarial;

public interface AttackStrategy {

    String getName();

    String generateAdversarialPrompt(String originalPrompt);

    double evaluateSuccess(String modelResponse, String originalPrompt);

    default int getOrder() {
        return 0;
    }
}
