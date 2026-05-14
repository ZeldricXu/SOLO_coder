package models

import "time"

type TemplateType string

const (
	TemplateTypeSMS    TemplateType = "sms"
	TemplateTypeEmail  TemplateType = "email"
	TemplateTypeApp    TemplateType = "app"
)

type Template struct {
	TemplateID      string            `json:"template_id"`
	TemplateName    string            `json:"template_name"`
	TemplateType    TemplateType      `json:"template_type"`
	TemplateContent string            `json:"template_content"`
	Subject         string            `json:"subject,omitempty"`
	Variables       []string          `json:"variables"`
	Status          string            `json:"status"`
	CreatedAt       time.Time         `json:"created_at"`
	UpdatedAt       time.Time         `json:"updated_at"`
}
