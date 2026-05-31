package notification

import "github.com/solocoder/task-scheduler/internal/contracts"

type ChannelType = contracts.ChannelType
type NotificationSeverity = contracts.NotificationSeverity
type Notification = contracts.Notification
type NotificationResult = contracts.NotificationResult
type NotificationTemplate = contracts.NotificationTemplate
type Channel = contracts.NotificationChannel
type TemplateManager = contracts.TemplateRepository
type NotificationService = contracts.NotificationService

const (
	ChannelEmail    = contracts.ChannelEmail
	ChannelSMS      = contracts.ChannelSMS
	ChannelWebhook  = contracts.ChannelWebhook
	ChannelSlack    = contracts.ChannelSlack
	ChannelDingTalk = contracts.ChannelDingTalk
)

const (
	SeverityInfo     = contracts.SeverityInfo
	SeverityWarning  = contracts.SeverityWarning
	SeverityError    = contracts.SeverityError
	SeverityCritical = contracts.SeverityCritical
)
