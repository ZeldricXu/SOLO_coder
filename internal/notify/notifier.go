package notify

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/smtp"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"go.uber.org/zap"
)

type NotificationEventType string

const (
	NotificationEventStart   NotificationEventType = "start"
	NotificationEventSuccess NotificationEventType = "success"
	NotificationEventFailure NotificationEventType = "failure"
)

type Sender interface {
	Send(title string, message string, severity types.NotificationSeverity) error
	Channel() types.NotificationChannel
}

type Notifier struct {
	cfg     *config.NotificationConfig
	senders map[types.NotificationChannel]Sender
	rules   []types.NotificationRule
}

type DingTalkSender struct {
	webhook string
	secret  string
}

type FeiShuSender struct {
	webhook string
	secret  string
}

type SlackSender struct {
	webhook string
}

type EmailSender struct {
	smtpCfg *config.SMTPConfig
}

func NewNotifier(cfg *config.NotificationConfig) *Notifier {
	n := &Notifier{
		cfg:     cfg,
		senders: make(map[types.NotificationChannel]Sender),
	}

	if cfg.DingTalk.Webhook != "" {
		n.senders[types.NotificationChannelDingTalk] = NewDingTalkSender(cfg.DingTalk)
	}
	if cfg.FeiShu.Webhook != "" {
		n.senders[types.NotificationChannelFeiShu] = NewFeiShuSender(cfg.FeiShu)
	}
	if cfg.Slack.Webhook != "" {
		n.senders[types.NotificationChannelSlack] = NewSlackSender(cfg.Slack)
	}
	if cfg.SMTP.Host != "" {
		n.senders[types.NotificationChannelEmail] = NewEmailSender(cfg.SMTP)
	}

	return n
}

func (n *Notifier) SetRules(rules []types.NotificationRule) {
	n.rules = rules
}

func (n *Notifier) Notify(execution *models.PipelineExecution, severity types.NotificationSeverity) {
	eventType := getEventType(execution)
	title := buildTitle(execution, eventType)
	message := buildMessage(execution, eventType, severity)

	channels := n.getChannels(eventType, severity)
	if len(channels) == 0 {
		logger.Info("no channels matched for notification",
			zap.String("execution_id", string(execution.ID)),
			zap.String("event_type", string(eventType)),
			zap.String("severity", string(severity)))
		return
	}

	for _, channel := range channels {
		sender, ok := n.senders[channel]
		if !ok {
			logger.Warn("sender not configured for channel",
				zap.String("channel", string(channel)))
			continue
		}

		go func(s Sender, ch types.NotificationChannel) {
			if err := s.Send(title, message, severity); err != nil {
				logger.Error("failed to send notification",
					zap.String("channel", string(ch)),
					zap.String("execution_id", string(execution.ID)),
					zap.Error(err))
			}
		}(sender, channel)
	}
}

func (n *Notifier) getChannels(eventType NotificationEventType, severity types.NotificationSeverity) []types.NotificationChannel {
	if len(n.rules) == 0 {
		return n.getDefaultChannels(severity)
	}

	var channels []types.NotificationChannel
	eventTypeStr := types.EventType(eventType)

	for _, rule := range n.rules {
		if !matchesSeverity(rule.Severity, severity) {
			continue
		}

		eventMatched := len(rule.Events) == 0
		for _, e := range rule.Events {
			if e == eventTypeStr {
				eventMatched = true
				break
			}
		}
		if !eventMatched {
			continue
		}

		for _, ch := range rule.Channels {
			if !containsChannel(channels, ch) {
				channels = append(channels, ch)
			}
		}
	}

	return channels
}

func (n *Notifier) getDefaultChannels(severity types.NotificationSeverity) []types.NotificationChannel {
	var channels []types.NotificationChannel

	switch severity {
	case types.NotificationSeverityError, types.NotificationSeverityCritical:
		for ch := range n.senders {
			channels = append(channels, ch)
		}
	case types.NotificationSeverityWarning:
		for ch := range n.senders {
			if ch != types.NotificationChannelEmail {
				channels = append(channels, ch)
			}
		}
	default:
		if _, ok := n.senders[types.NotificationChannelDingTalk]; ok {
			channels = append(channels, types.NotificationChannelDingTalk)
		}
		if _, ok := n.senders[types.NotificationChannelFeiShu]; ok {
			channels = append(channels, types.NotificationChannelFeiShu)
		}
	}

	return channels
}

func matchesSeverity(ruleSeverity, targetSeverity types.NotificationSeverity) bool {
	severityOrder := map[types.NotificationSeverity]int{
		types.NotificationSeverityInfo:     0,
		types.NotificationSeverityWarning:  1,
		types.NotificationSeverityError:    2,
		types.NotificationSeverityCritical: 3,
	}

	ruleLevel, ok1 := severityOrder[ruleSeverity]
	targetLevel, ok2 := severityOrder[targetSeverity]
	if !ok1 || !ok2 {
		return false
	}

	return targetLevel >= ruleLevel
}

func containsChannel(channels []types.NotificationChannel, ch types.NotificationChannel) bool {
	for _, c := range channels {
		if c == ch {
			return true
		}
	}
	return false
}

func getEventType(execution *models.PipelineExecution) NotificationEventType {
	switch execution.Status {
	case types.ExecutionStatusRunning:
		return NotificationEventStart
	case types.ExecutionStatusSuccess:
		return NotificationEventSuccess
	case types.ExecutionStatusFailed, types.ExecutionStatusTimeout, types.ExecutionStatusCancelled:
		return NotificationEventFailure
	default:
		return NotificationEventStart
	}
}

func buildTitle(execution *models.PipelineExecution, eventType NotificationEventType) string {
	statusText := map[NotificationEventType]string{
		NotificationEventStart:   "开始执行",
		NotificationEventSuccess: "执行成功",
		NotificationEventFailure: "执行失败",
	}

	return fmt.Sprintf("[CI/CD] %s - %s", execution.PipelineName, statusText[eventType])
}

func buildMessage(execution *models.PipelineExecution, eventType NotificationEventType, severity types.NotificationSeverity) string {
	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("**流水线**: %s\n", execution.PipelineName))
	sb.WriteString(fmt.Sprintf("**执行ID**: %s\n", execution.ID))
	sb.WriteString(fmt.Sprintf("**状态**: %s\n", execution.Status))
	sb.WriteString(fmt.Sprintf("**触发方式**: %s (%s)\n", execution.TriggerSource, execution.TriggerType))
	sb.WriteString(fmt.Sprintf("**项目**: %s\n", execution.ProjectID))

	if execution.Branch != "" {
		sb.WriteString(fmt.Sprintf("**分支**: %s\n", execution.Branch))
	}
	if execution.Commit != "" {
		sb.WriteString(fmt.Sprintf("**提交**: %s\n", execution.Commit[:7]))
	}
	if execution.Message != "" {
		sb.WriteString(fmt.Sprintf("**信息**: %s\n", execution.Message))
	}
	if execution.Author != "" {
		sb.WriteString(fmt.Sprintf("**作者**: %s\n", execution.Author))
	}

	if execution.StartedAt != nil {
		sb.WriteString(fmt.Sprintf("**开始时间**: %s\n", execution.StartedAt.Format("2006-01-02 15:04:05")))
	}
	if execution.CompletedAt != nil {
		sb.WriteString(fmt.Sprintf("**完成时间**: %s\n", execution.CompletedAt.Format("2006-01-02 15:04:05")))
	}
	if execution.DurationSec != nil {
		sb.WriteString(fmt.Sprintf("**耗时**: %ds\n", *execution.DurationSec))
	}

	if execution.Error != "" {
		sb.WriteString(fmt.Sprintf("**错误**: %s\n", execution.Error))
	}

	sb.WriteString(fmt.Sprintf("**严重级别**: %s\n", severity))

	return sb.String()
}

func NewDingTalkSender(cfg config.DingTalkConfig) *DingTalkSender {
	return &DingTalkSender{
		webhook: cfg.Webhook,
		secret:  cfg.Secret,
	}
}

func (s *DingTalkSender) Channel() types.NotificationChannel {
	return types.NotificationChannelDingTalk
}

func (s *DingTalkSender) Send(title string, message string, severity types.NotificationSeverity) error {
	webhookURL := s.webhook
	if s.secret != "" {
		timestamp := strconv.FormatInt(time.Now().UnixMilli(), 10)
		stringToSign := fmt.Sprintf("%s\n%s", timestamp, s.secret)

		mac := hmac.New(sha256.New, []byte(s.secret))
		mac.Write([]byte(stringToSign))
		signData := mac.Sum(nil)
		sign := base64.StdEncoding.EncodeToString(signData)

		webhookURL = fmt.Sprintf("%s&timestamp=%s&sign=%s",
			webhookURL, timestamp, url.QueryEscape(sign))
	}

	payload := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]string{
			"title": title,
			"text":  fmt.Sprintf("### %s\n\n%s", title, message),
		},
		"at": map[string]interface{}{
			"isAtAll": severity == types.NotificationSeverityCritical,
		},
	}

	return sendWebhook(webhookURL, payload)
}

func NewFeiShuSender(cfg config.FeiShuConfig) *FeiShuSender {
	return &FeiShuSender{
		webhook: cfg.Webhook,
		secret:  cfg.Secret,
	}
}

func (s *FeiShuSender) Channel() types.NotificationChannel {
	return types.NotificationChannelFeiShu
}

func (s *FeiShuSender) Send(title string, message string, severity types.NotificationSeverity) error {
	webhookURL := s.webhook
	if s.secret != "" {
		timestamp := strconv.FormatInt(time.Now().Unix(), 10)
		stringToSign := fmt.Sprintf("%s\n%s", timestamp, s.secret)

		mac := hmac.New(sha256.New, []byte(stringToSign))
		signData := mac.Sum(nil)
		sign := base64.StdEncoding.EncodeToString(signData)

		webhookURL = fmt.Sprintf("%s&timestamp=%s&sign=%s",
			webhookURL, timestamp, url.QueryEscape(sign))
	}

	color := map[types.NotificationSeverity]string{
		types.NotificationSeverityInfo:     "blue",
		types.NotificationSeverityWarning:  "yellow",
		types.NotificationSeverityError:    "red",
		types.NotificationSeverityCritical: "red",
	}

	payload := map[string]interface{}{
		"msg_type": "interactive",
		"card": map[string]interface{}{
			"config": map[string]interface{}{
				"wide_screen_mode": true,
			},
			"header": map[string]interface{}{
				"title": map[string]string{
					"tag":     "plain_text",
					"content": title,
				},
				"template": color[severity],
			},
			"elements": []map[string]interface{}{
				{
					"tag": "div",
					"text": map[string]string{
						"tag":     "lark_md",
						"content": message,
					},
				},
			},
		},
	}

	return sendWebhook(webhookURL, payload)
}

func NewSlackSender(cfg config.SlackConfig) *SlackSender {
	return &SlackSender{
		webhook: cfg.Webhook,
	}
}

func (s *SlackSender) Channel() types.NotificationChannel {
	return types.NotificationChannelSlack
}

func (s *SlackSender) Send(title string, message string, severity types.NotificationSeverity) error {
	color := map[types.NotificationSeverity]string{
		types.NotificationSeverityInfo:     "#36a64f",
		types.NotificationSeverityWarning:  "#warning",
		types.NotificationSeverityError:    "#danger",
		types.NotificationSeverityCritical: "#danger",
	}

	payload := map[string]interface{}{
		"attachments": []map[string]interface{}{
			{
				"color": color[severity],
				"title": title,
				"text":  message,
				"ts":    time.Now().Unix(),
			},
		},
	}

	return sendWebhook(s.webhook, payload)
}

func NewEmailSender(cfg config.SMTPConfig) *EmailSender {
	return &EmailSender{
		smtpCfg: &cfg,
	}
}

func (s *EmailSender) Channel() types.NotificationChannel {
	return types.NotificationChannelEmail
}

func (s *EmailSender) Send(title string, message string, severity types.NotificationSeverity) error {
	subject := fmt.Sprintf("[%s] %s", strings.ToUpper(string(severity)), title)

	headers := make(map[string]string)
	headers["From"] = s.smtpCfg.From
	headers["To"] = s.smtpCfg.From
	headers["Subject"] = subject
	headers["MIME-Version"] = "1.0"
	headers["Content-Type"] = "text/plain; charset=UTF-8"

	var emailBody strings.Builder
	for k, v := range headers {
		emailBody.WriteString(fmt.Sprintf("%s: %s\r\n", k, v))
	}
	emailBody.WriteString("\r\n")
	emailBody.WriteString(message)

	auth := smtp.PlainAuth("", s.smtpCfg.User, s.smtpCfg.Password, s.smtpCfg.Host)
	addr := fmt.Sprintf("%s:%d", s.smtpCfg.Host, s.smtpCfg.Port)
	to := []string{s.smtpCfg.From}

	if err := smtp.SendMail(addr, auth, s.smtpCfg.From, to, []byte(emailBody.String())); err != nil {
		return fmt.Errorf("send email failed: %w", err)
	}

	return nil
}

func sendWebhook(url string, payload interface{}) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload failed: %w", err)
	}

	client := &http.Client{
		Timeout: 10 * time.Second,
	}

	resp, err := client.Post(url, "application/json", bytes.NewBuffer(body))
	if err != nil {
		return fmt.Errorf("send webhook failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("webhook returned status: %d", resp.StatusCode)
	}

	return nil
}
