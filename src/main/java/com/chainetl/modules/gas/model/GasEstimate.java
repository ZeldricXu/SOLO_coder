package com.chainetl.modules.gas.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.chainetl.common.handler.JsonTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "gas_estimates", autoResultMap = true)
public class GasEstimate {

    @TableId(type = IdType.INPUT)
    private String estimateId;

    private String chainId;

    private String transactionType;

    private Long estimatedGas;

    private Long gasPriceLow;

    private Long gasPriceMedium;

    private Long gasPriceHigh;

    private Long priorityFeeLow;

    private Long priorityFeeMedium;

    private Long priorityFeeHigh;

    private Double confidenceLevel;

    @TableField(typeHandler = JsonTypeHandler.class)
    private Map<String, Object> historicalData;

    private Instant createdAt;
}
