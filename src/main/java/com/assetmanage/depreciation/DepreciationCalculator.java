package com.assetmanage.depreciation;

import com.assetmanage.entity.Asset;

import java.math.BigDecimal;

public interface DepreciationCalculator {

    String getMethodCode();

    String getMethodName();

    BigDecimal calculateMonthlyDepreciation(Asset asset);

    boolean isEnabled();
}
