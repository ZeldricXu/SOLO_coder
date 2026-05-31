package vo

import "time"

type PaginationRequest struct {
	Page     int `json:"page" form:"page"`
	PageSize int `json:"page_size" form:"page_size"`
}

type PaginationResponse struct {
	Total    int64 `json:"total"`
	Page     int   `json:"page"`
	PageSize int   `json:"page_size"`
	Pages    int   `json:"pages"`
}

type TimeRange struct {
	StartTime time.Time `json:"start_time"`
	EndTime   time.Time `json:"end_time"`
}

type BaseResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

type BatchOperationRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchOperation struct {
	Action string                 `json:"action"`
	ID     string                 `json:"id"`
	Params map[string]interface{} `json:"params,omitempty"`
}

type BatchOperationResponse struct {
	BatchID string                  `json:"batch_id"`
	Results []BatchOperationResult `json:"results"`
}

type BatchOperationResult struct {
	ID     string                 `json:"id"`
	Status string                 `json:"status"`
	Data   interface{}            `json:"data,omitempty"`
	Error  string                 `json:"error,omitempty"`
}

type Event struct {
	EventID   string                 `json:"event_id"`
	EventType string                 `json:"event_type"`
	Source    string                 `json:"source"`
	Data      map[string]interface{} `json:"data"`
	Timestamp time.Time              `json:"timestamp"`
}
