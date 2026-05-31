package com.llmgateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan(basePackages = {
        "com.llmgateway.featurestore.mapper",
        "com.llmgateway.document.mapper",
        "com.llmgateway.modelregistry.mapper",
        "com.llmgateway.inference.mapper",
        "com.llmgateway.adversarial.mapper",
        "com.llmgateway.gpu.mapper",
        "com.llmgateway.promptlab.mapper",
        "com.llmgateway.evaluation.mapper"
})
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }
}
