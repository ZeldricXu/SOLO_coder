package com.restaurant.mgmt.dto;

public class ReserveTableResponse {
    private String tableId;
    private String tableNumber;
    private String status;

    public ReserveTableResponse() {
    }

    public ReserveTableResponse(String tableId, String tableNumber, String status) {
        this.tableId = tableId;
        this.tableNumber = tableNumber;
        this.status = status;
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getTableNumber() {
        return tableNumber;
    }

    public void setTableNumber(String tableNumber) {
        this.tableNumber = tableNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
