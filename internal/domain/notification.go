package domain

import (
	"time"
)

type NotificationChannel string

const (
	NotificationChannelEmail   NotificationChannel = "email"
	NotificationChannelSMS     NotificationChannel = "sms"
	NotificationChannelDingTalk  NotificationChannel = "dingtalk"
	NotificationChannelWebhook NotificationChannel = "webhook"
)

type NotificationTemplate struct {
	TemplateID string                 `json:"template_id" gorm:"primaryKey;type:varchar(64)"`
	Name       string                 `json:"name"`
	Channel    NotificationChannel  `json:"channel" gorm:"type:varchar(32);index"`
	Title      string                 `json:"title"`
	Content    string                 `json:"content" gorm:"type:text"`
	Variables  map[Variables  map[Variables  map[Variables  map[Variables  map[Variables  map[Variables  map[Variables  map[Variables  map[Variable         `json:"updated_at"`
}

func (NotificationTemplate) TableName() string {
	return "notification_templates"
}

type NotificationRecord struct {
	RecordID   string                 `json:"record_id" gorm:"primaryKey;type:varchar(64)"`
	TemplateID string                 `json:"template_id" gorm:"type:varchar(64);index"`
	Channel    NotificationChannel  `json:"channel" gorm:"type:varchar(32);index"`
	Recipient  string                 `json:"recipient"`
	Title      string                 `json:"title"`
	Content    string                 `json:"content" gorm:"type:text"`
	Status     string                 `json:"status" gorm:"type:varchar(32);index"`
	ErrorMsg   *string                `json:"error_msg,omitempty" gorm:"type:text"`
	SentAt     *time.Time             `json:"sent_at,omitempty"`
	CreatedAt  time.Time              `json:"created_at"`
}

func (NotificationRecord) TableName() string {
	return "notification_records"
}
