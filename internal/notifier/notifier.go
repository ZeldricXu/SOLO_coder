package notifier

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type Channel interface {
	Send(ctx context.Context, notification *models.AlertNotification) error
	Name() string
	Type() string
	Enabled() bool
}

type BaseChannel struct {
	name    string
	typ     string
	enabled bool
	cfg     map[string]interface{}
	filter  config.NotificationFilter
}

func (b *BaseChannel) Name() string    { return b.name }
func (b *BaseChannel) Type() string    { return b.typ }
func (b *BaseChannel) Enabled() bool   { return b.enabled }

type DingTalkChannel struct {
	BaseChannel
	webhookURL string
	secret     string
	atMobiles  []string
	isAtAll    bool
}

type PagerDutyChannel struct {
	BaseChannel
	apiKey      string
	routingKey  string
	serviceID   string
	escalationPolicy string
}

type WebhookChannel struct {
	BaseChannel
	url     string
	method  string
	headers map[string]string
}

type Notifier struct {
	cfg      config.NotificationConfig
	channels []Channel
	input    <-chan *models.Incident
	wg       sync.WaitGroup
	stopCh   chan struct{}
	retry    config.RetryConfig
}

func NewNotifier(cfg config.NotificationConfig, input <-chan *models.Incident) (*Notifier, error) {
	if cfg.Retry.MaxRetries == 0 {
		cfg.Retry.MaxRetries = 3
	}
	if cfg.Retry.Backoff == 0 {
		cfg.Retry.Backoff = 1 * time.Second
	}
	if cfg.Retry.Multiplier == 0 {
		cfg.Retry.Multiplier = 2.0
	}

	notifier := &Notifier{
		cfg:    cfg,
		input:  input,
		stopCh: make(chan struct{}),
		retry:  cfg.Retry,
	}

	for _, chCfg := range cfg.Channels {
		if !chCfg.Enabled {
			continue
		}

		channel, err := notifier.createChannel(chCfg)
		if err != nil {
			log.Printf("Failed to create channel %s: %v", chCfg.Name, err)
			continue
		}
		notifier.channels = append(notifier.channels, channel)
	}

	return notifier, nil
}

func (n *Notifier) createChannel(cfg config.NotificationChannel) (Channel, error) {
	base := BaseChannel{
		name:    cfg.Name,
		typ:     cfg.Type,
		enabled: cfg.Enabled,
		cfg:     cfg.Config,
		filter:  cfg.Filter,
	}

	switch cfg.Type {
	case "dingtalk":
		return n.createDingTalkChannel(base, cfg)
	case "pagerduty":
		return n.createPagerDutyChannel(base, cfg)
	case "webhook":
		return n.createWebhookChannel(base, cfg)
	default:
		return nil, fmt.Errorf("unknown channel type: %s", cfg.Type)
	}
}

func (n *Notifier) createDingTalkChannel(base BaseChannel, cfg config.NotificationChannel) (*DingTalkChannel, error) {
	ch := &DingTalkChannel{
		BaseChannel: base,
	}

	if webhook, ok := cfg.Config["webhook_url"].(string); ok {
		ch.webhookURL = webhook
	} else {
		return nil, fmt.Errorf("dingtalk webhook_url required")
	}

	if secret, ok := cfg.Config["secret"].(string); ok {
		ch.secret = secret
	}

	if mobiles, ok := cfg.Config["at_mobiles"].([]interface{}); ok {
		ch.atMobiles = make([]string, len(mobiles))
		for i, m := range mobiles {
			ch.atMobiles[i] = fmt.Sprintf("%v", m)
		}
	}

	if atAll, ok := cfg.Config["is_at_all"].(bool); ok {
		ch.isAtAll = atAll
	}

	return ch, nil
}

func (n *Notifier) createPagerDutyChannel(base BaseChannel, cfg config.NotificationChannel) (*PagerDutyChannel, error) {
	ch := &PagerDutyChannel{
		BaseChannel: base,
	}

	if apiKey, ok := cfg.Config["api_key"].(string); ok {
		ch.apiKey = apiKey
	} else {
		return nil, fmt.Errorf("pagerduty api_key required")
	}

	if routingKey, ok := cfg.Config["routing_key"].(string); ok {
		ch.routingKey = routingKey
	}

	if serviceID, ok := cfg.Config["service_id"].(string); ok {
		ch.serviceID = serviceID
	}

	if escalationPolicy, ok := cfg.Config["escalation_policy"].(string); ok {
		ch.escalationPolicy = escalationPolicy
	}

	return ch, nil
}

func (n *Notifier) createWebhookChannel(base BaseChannel, cfg config.NotificationChannel) (*WebhookChannel, error) {
	ch := &WebhookChannel{
		BaseChannel: base,
		method:      http.MethodPost,
		headers:     make(map[string]string),
	}

	if url, ok := cfg.Config["url"].(string); ok {
		ch.url = url
	} else {
		return nil, fmt.Errorf("webhook url required")
	}

	if method, ok := cfg.Config["method"].(string); ok {
		ch.method = method
	}

	if headers, ok := cfg.Config["headers"].(map[string]interface{}); ok {
		for k, v := range headers {
			ch.headers[k] = fmt.Sprintf("%v", v)
		}
	}

	return ch, nil
}

func (n *Notifier) Start(ctx context.Context) error {
	if !n.cfg.Enabled {
		log.Printf("Notifier disabled")
		return nil
	}

	n.wg.Add(1)
	go n.processIncidents(ctx)

	log.Printf("Notifier started with %d channels", len(n.channels))
	return nil
}

func (n *Notifier) processIncidents(ctx context.Context) {
	defer n.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-n.stopCh:
			return
		case incident := <-n.input:
			if incident == nil {
				continue
			}
			n.sendNotification(ctx, incident)
		}
	}
}

func (n *Notifier) sendNotification(ctx context.Context, incident *models.Incident) {
	notification := n.buildNotification(incident)

	for _, ch := range n.channels {
		if !ch.Enabled() {
			continue
		}

		if !n.shouldSend(ch, incident) {
			continue
		}

		go func(channel Channel) {
			if err := n.sendWithRetry(ctx, channel, notification); err != nil {
				log.Printf("Failed to send notification via %s: %v", channel.Name(), err)
			}
		}(ch)
	}
}

func (n *Notifier) buildNotification(incident *models.Incident) *models.AlertNotification {
	summary := fmt.Sprintf("[%s] %s - %s", incident.Severity, incident.Title, strings.Join(incident.ServiceNames, ", "))
	if len(incident.Alerts) > 1 {
		summary += fmt.Sprintf(" (%d related alerts)", len(incident.Alerts))
	}

	queryURL := fmt.Sprintf("/api/logs?trace_id=%s&start=%d&end=%d",
		incident.RelatedTraceIDs,
		incident.StartTime.Unix(),
		time.Now().Unix(),
	)

	logViewerURL := fmt.Sprintf("/logs?trace_id=%s", incident.RelatedTraceIDs)

	return &models.AlertNotification{
		Incident:     incident,
		Summary:      summary,
		QueryURL:     queryURL,
		LogViewerURL: logViewerURL,
		GeneratedAt:  time.Now(),
	}
}

func (n *Notifier) shouldSend(ch Channel, incident *models.Incident) bool {
	baseCh, ok := ch.(interface{ GetFilter() config.NotificationFilter })
	if !ok {
		return true
	}
	filter := baseCh.GetFilter()

	if filter.MinSeverity != "" {
		if !n.isSeverityAllowed(string(incident.Severity), filter.MinSeverity) {
			return false
		}
	}

	if len(filter.Services) > 0 {
		matched := false
		for _, svc := range filter.Services {
			for _, incidentSvc := range incident.ServiceNames {
				if svc == incidentSvc {
					matched = true
					break
				}
			}
			if matched {
				break
			}
		}
		if !matched {
			return false
		}
	}

	if len(filter.AlertTypes) > 0 {
		matched := false
		for _, alertType := range filter.AlertTypes {
			for _, alert := range incident.Alerts {
				if string(alert.AlertType) == alertType {
					matched = true
					break
				}
			}
			if matched {
				break
			}
		}
		if !matched {
			return false
		}
	}

	return true
}

func (n *Notifier) isSeverityAllowed(actual, min string) bool {
	priority := map[string]int{
		"CRITICAL": 4,
		"HIGH":     3,
		"MEDIUM":   2,
		"LOW":      1,
	}
	return priority[actual] >= priority[min]
}

func (n *Notifier) sendWithRetry(ctx context.Context, ch Channel, notification *models.AlertNotification) error {
	var lastErr error
	backoff := n.retry.Backoff

	for attempt := 0; attempt <= n.retry.MaxRetries; attempt++ {
		if err := ch.Send(ctx, notification); err == nil {
			log.Printf("Notification sent successfully via %s (attempt %d)", ch.Name(), attempt+1)
			return nil
		} else {
			lastErr = err
			log.Printf("Notification attempt %d failed via %s: %v", attempt+1, ch.Name(), err)
		}

		if attempt < n.retry.MaxRetries {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(backoff):
				backoff = time.Duration(float64(backoff) * n.retry.Multiplier)
			}
		}
	}

	return fmt.Errorf("all retry attempts failed: %w", lastErr)
}

func (ch *DingTalkChannel) GetFilter() config.NotificationFilter {
	return ch.filter
}

func (ch *DingTalkChannel) Send(ctx context.Context, notification *models.AlertNotification) error {
	incident := notification.Incident

	at := map[string]interface{}{
		"atMobiles": ch.atMobiles,
		"isAtAll":   ch.isAtAll,
	}

	text := fmt.Sprintf(`## 【%s】%s

**严重级别**: %s
**业务影响**: %s
**告警时间**: %s
**涉及服务**: %s
**关联告警数**: %d
**相关错误码**: %s
**相关TraceID**: %s

**故障摘要**:
%s

**快速链接**:
- [日志查询](%s)
- [链路追踪](%s)

---
*由实时日志分析系统自动生成*
`,
		incident.Severity,
		incident.Title,
		incident.Severity,
		incident.BusinessImpact,
		incident.StartTime.Format("2006-01-02 15:04:05"),
		strings.Join(incident.ServiceNames, ", "),
		len(incident.Alerts),
		strings.Join(incident.RelatedErrorCodes, ", "),
		strings.Join(incident.RelatedTraceIDs, ", "),
		incident.Description,
		notification.QueryURL,
		notification.LogViewerURL,
	)

	msg := map[string]interface{}{
		"msgtype": "markdown",
		"markdown": map[string]interface{}{
			"title": fmt.Sprintf("[%s] %s", incident.Severity, incident.Title),
			"text":  text,
		},
		"at": at,
	}

	body, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, ch.webhookURL, bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("dingtalk returned %d: %s", resp.StatusCode, string(respBody))
	}

	var result map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil
	}

	if errcode, ok := result["errcode"].(float64); ok && errcode != 0 {
		return fmt.Errorf("dingtalk error: %v", result["errmsg"])
	}

	return nil
}

func (ch *PagerDutyChannel) GetFilter() config.NotificationFilter {
	return ch.filter
}

func (ch *PagerDutyChannel) Send(ctx context.Context, notification *models.AlertNotification) error {
	incident := notification.Incident

	severity := "critical"
	switch incident.Severity {
	case models.SeverityCritical:
		severity = "critical"
	case models.SeverityHigh:
		severity = "error"
	case models.SeverityMedium:
		severity = "warning"
	case models.SeverityLow:
		severity = "info"
	}

	payload := map[string]interface{}{
		"routing_key": ch.routingKey,
		"event_action": "trigger",
		"dedup_key":    incident.DeduplicationKey,
		"payload": map[string]interface{}{
			"summary":       notification.Summary,
			"severity":      severity,
			"source":        strings.Join(incident.ServiceNames, ","),
			"component":     strings.Join(incident.ServiceNames, ","),
			"group":         "log-analyzer",
			"class":         string(incident.Alerts[0].AlertType),
			"custom_details": incident,
		},
		"links": []map[string]interface{}{
			{
				"href": notification.QueryURL,
				"text": "View Logs",
			},
			{
				"href": notification.LogViewerURL,
				"text": "View Trace",
			},
		},
	}

	if incident.Acknowledged {
		payload["event_action"] = "acknowledge"
	}

	body, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	url := "https://events.pagerduty.com/v2/enqueue"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewBuffer(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Token token="+ch.apiKey)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("pagerduty returned %d: %s", resp.StatusCode, string(respBody))
	}

	return nil
}

func (ch *WebhookChannel) GetFilter() config.NotificationFilter {
	return ch.filter
}

func (ch *WebhookChannel) Send(ctx context.Context, notification *models.AlertNotification) error {
	body, err := json.Marshal(notification)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, ch.method, ch.url, bytes.NewBuffer(body))
	if err != nil {
		return err
	}

	for k, v := range ch.headers {
		req.Header.Set(k, v)
	}
	if req.Header.Get("Content-Type") == "" {
		req.Header.Set("Content-Type", "application/json")
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		respBody, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("webhook returned %d: %s", resp.StatusCode, string(respBody))
	}

	return nil
}

func (n *Notifier) Stop() {
	close(n.stopCh)
	n.wg.Wait()
}
