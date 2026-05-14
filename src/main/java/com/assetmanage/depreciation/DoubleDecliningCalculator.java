package com.assetmanage.depreciation;

import com.assetmanage.config.DepreciationConfigProperties;
import com.assetmanage.entity.Asset;
import com.assetmanage.enums.DepreciationMethod;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
@RequiredArgsConstructor
public class DoubleDecliningCalculator implements DepreciationCalculator {

    private final DepreciationConfigProperties config;
    private static final BigDecimal DOUBLE_RATE = new BigDecimal("2");

    @Override
    public String getMethodCode() {
        return DepreciationMethod.DOUBLE_DECLINING.getCode();
    }

    @Override
    public String getMethodName() {
        return "双倍余额递减法";
    }

    @Override
    public BigDecimal calculateMonthlyDepreciation(Asset asset) {
        BigDecimal netValue = asset.getCurrentValue();
        if (netValue == null) {
            netValue = asset.getPurchasePrice();
        }

        int usefulLife = asset.getUsefulLife() != null ? asset.getUsefulLife() : 5;

        BigDecimal straightLineRate = BigDecimal.ONE.divide(BigDecimal.valueOf(usefulLife), 6, RoundingMode.HALF_UP);
        BigDecimal doubleRate = straightLineRate.multiply(DOUBLE_RATE);
        BigDecimal monthlyRate = doubleRate.divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);

        return netValue.multiply(monthlyRate).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public boolean isEnabled() {
        return config.isMethodEnabled(getMethodCode());
    }
}
