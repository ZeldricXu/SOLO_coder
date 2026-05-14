package models

type ApiResponse struct {
	Code int         `json:"code"`
	Data interface{} `json:"data,omitempty"`
	Msg  string      `json:"msg,omitempty"`
}

type SendRequest struct {
	TemplateID string            `json:"template_id"`
	Channel    string            `json:"channel"`
	Receiver   string            `json:"receiver"`
	Variables  map[string]string `json:"variables"`
	Priority   int               `json:"priority,omitempty"`
	ScheduleAt string            `json:"schedule_at,omitempty"`
}

type SendResponse struct {
	NotifyID string `json:"notify_id"`
	Status   string `json:"status"`
}

type BatchSendRequest struct {
	TemplateID string              `json:"template_id"`
	Channel    string              `json:"channel"`
	Receivers  []string            `json:"receivers"`
	Variables  []map[string]string `json:"variables,omitempty"`
	Priority   int                 `json:"priority,omitempty"`
	BatchSize  int                 `json:"batch_size,omitempty"`
	ScheduleAt string              `json:"schedule_at,omitempty"`
}

type BatchSendResponse struct {
	BatchID string `json:"batch_id"`
	Status  string `json:"status"`
}

type StatusQueryResponse struct {
	NotifyID       string `json:"notify_id"`
	Channel        string `json:"channel"`
	SendStatus     string `json:"send_status"`
	DeliveryStatus string `json:"delivery_status"`
	ErrorMessage   string `json:"error_message,omitempty"`
	RetryCount     int    `json:"retry_count"`
}

type TemplateCreateRequest struct {
	TemplateID      string   `json:"template_id"`
	TemplateName    string   `json:"template_name"`
	TemplateType    string   `json:"template_type"`
	TemplateContent string   `json:"template_content"`
	Subject         string   `json:"subject,omitempty"`
	Variables       []string `json:"variables"`
}
