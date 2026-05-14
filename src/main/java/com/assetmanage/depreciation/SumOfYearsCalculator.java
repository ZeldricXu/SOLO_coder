package com.assetmanage.depreciation;

import com.assetmanage.config.DepreciationConfigProperties;
import com.assetmanage.entity.Asset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

@Slf4j
@Component
@RequiredArgsConstructor
public class SumOfYearsCalculator implements DepreciationCalculator {

    private final DepreciationConfigProperties config;

    @Override
    public String getMethodCode() {
        return "sum_of_years";
    }

    @Override
    public String getMethodName() {
        return "年限总和法";
    }

    @Override
    public BigDecimal calculateMonthlyDepreciation(Asset asset) {
        int usefulLife = asset.getUsefulLife() != null ? asset.getUsefulLife() : 5;
        BigDecimal originalValue = asset.getPurchasePrice();

        int remainingMonths = calculateRemainingMonths(asset, usefulLife);
        int remainingYears = (remainingMonths + 11) / 12;

        if (remainingYears <= 0) {
            return BigDecimal.ZERO;
        }

        int sumOfYears = calculateSumOfYears(usefulLife);

        BigDecimal depreciableValue = originalValue;
        BigDecimal annualRate = BigDecimal.valueOf(remainingYears)
                .divide(BigDecimal.valueOf(sumOfYears), 6, RoundingMode.HALF_UP);
        BigDecimal annualDepreciation = depreciableValue.multiply(annualRate);
        BigDecimal monthlyDepreciation = annualDepreciation.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

        log.debug("年限总和法计算: assetId={}, usefulLife={}, remainingYears={}, sumOfYears={}, monthly={}",
                asset.getAssetId(), usefulLife, remainingYears, sumOfYears, monthlyDepreciation);

        return monthlyDepreciation;
    }

    private int calculateRemainingMonths(Asset asset, int usefulLife) {
        LocalDate purchaseDate = asset.getPurchaseDate();
        if (purchaseDate == null) {
            if (asset.getCreatedAt() != null) {
                purchaseDate = asset.getCreatedAt().toLocalDate();
            } else {
                purchaseDate = LocalDate.now();
            }
        }

        Period age = Period.between(purchaseDate, LocalDate.now());
        int monthsUsed = age.getYears() * 12 + age.getMonths();
        int totalMonths = usefulLife * 12;

        return Math.max(0, totalMonths - monthsUsed);
    }

    private int calculateSumOfYears(int usefulLife) {
        return usefulLife * (usefulLife + 1) / 2;
    }

    @Override
    public boolean isEnabled() {
        return config.isMethodEnabled(getMethodCode());
    }
}
