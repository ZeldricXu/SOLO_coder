package domain

import (
	"math/big"
	"time"
)

type GasPriceData struct {
	ID            string    `json:"id" gorm:"primaryKey"`
	ChainID       int64     `json:"chain_id" gorm:"index"`
	BlockNumber   uint64    `json:"block_number" gorm:"index"`
	BlockHash     string    `json:"block_hash"`
	BaseFee       string    `json:"base_fee"`
	PriorityFee   string    `json:"priority_fee"`
	GasUsed       uint64    `json:"gas_used"`
	GasLimit      uint64    `json:"gas_limit"`
	GasUtilization float64  `json:"gas_utilization"`
	NumTxs        int       `json:"num_txs"`
	AvgGasPrice   string    `json:"avg_gas_price"`
	MedianGasPrice string   `json:"median_gas_price"`
	MinGasPrice   string    `json:"min_gas_price"`
	MaxGasPrice   string    `json:"max_gas_price"`
	Timestamp     time.Time `json:"timestamp" gorm:"index"`
}

type GasEstimate struct {
	ChainID         int64    `json:"chain_id"`
	BaseFee         *big.Int `json:"base_fee"`
	BaseFeeGwei     float64  `json:"base_fee_gwei"`
	PriorityFeeLow  *big.Int `json:"priority_fee_low"`
	PriorityFeeAvg  *big.Int `json:"priority_fee_avg"`
	PriorityFeeHigh *big.Int `json:"priority_fee_high"`
	GasPriceLow     *big.Int `json:"gas_price_low"`
	GasPriceAvg     *big.Int `json:"gas_price_avg"`
	GasPriceHigh    *big.Int `json:"gas_price_high"`
	MaxFeePerGas    *big.Int `json:"max_fee_per_gas"`
	EstimatedAt     time.Time `json:"estimated_at"`
	Confidence      float64   `json:"confidence"`
	Trend           string    `json:"trend"`
	NextBlockBaseFee *big.Int `json:"next_block_base_fee,omitempty"`
}

type HistoricalGasStats struct {
	ChainID        int64     `json:"chain_id"`
	TimeWindow     string    `json:"time_window"`
	AvgBaseFee     *big.Int  `json:"avg_base_fee"`
	PeakBaseFee    *big.Int  `json:"peak_base_fee"`
	MinBaseFee     *big.Int  `json:"min_base_fee"`
	AvgPriorityFee *big.Int  `json:"avg_priority_fee"`
	Volatility     float64   `json:"volatility"`
	StartTimestamp time.Time `json:"start_timestamp"`
	EndTimestamp   time.Time `json:"end_timestamp"`
}

type EstimateRequest struct {
	ChainID  int64  `json:"chain_id" binding:"required"`
	To       string `json:"to"`
	Data     string `json:"data"`
	Value    string `json:"value"`
	GasLimit uint64 `json:"gas_limit"`
}

type EstimateResponse struct {
	Estimate    GasEstimate `json:"estimate"`
	GasLimit    uint64      `json:"gas_limit"`
	TotalCostLow  string    `json:"total_cost_low"`
	TotalCostAvg  string    `json:"total_cost_avg"`
	TotalCostHigh string    `json:"total_cost_high"`
}

const (
	TrendUp      = "up"
	TrendDown    = "down"
	TrendStable  = "stable"
	TrendUnknown = "unknown"
)
