package com.assetmanage.depreciation;

import com.assetmanage.config.DepreciationConfigProperties;
import com.assetmanage.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DepreciationCalculatorFactory {

    private final Map<String, DepreciationCalculator> calculatorMap = new HashMap<>();
    private final DepreciationConfigProperties config;

    public DepreciationCalculatorFactory(List<DepreciationCalculator> calculators, 
                                          DepreciationConfigProperties config) {
        this.config = config;
        for (DepreciationCalculator calculator : calculators) {
            calculatorMap.put(calculator.getMethodCode(), calculator);
            log.info("注册折旧方法计算器: {}", calculator.getMethodCode());
        }
    }

    public DepreciationCalculator getCalculator(String methodCode) {
        DepreciationCalculator calculator = calculatorMap.get(methodCode);
        if (calculator == null) {
            throw new BusinessException("不支持的折旧方法: " + methodCode);
        }
        if (!calculator.isEnabled()) {
            throw new BusinessException("折旧方法已禁用: " + methodCode);
        }
        return calculator;
    }

    public boolean isMethodSupported(String methodCode) {
        return calculatorMap.containsKey(methodCode);
    }

    public boolean isMethodEnabled(String methodCode) {
        return config.isMethodEnabled(methodCode);
    }

    public Map<String, String> getAvailableMethods() {
        Map<String, String> methods = new HashMap<>();
        for (Map.Entry<String, DepreciationCalculator> entry : calculatorMap.entrySet()) {
            if (entry.getValue().isEnabled()) {
                methods.put(entry.getKey(), entry.getValue().getMethodName());
            }
        }
        return methods;
    }

    public List<String> getAllMethodCodes() {
        return List.copyOf(calculatorMap.keySet());
    }
}
