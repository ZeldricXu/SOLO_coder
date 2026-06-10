package notify

import (
	"bytes"
	"context"
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
	"sync"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"go.uber.org/zap"
)

type emailPayload struct {
	subject  string
	body     string
	smtpCfg  *config.SMTPConfig
}

type NotificationEventType string

const (
	NotificationEventStart   NotificationEventType = "start"
	NotificationEventSuccess NotificationEventType = "success"
	NotificationEventFailure NotificationEventType = "failure"
)

type Sender interface {
	FormatMessage(title string, message string, severity types.NotificationSeverity) (interface{}, error)
	GetWebhookURL() string
	Channel() types.NotificationChannel
}

type NotifierSender interface {
	Send(title string, message string, severity types.NotificationSeverity) error
	Channel() types.NotificationChannel
}

type RetryConfig struct {
	MaxAttempts int
	Backoff     time.Duration
	MaxBackoff  time.Duration
	Multiplier  float64
	Timeout     time.Duration
}

type CircuitBreaker struct {
	failureThreshold int
	resetTimeout     time.Duration
	failures         int
	lastFailure      time.Time
	state            string
	mu               sync.Mutex
}

const (
	circuitStateClosed   = "closed"
	circuitStateOpen     = "open"
	circuitStateHalfOpen = "half_open"
)

type RetryableSender struct {
	inner          Sender
	retryConfig    RetryConfig
	circuitBreaker *CircuitBreaker
}

func NewCircuitBreaker(failureThreshold int, resetTimeout time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		failureThreshold: failureThreshold,
		resetTimeout:     resetTimeout,
		state:            circuitStateClosed,
	}
}

func (cb *CircuitBreaker) Allow() bool {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	switch cb.state {
	case circuitStateClosed:
		return true
	case circuitStateOpen:
		if time.Since(cb.lastFailure) > cb.resetTimeout {
			cb.state = circuitStateHalfOpen
			return true
		}
		return false
	case circuitStateHalfOpen:
		return true
	default:
		return true
	}
}

func (cb *CircuitBreaker) RecordSuccess() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.failures = 0
	cb.state = circuitStateClosed
}

func (cb *CircuitBreaker) RecordFailure() {
	cb.mu.Lock()
	defer cb.mu.Unlock()

	cb.failures++
	cb.lastFailure = time.Now()

	if cb.failures >= cb.failureThreshold {
		cb.state = circuitStateOpen
	}
}

func NewRetryableSender(inner Sender, retryConfig RetryConfig) *RetryableSender {
	rs := &RetryableSender{
		inner:       inner,
		retryConfig: retryConfig,
	}
	rs.circuitBreaker = NewCircuitBreaker(5, 30*time.Second)
	return rs
}

func (rs *RetryableSender) Channel() types.NotificationChannel {
	return rs.inner.Channel()
}

func (rs *RetryableSender) Send(title string, message string, severity types.NotificationSeverity) error {
	if !rs.circuitBreaker.Allow() {
		return fmt.Errorf("circuit breaker open for channel %s", rs.inner.Channel())
	}

	payload, err := rs.inner.FormatMessage(title, message, severity)
	if err != nil {
		rs.circuitBreaker.RecordFailure()
		return fmt.Errorf("format message failed: %w", err)
	}

	var lastErr error
	backoff := rs.retryConfig.Backoff

	for attempt := 0; attempt < rs.retryConfig.MaxAttempts; attempt++ {
		if attempt > 0 {
			logger.Info("retrying notification",
				zap.String("channel", string(rs.inner.Channel())),
				zap.Int("attempt", attempt+1),
				zap.Duration("backoff", backoff))
			time.Sleep(backoff)
			backoff = time.Duration(float64(backoff) * rs.retryConfig.Multiplier)
			if backoff > rs.retryConfig.MaxBackoff {
				backoff = rs.retryConfig.MaxBackoff
			}
		}

		err := rs.sendWithTimeout(payload, rs.inner.GetWebhookURL())
		if err == nil {
			rs.circuitBreaker.RecordSuccess()
			return nil
		}

		lastErr = err
		logger.Warn("notification send failed",
			zap.String("channel", string(rs.inner.Channel())),
			zap.Int("attempt", attempt+1),
			zap.Error(err))
	}

	rs.circuitBreaker.RecordFailure()
	return fmt.Errorf("notification failed after %d attempts: %w", rs.retryConfig.MaxAttempts, lastErr)
}

func (rs *RetryableSender) sendWithTimeout(payload interface{}, url string) error {
	ctx, cancel := context.WithTimeout(context.Background(), rs.retryConfig.Timeout)
	defer cancel()

	if url == "" {
		return rs.sendEmail(payload)
	}

	return sendWebhookWithContext(ctx, url, payload)
}

func (rs *RetryableSender) sendEmail(payload interface{}) error {
	emailPayload, ok := payload.(*emailPayload)
	if !ok {
		return fmt.Errorf("invalid email payload type")
	}
	return sendEmailRaw(emailPayload)
}

type Notifier struct {
	cfg     *config.NotificationConfig
	senders map[types.NotificationChannel]NotifierSender
	rules   []types.NotificationRule
}

type DingTalkSender struct {
	webhook string
	secret  string
}

func (s *DingTalkSender) GetWebhookURL() string {
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
	return webhookURL
}

func (s *DingTalkSender) FormatMessage(title string, message string, severity types.NotificationSeverity) (interface{}, error) {
	return map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]string{
			"title": title,
			"text":  fmt.Sprintf("### %s\n\n%s", title, message),
		},
		"at": map[string]interface{}{
			"isAtAll": severity == types.NotificationSeverityCritical,
		},
	}, nil
}

type FeiShuSender struct {
	webhook string
	secret  string
}

func (s *FeiShuSender) GetWebhookURL() string {
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
	return webhookURL
}

func (s *FeiShuSender) FormatMessage(title string, message string, severity types.NotificationSeverity) (interface{}, error) {
	color := map[types.NotificationSeverity]string{
		types.NotificationSeverityInfo:     "blue",
		types.NotificationSeverityWarning:  "yellow",
		types.NotificationSeverityError:    "red",
		types.NotificationSeverityCritical: "red",
	}

	return map[string]interface{}{
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
	}, nil
}

type SlackSender struct {
	webhook string
}

func (s *SlackSender) GetWebhookURL() string {
	return s.webhook
}

func (s *SlackSender) FormatMessage(title string, message string, severity types.NotificationSeverity) (interface{}, error) {
	color := map[types.NotificationSeverity]string{
		types.NotificationSeverityInfo:     "#36a64f",
		types.NotificationSeverityWarning:  "#warning",
		types.NotificationSeverityError:    "#danger",
		types.NotificationSeverityCritical: "#danger",
	}

	return map[string]interface{}{
		"attachments": []map[string]interface{}{
			{
				"color": color[severity],
				"title": title,
				"text":  message,
				"ts":    time.Now().Unix(),
			},
		},
	}, nil
}

type EmailSender struct {
	smtpCfg *config.SMTPConfig
}

func (s *EmailSender) GetWebhookURL() string {
	return ""
}

func (s *EmailSender) FormatMessage(title string, message string, severity types.NotificationSeverity) (interface{}, error) {
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

	return &emailPayload{
		subject: subject,
		body:    emailBody.String(),
		smtpCfg: s.smtpCfg,
	}, nil
}

func NewNotifier(cfg *config.NotificationConfig) *Notifier {
	n := &Notifier{
		cfg:     cfg,
		senders: make(map[types.NotificationChannel]NotifierSender),
	}

	retryCfg := RetryConfig{
		MaxAttempts: 3,
		Backoff:     1 * time.Second,
		MaxBackoff:  10 * time.Second,
		Multiplier:  2.0,
		Timeout:     10 * time.Second,
	}

	if cfg.DingTalk.Webhook != "" {
		dt := NewDingTalkSender(cfg.DingTalk)
		n.senders[types.NotificationChannelDingTalk] = NewRetryableSender(dt, retryCfg)
	}
	if cfg.FeiShu.Webhook != "" {
		fs := NewFeiShuSender(cfg.FeiShu)
		n.senders[types.NotificationChannelFeiShu] = NewRetryableSender(fs, retryCfg)
	}
	if cfg.Slack.Webhook != "" {
		sl := NewSlackSender(cfg.Slack)
		n.senders[types.NotificationChannelSlack] = NewRetryableSender(sl, retryCfg)
	}
	if cfg.SMTP.Host != "" {
		em := NewEmailSender(cfg.SMTP)
		n.senders[types.NotificationChannelEmail] = NewRetryableSender(em, retryCfg)
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

		go func(s NotifierSender, ch types.NotificationChannel) {
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

func NewFeiShuSender(cfg config.FeiShuConfig) *FeiShuSender {
	return &FeiShuSender{
		webhook: cfg.Webhook,
		secret:  cfg.Secret,
	}
}

func (s *FeiShuSender) Channel() types.NotificationChannel {
	return types.NotificationChannelFeiShu
}

func NewSlackSender(cfg config.SlackConfig) *SlackSender {
	return &SlackSender{
		webhook: cfg.Webhook,
	}
}

func (s *SlackSender) Channel() types.NotificationChannel {
	return types.NotificationChannelSlack
}

func NewEmailSender(cfg config.SMTPConfig) *EmailSender {
	return &EmailSender{
		smtpCfg: &cfg,
	}
}

func (s *EmailSender) Channel() types.NotificationChannel {
	return types.NotificationChannelEmail
}

func sendWebhookWithContext(ctx context.Context, url string, payload interface{}) error {
	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("marshal payload failed: %w", err)
	}

	client := &http.Client{}

	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(body))
	if err != nil {
		return fmt.Errorf("create request failed: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("send webhook failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		return fmt.Errorf("webhook returned status: %d", resp.StatusCode)
	}

	return nil
}

func sendEmailRaw(payload *emailPayload) error {
	auth := smtp.PlainAuth("", payload.smtpCfg.User, payload.smtpCfg.Password, payload.smtpCfg.Host)
	addr := fmt.Sprintf("%s:%d", payload.smtpCfg.Host, payload.smtpCfg.Port)
	to := []string{payload.smtpCfg.From}

	if err := smtp.SendMail(addr, auth, payload.smtpCfg.From, to, []byte(payload.body)); err != nil {
		return fmt.Errorf("send email failed: %w", err)
	}

	return nil
}
