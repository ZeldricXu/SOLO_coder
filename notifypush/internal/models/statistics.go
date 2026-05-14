package models

type Statistics struct {
	StatID       string  `json:"stat_id"`
	StatDate     string  `json:"stat_date"`
	Channel      string  `json:"channel"`
	SendCount    int64   `json:"send_count"`
	SuccessCount int64   `json:"success_count"`
	FailCount    int64   `json:"fail_count"`
	DeliveryRate float64 `json:"delivery_rate"`
}

type BatchStatistics struct {
	BatchID      string  `json:"batch_id"`
	TotalCount   int     `json:"total_count"`
	SentCount    int     `json:"sent_count"`
	SuccessCount int     `json:"success_count"`
	FailCount    int     `json:"fail_count"`
	SuccessRate  float64 `json:"success_rate"`
}
