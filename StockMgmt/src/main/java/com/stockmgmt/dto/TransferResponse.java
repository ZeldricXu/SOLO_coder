package com.stockmgmt.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransferResponse {

    private String fromStockId;
    private String toStockId;
    private String outRecordId;
    private String inRecordId;
    private Integer fromQuantity;
    private Integer toQuantity;
}
