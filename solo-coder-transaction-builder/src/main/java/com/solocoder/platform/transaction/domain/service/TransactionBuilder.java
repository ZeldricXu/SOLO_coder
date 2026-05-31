package com.solocoder.platform.transaction.domain.service;

import com.solocoder.platform.gas.estimator.api.GasEstimationApi;
import com.solocoder.platform.gas.estimator.api.GasEstimationResult;
import com.solocoder.platform.transaction.domain.model.BuiltTransaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionBuilder {

    private final GasEstimationApi gasEstimationApi;

    public BuiltTransaction buildTransaction(String chainId, String from, String to,
                                             BigDecimal value, String data, Long nonce,
                                             BuiltTransaction.MultisigStrategy multisigStrategy) {
        BuiltTransaction.GasSettings gasSettings = buildGasSettings(chainId, data);

        BuiltTransaction transaction = BuiltTransaction.builder()
                .txId(generateTxId())
                .chainId(chainId)
                .from(from)
                .to(to)
                .value(value != null ? value : BigDecimal.ZERO)
                .data(data)
                .nonce(nonce)
                .gasSettings(gasSettings)
                .multisigStrategy(multisigStrategy)
                .status(BuiltTransaction.TransactionStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        transaction.setUnsignedData(buildUnsignedData(transaction));
        return transaction;
    }

    private BuiltTransaction.GasSettings buildGasSettings(String chainId, String data) {
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("dataLength", data != null ? data.length() : 0);
            context.put("contractInteraction", data != null && data.length() > 2);

            GasEstimationResult estimation = gasEstimationApi.estimateGas(chainId, context);

            BuiltTransaction.GasSettings.GasType gasType =
                    isEIP1559Supported(chainId) ? BuiltTransaction.GasSettings.GasType.EIP1559 : BuiltTransaction.GasSettings.GasType.LEGACY;

            return BuiltTransaction.GasSettings.builder()
                    .gasLimit(estimation.getGasLimit())
                    .gasPrice(estimation.getGasPrice())
                    .maxPriorityFeePerGas(estimation.getMaxPriorityFee())
                    .maxFeePerGas(estimation.getMaxFee())
                    .gasType(gasType)
                    .build();
        } catch (Exception e) {
            log.warn("Gas estimation failed, using defaults: {}", e.getMessage());
            return BuiltTransaction.GasSettings.builder()
                    .gasLimit(21000L)
                    .gasPrice(new BigDecimal("20000000000"))
                    .gasType(BuiltTransaction.GasSettings.GasType.LEGACY)
                    .build();
        }
    }

    private String generateTxId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildUnsignedData(BuiltTransaction transaction) {
        StringBuilder sb = new StringBuilder();
        sb.append(transaction.getChainId()).append(":");
        sb.append(transaction.getFrom()).append(":");
        sb.append(transaction.getTo()).append(":");
        sb.append(transaction.getValue()).append(":");
        sb.append(transaction.getNonce()).append(":");
        sb.append(transaction.getGasSettings().getGasLimit()).append(":");
        sb.append(transaction.getGasSettings().getGasPrice()).append(":");
        sb.append(transaction.getData() != null ? transaction.getData() : "");

        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }

    private boolean isEIP1559Supported(String chainId) {
        try {
            long id = Long.parseLong(chainId);
            return id == 1 || id == 5 || id == 11155111 || id == 137;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public BuiltTransaction applyGasOptimization(BuiltTransaction transaction) {
        BuiltTransaction.GasSettings gasSettings = transaction.getGasSettings();
        if (gasSettings == null) {
            return transaction;
        }

        if (gasSettings.getGasType() == BuiltTransaction.GasSettings.GasType.EIP1559) {
            BigDecimal optimizedMaxFee = gasSettings.getMaxFeePerGas().multiply(new BigDecimal("1.1"));
            gasSettings.setMaxFeePerGas(optimizedMaxFee);
        } else {
            BigDecimal optimizedPrice = gasSettings.getGasPrice().multiply(new BigDecimal("1.1"));
            gasSettings.setGasPrice(optimizedPrice);
        }

        transaction.setGasSettings(gasSettings);
        transaction.setUnsignedData(buildUnsignedData(transaction));
        transaction.setUpdatedAt(LocalDateTime.now());

        return transaction;
    }
}
