package com.homeservice.dto;

public class SettlementResponse {
    private String settlementId;
    private Double amount;

    public SettlementResponse() {}

    public SettlementResponse(String settlementId, Double amount) {
        this.settlementId = settlementId;
        this.amount = amount;
    }

    public String getSettlementId() { return settlementId; }
    public void setSettlementId(String settlementId) { this.settlementId = settlementId; }
    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }
}
