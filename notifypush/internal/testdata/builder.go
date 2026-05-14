package testdata

import (
	"fmt"
	"notifypush/internal/models"
	"time"
)

type NotificationBuilder struct {
	notification *models.Notification
}

func NewNotificationBuilder() *NotificationBuilder {
	now := time.Now()
	return &NotificationBuilder{
		notification: &models.Notification{
			NotifyID:    "notify_test_001",
			NotifyType:  "standard",
			TemplateID:  "template_test",
			Channel:     "sms",
			Receiver:    "13800138000",
			Content:     "测试通知内容",
			Priority:    5,
			CreatedAt:   now,
			ScheduledAt: now,
			Status:      models.NotifyStatusPending,
			RetryCount:  0,
			MaxRetries:  3,
			Variables:   map[string]string{},
		},
	}
}

func (b *NotificationBuilder) WithNotifyID(id string) *NotificationBuilder {
	b.notification.NotifyID = id
	return b
}

func (b *NotificationBuilder) WithChannel(channel string) *NotificationBuilder {
	b.notification.Channel = channel
	return b
}

func (b *NotificationBuilder) WithReceiver(receiver string) *NotificationBuilder {
	b.notification.Receiver = receiver
	return b
}

func (b *NotificationBuilder) WithTemplateID(templateID string) *NotificationBuilder {
	b.notification.TemplateID = templateID
	return b
}

func (b *NotificationBuilder) WithStatus(status models.NotifyStatus) *NotificationBuilder {
	b.notification.Status = status
	return b
}

func (b *NotificationBuilder) WithRetryCount(count int) *NotificationBuilder {
	b.notification.RetryCount = count
	return b
}

func (b *NotificationBuilder) WithMaxRetries(max int) *NotificationBuilder {
	b.notification.MaxRetries = max
	return b
}

func (b *NotificationBuilder) WithPriority(priority int) *NotificationBuilder {
	b.notification.Priority = priority
	return b
}

func (b *NotificationBuilder) WithVariables(vars map[string]string) *NotificationBuilder {
	b.notification.Variables = vars
	return b
}

func (b *NotificationBuilder) WithBatchID(batchID string) *NotificationBuilder {
	b.notification.BatchID = batchID
	return b
}

func (b *NotificationBuilder) Build() *models.Notification {
	return b.notification
}

type TemplateBuilder struct {
	template *models.Template
}

func NewTemplateBuilder() *TemplateBuilder {
	now := time.Now()
	return &TemplateBuilder{
		template: &models.Template{
			TemplateID:      "template_test",
			TemplateName:    "测试模板",
			TemplateType:    models.TemplateTypeSMS,
			TemplateContent: "您的订单{order_id}已提交，金额{amount}元",
			Subject:         "",
			Variables:       []string{"order_id", "amount"},
			Status:          "active",
			CreatedAt:       now,
			UpdatedAt:       now,
		},
	}
}

func (b *TemplateBuilder) WithTemplateID(id string) *TemplateBuilder {
	b.template.TemplateID = id
	return b
}

func (b *TemplateBuilder) WithTemplateName(name string) *TemplateBuilder {
	b.template.TemplateName = name
	return b
}

func (b *TemplateBuilder) WithTemplateType(templateType models.TemplateType) *TemplateBuilder {
	b.template.TemplateType = templateType
	return b
}

func (b *TemplateBuilder) WithContent(content string) *TemplateBuilder {
	b.template.TemplateContent = content
	return b
}

func (b *TemplateBuilder) WithSubject(subject string) *TemplateBuilder {
	b.template.Subject = subject
	return b
}

func (b *TemplateBuilder) WithVariables(vars []string) *TemplateBuilder {
	b.template.Variables = vars
	return b
}

func (b *TemplateBuilder) Build() *models.Template {
	return b.template
}

type BatchTaskBuilder struct {
	batchTask *models.BatchTask
}

func NewBatchTaskBuilder() *BatchTaskBuilder {
	now := time.Now()
	return &BatchTaskBuilder{
		batchTask: &models.BatchTask{
			BatchID:      "batch_test_001",
			NotifyType:   "batch",
			TemplateID:   "template_test",
			Channel:      "sms",
			Receivers:    []string{"13800138000", "13800138001", "13800138002"},
			Variables:    nil,
			BatchSize:    100,
			TotalCount:   3,
			SentCount:    0,
			SuccessCount: 0,
			FailCount:    0,
			Status:       models.BatchStatusPending,
			Priority:     5,
			ScheduledAt:  now,
			CreatedAt:    now,
		},
	}
}

func (b *BatchTaskBuilder) WithBatchID(id string) *BatchTaskBuilder {
	b.batchTask.BatchID = id
	return b
}

func (b *BatchTaskBuilder) WithChannel(channel string) *BatchTaskBuilder {
	b.batchTask.Channel = channel
	return b
}

func (b *BatchTaskBuilder) WithTemplateID(templateID string) *BatchTaskBuilder {
	b.batchTask.TemplateID = templateID
	return b
}

func (b *BatchTaskBuilder) WithReceivers(receivers []string) *BatchTaskBuilder {
	b.batchTask.Receivers = receivers
	b.batchTask.TotalCount = len(receivers)
	return b
}

func (b *BatchTaskBuilder) WithBatchSize(size int) *BatchTaskBuilder {
	b.batchTask.BatchSize = size
	return b
}

func (b *BatchTaskBuilder) WithStatus(status models.BatchStatus) *BatchTaskBuilder {
	b.batchTask.Status = status
	return b
}

func (b *BatchTaskBuilder) WithPriority(priority int) *BatchTaskBuilder {
	b.batchTask.Priority = priority
	return b
}

func (b *BatchTaskBuilder) Build() *models.BatchTask {
	return b.batchTask
}

type SendStatusRecordBuilder struct {
	record *models.SendStatusRecord
}

func NewSendStatusRecordBuilder() *SendStatusRecordBuilder {
	now := time.Now()
	return &SendStatusRecordBuilder{
		record: &models.SendStatusRecord{
			StatusID:       "status_test_001",
			NotifyID:       "notify_test_001",
			Channel:        "sms",
			SendStatus:     models.SendStatusPending,
			DeliveryStatus: models.DeliveryStatusPending,
			RetryAttempt:   0,
			CreatedAt:      now,
		},
	}
}

func (b *SendStatusRecordBuilder) WithStatusID(id string) *SendStatusRecordBuilder {
	b.record.StatusID = id
	return b
}

func (b *SendStatusRecordBuilder) WithNotifyID(notifyID string) *SendStatusRecordBuilder {
	b.record.NotifyID = notifyID
	return b
}

func (b *SendStatusRecordBuilder) WithChannel(channel string) *SendStatusRecordBuilder {
	b.record.Channel = channel
	return b
}

func (b *SendStatusRecordBuilder) WithSendStatus(status models.SendStatus) *SendStatusRecordBuilder {
	b.record.SendStatus = status
	return b
}

func (b *SendStatusRecordBuilder) WithDeliveryStatus(status models.DeliveryStatus) *SendStatusRecordBuilder {
	b.record.DeliveryStatus = status
	return b
}

func (b *SendStatusRecordBuilder) WithRetryAttempt(attempt int) *SendStatusRecordBuilder {
	b.record.RetryAttempt = attempt
	return b
}

func (b *SendStatusRecordBuilder) WithErrorMessage(msg string) *SendStatusRecordBuilder {
	b.record.ErrorMessage = msg
	return b
}

func (b *SendStatusRecordBuilder) Build() *models.SendStatusRecord {
	return b.record
}

type ChannelConfigBuilder struct {
	config *models.ChannelConfig
}

func NewChannelConfigBuilder() *ChannelConfigBuilder {
	now := time.Now()
	return &ChannelConfigBuilder{
		config: &models.ChannelConfig{
			ChannelID:   "channel_sms_01",
			ChannelType: models.ChannelTypeSMS,
			ChannelName: "测试短信通道",
			ChannelConfig: map[string]interface{}{
				"provider":  "aliyun",
				"api_key":   "test_api_key",
				"sign_name": "测试签名",
			},
			Status:    models.ChannelStatusActive,
			Priority:  1,
			CreatedAt: now,
			UpdatedAt: now,
		},
	}
}

func (b *ChannelConfigBuilder) WithChannelID(id string) *ChannelConfigBuilder {
	b.config.ChannelID = id
	return b
}

func (b *ChannelConfigBuilder) WithChannelType(channelType models.ChannelType) *ChannelConfigBuilder {
	b.config.ChannelType = channelType
	return b
}

func (b *ChannelConfigBuilder) WithChannelName(name string) *ChannelConfigBuilder {
	b.config.ChannelName = name
	return b
}

func (b *ChannelConfigBuilder) WithStatus(status models.ChannelStatus) *ChannelConfigBuilder {
	b.config.Status = status
	return b
}

func (b *ChannelConfigBuilder) WithPriority(priority int) *ChannelConfigBuilder {
	b.config.Priority = priority
	return b
}

func (b *ChannelConfigBuilder) Build() *models.ChannelConfig {
	return b.config
}

func GeneratePhoneNumbers(count int) []string {
	numbers := make([]string, count)
	for i := 0; i < count; i++ {
		numbers[i] = fmt.Sprintf("138%08d", i)
	}
	return numbers
}
