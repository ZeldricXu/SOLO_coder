package notification

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"model-inference-platform/internal/pkg/config"
	"strings"
	"sync"
	"time"

	"go.uber.org/zap"
)

type NotificationSeverity string

const (
	SeverityInfo     NotificationSeverity = "info"
	SeverityWarning  NotificationSeverity = "warning"
	SeverityError    NotificationSeverity = "error"
	SeverityCritical NotificationSeverity = "critical"
)

type NotificationType string

const (
	TypeDriftDetected   NotificationType = "drift_detected"
	TypeModelRegistered NotificationType = "model_registered"
	TypeABTestComplete  NotificationType = "abtest_complete"
	TypeAlert           NotificationType = "alert"
)

type Notification struct {
	ID          string                 `json:"id"`
	Type        NotificationType       `json:"type"`
	Severity    NotificationSeverity   `json:"severity"`
	Title       string                 `json:"title"`
	Message     string                 `json:"message"`
	Details     map[string]interface{} `json:"details,omitempty"`
	ModelName   string                 `json:"model_name,omitempty"`
	ModelID     string                 `json:"model_id,omitempty"`
	Namespace   string                 `json:"namespace,omitempty"`
	Recipients  []string               `json:"recipients,omitempty"`
	CreatedAt   time.Time              `json:"created_at"`
}

type Notifier interface {
	Send(ctx context.Context, notification *Notification) error
	Name() string
}

type DingTalkNotifier struct {
	webhookURL string
	secret     string
	httpClient *http.Client
	logger     *zap.Logger
}

func NewDingTalkNotifier(webhookURL, secret string, logger *zap.Logger) *DingTalkNotifier {
	return &DingTalkNotifier{
		webhookURL: webhookURL,
		secret:     secret,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		logger: logger,
	}
}

func (d *DingTalkNotifier) Name() string {
	return "dingtalk"
}

func (d *DingTalkNotifier) Send(ctx context.Context, notification *Notification) error {
	if d.webhookURL == "" {
		return fmt.Errorf("dingtalk webhook URL not configured")
	}

	signURL, err := d.generateSignedURL()
	if err != nil {
		return fmt.Errorf("failed to generate dingtalk signature: %w", err)
	}

	markdownText := d.buildMarkdown(notification)

	payload := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]string{
			"title": notification.Title,
			"text":  markdownText,
		},
		"at": map[string]interface{}{
			"isAtAll": notification.Severity == SeverityCritical,
		},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, signURL, bytes.NewBuffer(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := d.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send dingtalk notification: %w", err)
	}
	defer resp.Body.Close()

	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to decode response: %w", err)
	}

	if errCode, ok := result["errcode"].(float64); ok && errCode != 0 {
		return fmt.Errorf("dingtalk API error: %v", result["errmsg"])
	}

	d.logger.Info("DingTalk notification sent successfully",
		zap.String("notification_id", notification.ID),
		zap.String("type", string(notification.Type)))

	return nil
}

func (d *DingTalkNotifier) generateSignedURL() (string, error) {
	if d.secret == "" {
		return d.webhookURL, nil
	}

	timestamp := time.Now().UnixMilli()
	stringToSign := fmt.Sprintf("%d\n%s", timestamp, d.secret)

	h := hmac.New(sha256.New, []byte(d.secret))
	h.Write([]byte(stringToSign))
	signData := h.Sum(nil)

	sign := url.QueryEscape(base64.StdEncoding.EncodeToString(signData))

	return fmt.Sprintf("%s&timestamp=%d&sign=%s", d.webhookURL, timestamp, sign), nil
}

func (d *DingTalkNotifier) buildMarkdown(notification *Notification) string {
	var emoji string
	switch notification.Severity {
	case SeverityCritical:
		emoji = "🚨"
	case SeverityError:
		emoji = "❌"
	case SeverityWarning:
		emoji = "⚠️"
	default:
		emoji = "ℹ️"
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("## %s %s\n\n", emoji, notification.Title))
	sb.WriteString(fmt.Sprintf("**时间**: %s\n\n", notification.CreatedAt.Format("2006-01-02 15:04:05")))
	sb.WriteString(fmt.Sprintf("**类型**: %s\n\n", notification.Type))
	sb.WriteString(fmt.Sprintf("**严重程度**: %s\n\n", notification.Severity))

	if notification.ModelName != "" {
		sb.WriteString(fmt.Sprintf("**模型**: %s\n\n", notification.ModelName))
	}
	if notification.Namespace != "" {
		sb.WriteString(fmt.Sprintf("**命名空间**: %s\n\n", notification.Namespace))
	}

	sb.WriteString(fmt.Sprintf("**描述**:\n\n%s\n\n", notification.Message))

	if len(notification.Details) > 0 {
		sb.WriteString("**详细信息**:\n\n")
		for k, v := range notification.Details {
			sb.WriteString(fmt.Sprintf("- **%s**: %v\n", k, v))
		}
	}

	return sb.String()
}

type WeChatWorkNotifier struct {
	webhookURL string
	httpClient *http.Client
	logger     *zap.Logger
}

func NewWeChatWorkNotifier(webhookURL string, logger *zap.Logger) *WeChatWorkNotifier {
	return &WeChatWorkNotifier{
		webhookURL: webhookURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		logger: logger,
	}
}

func (w *WeChatWorkNotifier) Name() string {
	return "wechat_work"
}

func (w *WeChatWorkNotifier) Send(ctx context.Context, notification *Notification) error {
	if w.webhookURL == "" {
		return fmt.Errorf("wechat work webhook URL not configured")
	}

	content := w.buildContent(notification)

	payload := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]interface{}{
			"content": content,
		},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return fmt.Errorf("failed to marshal payload: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, w.webhookURL, bytes.NewBuffer(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := w.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send wechat work notification: %w", err)
	}
	defer resp.Body.Close()

	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to decode response: %w", err)
	}

	if errCode, ok := result["errcode"].(float64); ok && errCode != 0 {
		return fmt.Errorf("wechat work API error: %v", result["errmsg"])
	}

	w.logger.Info("WeChat Work notification sent successfully",
		zap.String("notification_id", notification.ID),
		zap.String("type", string(notification.Type)))

	return nil
}

func (w *WeChatWorkNotifier) buildContent(notification *Notification) string {
	var emoji string
	switch notification.Severity {
	case SeverityCritical:
		emoji = "🚨"
	case SeverityError:
		emoji = "❌"
	case SeverityWarning:
		emoji = "⚠️"
	default:
		emoji = "ℹ️"
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("> ## <font color=\"warning\">%s %s</font>\n\n", emoji, notification.Title))
	sb.WriteString(fmt.Sprintf("> **时间**: <font color=\"info\">%s</font>\n",
		notification.CreatedAt.Format("2006-01-02 15:04:05")))
	sb.WriteString(fmt.Sprintf("> **类型**: <font color=\"comment\">%s</font>\n", notification.Type))
	sb.WriteString(fmt.Sprintf("> **严重程度**: <font color=\"warning\">%s</font>\n\n", notification.Severity))

	if notification.ModelName != "" {
		sb.WriteString(fmt.Sprintf("> **模型**: <font color=\"info\">%s</font>\n", notification.ModelName))
	}
	if notification.Namespace != "" {
		sb.WriteString(fmt.Sprintf("> **命名空间**: <font color=\"info\">%s</font>\n\n", notification.Namespace))
	}

	sb.WriteString(fmt.Sprintf("> **描述**:\n\n> %s\n\n", notification.Message))

	if len(notification.Details) > 0 {
		sb.WriteString("> **详细信息**:\n\n")
		for k, v := range notification.Details {
			sb.WriteString(fmt.Sprintf("> - **%s**: `%v`\n", k, v))
		}
	}

	return sb.String()
}

type EmailNotifier struct {
	smtpHost string
	smtpPort int
	username string
	password string
	fromAddr string
	logger   *zap.Logger
}

func NewEmailNotifier(cfg config.EmailConfig, logger *zap.Logger) *EmailNotifier {
	return &EmailNotifier{
		smtpHost: cfg.SMTPHost,
		smtpPort: cfg.SMTPPort,
		username: cfg.Username,
		password: cfg.Password,
		fromAddr: cfg.FromAddr,
		logger:   logger,
	}
}

func (e *EmailNotifier) Name() string {
	return "email"
}

func (e *EmailNotifier) Send(ctx context.Context, notification *Notification) error {
	if e.smtpHost == "" {
		return fmt.Errorf("email SMTP not configured")
	}

	if len(notification.Recipients) == 0 {
		return fmt.Errorf("no email recipients specified")
	}

	e.logger.Info("Email notification (placeholder)",
		zap.String("notification_id", notification.ID),
		zap.Strings("recipients", notification.Recipients),
		zap.String("title", notification.Title))

	return nil
}

type NotificationManager struct {
	cfg      config.NotificationConfig
	notifiers []Notifier
	logger   *zap.Logger

	history   []*Notification
	historyMu sync.RWMutex
	historyMax int
}

func NewNotificationManager(cfg config.NotificationConfig, logger *zap.Logger) *NotificationManager {
	manager := &NotificationManager{
		cfg:        cfg,
		logger:     logger,
		notifiers:  make([]Notifier, 0),
		history:    make([]*Notification, 0),
		historyMax: 1000,
	}

	if cfg.DingTalk.Enabled && cfg.DingTalk.WebhookURL != "" {
		dt := NewDingTalkNotifier(cfg.DingTalk.WebhookURL, cfg.DingTalk.Secret, logger)
		manager.notifiers = append(manager.notifiers, dt)
	}

	if cfg.WeChatWork.Enabled && cfg.WeChatWork.WebhookURL != "" {
		wx := NewWeChatWorkNotifier(cfg.WeChatWork.WebhookURL, logger)
		manager.notifiers = append(manager.notifiers, wx)
	}

	if cfg.Email.Enabled {
		email := NewEmailNotifier(cfg.Email, logger)
		manager.notifiers = append(manager.notifiers, email)
	}

	return manager
}

func (nm *NotificationManager) Send(ctx context.Context, notification *Notification) error {
	if notification.ID == "" {
		notification.ID = fmt.Sprintf("notif-%d", time.Now().UnixNano())
	}
	if notification.CreatedAt.IsZero() {
		notification.CreatedAt = time.Now()
	}

	nm.addToHistory(notification)

	var firstErr error
	for _, notifier := range nm.notifiers {
		if err := notifier.Send(ctx, notification); err != nil {
			nm.logger.Error("Failed to send notification via notifier",
				zap.String("notifier", notifier.Name()),
				zap.String("notification_id", notification.ID),
				zap.Error(err))
			if firstErr == nil {
				firstErr = fmt.Errorf("%s: %w", notifier.Name(), err)
			}
		}
	}

	return firstErr
}

func (nm *NotificationManager) SendDriftAlert(ctx context.Context, modelName, modelID, namespace string,
	klDivergence, psiValue float64, threshold float64, ownerEmails []string) error {

	notification := &Notification{
		Type:       TypeDriftDetected,
		Severity:   SeverityWarning,
		Title:      fmt.Sprintf("概念漂移检测: 模型 %s 预测分布偏移", modelName),
		Message:    fmt.Sprintf("模型 %s 的预测分布与基线分布差异超过阈值 (%.3f)，建议重新训练模型。",
			modelName, threshold),
		Details: map[string]interface{}{
			"kl_divergence": fmt.Sprintf("%.4f", klDivergence),
			"psi_value":     fmt.Sprintf("%.4f", psiValue),
			"threshold":     fmt.Sprintf("%.4f", threshold),
			"model_id":      modelID,
		},
		ModelName:  modelName,
		ModelID:    modelID,
		Namespace:  namespace,
		Recipients: ownerEmails,
	}

	if klDivergence > threshold*2 {
		notification.Severity = SeverityCritical
	}

	return nm.Send(ctx, notification)
}

func (nm *NotificationManager) SendModelRegistered(ctx context.Context, modelName, modelID, namespace,
	version string, autoDeploy bool, ownerEmails []string) error {

	notification := &Notification{
		Type:       TypeModelRegistered,
		Severity:   SeverityInfo,
		Title:      fmt.Sprintf("模型注册成功: %s:%s", modelName, version),
		Message:    fmt.Sprintf("模型 %s 版本 %s 已成功注册到模型仓库。", modelName, version),
		Details: map[string]interface{}{
			"auto_deploy": autoDeploy,
			"version":     version,
		},
		ModelName:  modelName,
		ModelID:    modelID,
		Namespace:  namespace,
		Recipients: ownerEmails,
	}

	return nm.Send(ctx, notification)
}

func (nm *NotificationManager) SendABTestComplete(ctx context.Context, testName, testID, modelName,
	namespace, betterVersion string, pValue, effectSize float64, isSignificant bool,
	ownerEmails []string) error {

	severity := SeverityInfo
	if isSignificant {
		severity = SeverityWarning
	}

	notification := &Notification{
		Type:       TypeABTestComplete,
		Severity:   severity,
		Title:      fmt.Sprintf("A/B测试完成: %s", testName),
		Message:    fmt.Sprintf("A/B测试 %s 已完成分析，优胜版本: %s", testName, betterVersion),
		Details: map[string]interface{}{
			"test_id":       testID,
			"better_version": betterVersion,
			"p_value":       fmt.Sprintf("%.4f", pValue),
			"effect_size":   fmt.Sprintf("%.4f", effectSize),
			"is_significant": isSignificant,
		},
		ModelName:  modelName,
		Namespace:  namespace,
		Recipients: ownerEmails,
	}

	return nm.Send(ctx, notification)
}

func (nm *NotificationManager) addToHistory(notification *Notification) {
	nm.historyMu.Lock()
	defer nm.historyMu.Unlock()

	nm.history = append(nm.history, notification)
	if len(nm.history) > nm.historyMax {
		nm.history = nm.history[len(nm.history)-nm.historyMax:]
	}
}

func (nm *NotificationManager) GetHistory(limit int) []*Notification {
	nm.historyMu.RLock()
	defer nm.historyMu.RUnlock()

	result := make([]*Notification, 0, len(nm.history))
	start := 0
	if limit > 0 && len(nm.history) > limit {
		start = len(nm.history) - limit
	}
	result = append(result, nm.history[start:]...)
	return result
}

func (nm *NotificationManager) GetNotifiers() []string {
	names := make([]string, 0, len(nm.notifiers))
	for _, n := range nm.notifiers {
		names = append(names, n.Name())
	}
	return names
}
