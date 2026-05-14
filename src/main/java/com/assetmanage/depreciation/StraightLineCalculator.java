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
public class StraightLineCalculator implements DepreciationCalculator {

    private final DepreciationConfigProperties config;

    @Override
    public String getMethodCode() {
        return DepreciationMethod.STRAIGHT_LINE.getCode();
    }

    @Override
    public String getMethodName() {
        return "直线法";
    }

    @Override
    public BigDecimal calculateMonthlyDepreciation(Asset asset) {
        BigDecimal originalValue = asset.getPurchasePrice();
        BigDecimal rate = asset.getDepreciationRate();
        if (rate == null) {
            rate = new BigDecimal("0.20");
        }

        BigDecimal annualDepreciation = originalValue.multiply(rate);
        return annualDepreciation.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    @Override
    public boolean isEnabled() {
        return config.isMethodEnabled(getMethodCode());
    }
}
