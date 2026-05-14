package com.restaurant.mgmt.dto;

public class QueryStockResponse {
    private StockInfo stock;

    public QueryStockResponse() {
    }

    public QueryStockResponse(double quantity, String status) {
        this.stock = new StockInfo(quantity, status);
    }

    public StockInfo getStock() {
        return stock;
    }

    public void setStock(StockInfo stock) {
        this.stock = stock;
    }

    public static class StockInfo {
        private double quantity;
        private String status;

        public StockInfo() {
        }

        public StockInfo(double quantity, String status) {
            this.quantity = quantity;
            this.status = status;
        }

        public double getQuantity() {
            return quantity;
        }

        public void setQuantity(double quantity) {
            this.quantity = quantity;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
