package notification

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/smtp"
	"strings"
	"sync"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"gorm.io/gorm"
)

type NotifyLevel string

const (
	LevelInfo     NotifyLevel = "info"
	LevelWarning  NotifyLevel = "warning"
	LevelError    NotifyLevel = "error"
	LevelCritical NotifyLevel = "critical"
)

type NotifyChannel string

const (
	ChannelEmail    NotifyChannel = "email"
	ChannelSMS      NotifyChannel = "sms"
	ChannelWebhook  NotifyChannel = "webhook"
	ChannelInApp    NotifyChannel = "inapp"
	ChannelSlack    NotifyChannel = "slack"
	ChannelDingTalk NotifyChannel = "dingtalk"
)

type NotifyStatus string

const (
	StatusPending   NotifyStatus = "pending"
	StatusSent      NotifyStatus = "sent"
	StatusFailed    NotifyStatus = "failed"
	StatusSuppressed NotifyStatus = "suppressed"
	StatusDelivered NotifyStatus = "delivered"
)

type SuppressionStrategy string

const (
	SuppressNone     SuppressionStrategy = "none"
	SuppressDedupe   SuppressionStrategy = "dedupe"
	SuppressThrottle SuppressionStrategy = "throttle"
	SuppressWindow   SuppressionStrategy = "window"
)

type Notification struct {
	models.BaseModel
	Title        string        `json:"title"`
	Content      string        `json:"content"`
	Level        NotifyLevel   `json:"level" gorm:"index;size:50"`
	Channel      NotifyChannel `json:"channel" gorm:"index;size:50"`
	Recipients   string        `json:"recipients"`
	TemplateID   string        `json:"template_id"`
	Params       string        `json:"params"`
	Status       NotifyStatus  `json:"status" gorm:"index;size:50"`
	RetryCount   int           `json:"retry_count"`
	MaxRetry     int           `json:"max_retry"`
	SentAt       *time.Time    `json:"sent_at"`
	DeliveredAt  *time.Time    `json:"delivered_at"`
	Error        string        `json:"error"`
	Source       string        `json:"source" gorm:"index;size:100"`
	SourceID     string        `json:"source_id"`
	DedupeKey    string        `json:"dedupe_key" gorm:"index;size:255"`
	SuppressStrategy SuppressionStrategy `json:"suppress_strategy" gorm:"size:50"`
	SuppressUntil *time.Time   `json:"suppress_until"`
	Priority     int           `json:"priority" gorm:"index"`
	ReadAt       *time.Time    `json:"read_at"`
	ReadBy       string        `json:"read_by"`
}

type NotifyTemplate struct {
	models.BaseModel
	Name        string `json:"name" gorm:"index;size:100"`
	Code        string `json:"code" gorm:"uniqueIndex;size:100"`
	TitleTmpl   string `json:"title_tmpl"`
	ContentTmpl string `json:"content_tmpl"`
	Channel     NotifyChannel `json:"channel" gorm:"size:50"`
	Level       NotifyLevel   `json:"level" gorm:"size:50"`
	DefaultRecipients string `json:"default_recipients"`
	Enabled     bool   `json:"enabled" gorm:"index"`
	Description string `json:"description"`
}

type NotifyConfig struct {
	SMTPHost     string
	SMTPPort     int
	SMTPUser     string
	SMTPPassword string
	SMTPFrom     string
	WebhookURL   string
	SlackWebhook string
	DingTalkWebhook string
	DefaultLevel NotifyLevel
	DefaultChannel NotifyChannel
	EnableThrottling bool
	ThrottleLimit  int
	ThrottlePeriod time.Duration
	EnableDeduplication bool
	DedupeWindow     time.Duration
}

type SuppressionRule struct {
	ID        string
	Pattern   string
	Strategy  SuppressionStrategy
	Threshold int
	Window    time.Duration
	Channels  []NotifyChannel
	Levels    []NotifyLevel
	Enabled   bool
}

type ChannelSender interface {
	Name() NotifyChannel
	Send(ctx context.Context, notification *Notification, config *NotifyConfig) error
}

type EmailSender struct{}

func (s *EmailSender) Name() NotifyChannel { return ChannelEmail }

func (s *EmailSender) Send(ctx context.Context, notif *Notification, config *NotifyConfig) error {
	if config.SMTPHost == "" {
		return fmt.Errorf("SMTP host not configured")
	}

	recipients := strings.Split(notif.Recipients, ",")
	for i, r := range recipients {
		recipients[i] = strings.TrimSpace(r)
	}

	if len(recipients) == 0 {
		return fmt.Errorf("no recipients specified")
	}

	auth := smtp.PlainAuth("", config.SMTPUser, config.SMTPPassword, config.SMTPHost)
	addr := fmt.Sprintf("%s:%d", config.SMTPHost, config.SMTPPort)

	msg := fmt.Sprintf("From: %s\r\nTo: %s\r\nSubject: %s\r\n\r\n%s",
		config.SMTPFrom,
		strings.Join(recipients, ", "),
		notif.Title,
		notif.Content,
	)

	logger.Info("[Email] Sending notification to %s: %s", recipients, notif.Title)
	return smtp.SendMail(addr, auth, config.SMTPFrom, recipients, []byte(msg))
}

type WebhookSender struct{}

func (s *WebhookSender) Name() NotifyChannel { return ChannelWebhook }

func (s *WebhookSender) Send(ctx context.Context, notif *Notification, config *NotifyConfig) error {
	if config.WebhookURL == "" {
		return fmt.Errorf("webhook URL not configured")
	}

	payload := map[string]interface{}{
		"title":    notif.Title,
		"content":  notif.Content,
		"level":    notif.Level,
		"source":   notif.Source,
		"source_id": notif.SourceID,
		"timestamp": time.Now(),
	}

	body, _ := json.Marshal(payload)
	resp, err := http.Post(config.WebhookURL, "application/json", strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return fmt.Errorf("webhook returned status: %d", resp.StatusCode)
	}

	logger.Info("[Webhook] Notification sent: %s", notif.Title)
	return nil
}

type SlackSender struct{}

func (s *SlackSender) Name() NotifyChannel { return ChannelSlack }

func (s *SlackSender) Send(ctx context.Context, notif *Notification, config *NotifyConfig) error {
	if config.SlackWebhook == "" {
		return fmt.Errorf("slack webhook not configured")
	}

	emoji := ":information_source:"
	switch notif.Level {
	case LevelWarning:
		emoji = ":warning:"
	case LevelError:
		emoji = ":error:"
	case LevelCritical:
		emoji = ":fire:"
	}

	payload := map[string]interface{}{
		"text": fmt.Sprintf("%s *%s*\n\n%s", emoji, notif.Title, notif.Content),
	}

	body, _ := json.Marshal(payload)
	resp, err := http.Post(config.SlackWebhook, "application/json", strings.NewReader(string(body)))
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	logger.Info("[Slack] Notification sent: %s", notif.Title)
	return nil
}

type InAppSender struct{}

func (s *InAppSender) Name() NotifyChannel { return ChannelInApp }

func (s *InAppSender) Send(ctx context.Context, notif *Notification, config *NotifyConfig) error {
	logger.Info("[InApp] Notification delivered: %s", notif.Title)
	notif.Status = StatusDelivered
	return nil
}

type NotificationManager struct {
	mu          sync.RWMutex
	db          *dao.DAO
	config      NotifyConfig
	senders     map[NotifyChannel]ChannelSender
	queue       chan *Notification
	stopChan    chan struct{}
	workerCount int
	running     bool
	suppressionRules []SuppressionRule
	dedupeCache map[string]time.Time
}

func NewNotificationManager(db *dao.DAO, config NotifyConfig) *NotificationManager {
	if config.DefaultLevel == "" {
		config.DefaultLevel = LevelInfo
	}
	if config.DefaultChannel == "" {
		config.DefaultChannel = ChannelInApp
	}
	if config.ThrottleLimit <= 0 {
		config.ThrottleLimit = 100
	}
	if config.ThrottlePeriod <= 0 {
		config.ThrottlePeriod = time.Minute
	}
	if config.DedupeWindow <= 0 {
		config.DedupeWindow = 5 * time.Minute
	}

	nm := &NotificationManager{
		db:          db,
		config:      config,
		senders:     make(map[NotifyChannel]ChannelSender),
		queue:       make(chan *Notification, 1000),
		stopChan:    make(chan struct{}),
		workerCount: 3,
		dedupeCache: make(map[string]time.Time),
	}

	nm.registerSender(&EmailSender{})
	nm.registerSender(&WebhookSender{})
	nm.registerSender(&SlackSender{})
	nm.registerSender(&InAppSender{})

	nm.initDefaultSuppressionRules()
	db.AutoMigrate(&Notification{}, &NotifyTemplate{})
	nm.loadDefaultTemplates()

	nm.running = true
	for i := 0; i < nm.workerCount; i++ {
		go nm.worker()
	}
	go nm.cleanupDedupeCache()

	logger.Info("Notification manager initialized with %d workers", nm.workerCount)
	return nm
}

func (nm *NotificationManager) registerSender(sender ChannelSender) {
	nm.senders[sender.Name()] = sender
}

func (nm *NotificationManager) initDefaultSuppressionRules() {
	nm.suppressionRules = []SuppressionRule{
		{
			ID:       "dedupe-critical",
			Pattern:  "*",
			Strategy: SuppressDedupe,
			Window:   5 * time.Minute,
			Levels:   []NotifyLevel{LevelCritical, LevelError},
			Enabled:  true,
		},
		{
			ID:        "throttle-info",
			Pattern:   "*",
			Strategy:  SuppressThrottle,
			Threshold: 10,
			Window:    time.Minute,
			Levels:    []NotifyLevel{LevelInfo, LevelWarning},
			Enabled:   true,
		},
	}
}

func (nm *NotificationManager) loadDefaultTemplates() {
	templates := []*NotifyTemplate{
		{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "环境创建成功",
			Code:      "env_created",
			TitleTmpl: "环境 {{.env_name}} 创建成功",
			ContentTmpl: "您申请的环境 {{.env_name}} 已成功创建，访问地址：{{.url}}",
			Channel:   ChannelInApp,
			Level:     LevelInfo,
			Enabled:   true,
		},
		{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "环境即将过期",
			Code:      "env_expiring",
			TitleTmpl: "环境 {{.env_name}} 即将过期",
			ContentTmpl: "您的环境 {{.env_name}} 将于 {{.expire_time}} 过期，请及时延长使用时间或保存数据。",
			Channel:   ChannelEmail,
			Level:     LevelWarning,
			Enabled:   true,
		},
		{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "告警触发",
			Code:      "alert_triggered",
			TitleTmpl: "告警触发: {{.alert_name}}",
			ContentTmpl: "告警规则 {{.alert_name}} 已触发，当前值: {{.current_value}}，阈值: {{.threshold}}",
			Channel:   ChannelSlack,
			Level:     LevelCritical,
			Enabled:   true,
		},
		{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "漏洞发现",
			Code:      "vulnerability_found",
			TitleTmpl: "发现 {{.count}} 个新漏洞",
			ContentTmpl: "在项目 {{.project}} 中发现 {{.count}} 个新的安全漏洞，其中严重级别: {{.critical_count}}，高危: {{.high_count}}",
			Channel:   ChannelEmail,
			Level:     LevelError,
			Enabled:   true,
		},
		{
			BaseModel: models.BaseModel{ID: utils.GenerateUUID()},
			Name:      "任务执行失败",
			Code:      "task_failed",
			TitleTmpl: "任务执行失败: {{.task_name}}",
			ContentTmpl: "任务 {{.task_name}} 执行失败，错误信息: {{.error}}。已重试 {{.retry}} 次。",
			Channel:   ChannelInApp,
			Level:     LevelError,
			Enabled:   true,
		},
	}

	for _, tpl := range templates {
		var existing NotifyTemplate
		result := nm.db.DB().Where("code = ?", tpl.Code).First(&existing)
		if result.Error == gorm.ErrRecordNotFound {
			nm.db.DB().Create(tpl)
		}
	}
}

func (nm *NotificationManager) Send(ctx context.Context, notif *Notification) error {
	if notif.Title == "" {
		return fmt.Errorf("%w: notification title required", common.ErrInvalidInput)
	}
	if notif.Recipients == "" {
		return fmt.Errorf("%w: recipients required", common.ErrInvalidInput)
	}

	notif.ID = utils.GenerateUUID()
	if notif.Level == "" {
		notif.Level = nm.config.DefaultLevel
	}
	if notif.Channel == "" {
		notif.Channel = nm.config.DefaultChannel
	}
	if notif.MaxRetry == 0 {
		notif.MaxRetry = 3
	}
	if notif.Priority == 0 {
		notif.Priority = getPriorityByLevel(notif.Level)
	}
	if notif.Status == "" {
		notif.Status = StatusPending
	}
	if notif.SuppressStrategy == "" {
		notif.SuppressStrategy = SuppressDedupe
	}

	if notif.DedupeKey == "" {
		notif.DedupeKey = utils.MD5(notif.Title + notif.Content + notif.Recipients)
	}

	if nm.shouldSuppress(notif) {
		notif.Status = StatusSuppressed
		nm.db.DB().Create(notif)
		logger.Info("Notification suppressed: %s (key: %s)", notif.Title, notif.DedupeKey)
		return nil
	}

	if err := nm.db.DB().Create(notif).Error; err != nil {
		return err
	}

	select {
	case nm.queue <- notif:
		logger.Debug("Notification queued: %s", notif.Title)
	default:
		logger.Warn("Notification queue is full, processing synchronously")
		go nm.processNotification(notif)
	}

	return nil
}

func (nm *NotificationManager) SendWithTemplate(ctx context.Context, templateCode string, params map[string]interface{}, recipients []string) (*Notification, error) {
	tpl, err := nm.GetTemplate(templateCode)
	if err != nil {
		return nil, err
	}

	title, err := renderTemplate(tpl.TitleTmpl, params)
	if err != nil {
		return nil, fmt.Errorf("failed to render title: %w", err)
	}

	content, err := renderTemplate(tpl.ContentTmpl, params)
	if err != nil {
		return nil, fmt.Errorf("failed to render content: %w", err)
	}

	recipientStr := strings.Join(recipients, ",")
	if recipientStr == "" {
		recipientStr = tpl.DefaultRecipients
	}

	paramsJSON, _ := json.Marshal(params)

	notif := &Notification{
		Title:        title,
		Content:      content,
		Level:        tpl.Level,
		Channel:      tpl.Channel,
		Recipients:   recipientStr,
		TemplateID:   tpl.ID,
		Params:       string(paramsJSON),
		Status:       StatusPending,
		Source:       "template:" + templateCode,
		DedupeKey:    utils.MD5(templateCode + string(paramsJSON) + recipientStr),
	}

	if err := nm.Send(ctx, notif); err != nil {
		return nil, err
	}

	return notif, nil
}

func (nm *NotificationManager) worker() {
	for {
		select {
		case <-nm.stopChan:
			return
		case notif := <-nm.queue:
			nm.processNotification(notif)
		}
	}
}

func (nm *NotificationManager) processNotification(notif *Notification) {
	ctx := context.Background()

	sender, exists := nm.senders[notif.Channel]
	if !exists {
		notif.Status = StatusFailed
		notif.Error = fmt.Sprintf("no sender for channel: %s", notif.Channel)
		nm.db.DB().Save(notif)
		logger.Error(notif.Error)
		return
	}

	notif.Status = StatusPending
	nm.db.DB().Save(notif)

	err := sender.Send(ctx, notif, &nm.config)
	if err != nil {
		notif.RetryCount++
		notif.Error = err.Error()

		if notif.RetryCount < notif.MaxRetry {
			logger.Warn("Notification send failed (retry %d/%d): %v", notif.RetryCount, notif.MaxRetry, err)
			notif.Status = StatusPending
			go func() {
				time.Sleep(time.Duration(notif.RetryCount*2) * time.Second)
				nm.processNotification(notif)
			}()
			return
		}

		notif.Status = StatusFailed
		logger.Error("Notification send failed after %d retries: %s: %v", notif.MaxRetry, notif.Title, err)
	} else {
		now := time.Now()
		notif.Status = StatusSent
		notif.SentAt = &now
		logger.Info("Notification sent successfully: %s (channel: %s)", notif.Title, notif.Channel)
	}

	nm.db.DB().Save(notif)
	nm.trackDedupe(notif)
}

func (nm *NotificationManager) shouldSuppress(notif *Notification) bool {
	if !nm.config.EnableDeduplication {
		return false
	}

	if notif.SuppressUntil != nil && time.Now().Before(*notif.SuppressUntil) {
		return true
	}

	for _, rule := range nm.suppressionRules {
		if !rule.Enabled {
			continue
		}

		levelStrs := make([]string, len(rule.Levels))
		for i, l := range rule.Levels {
			levelStrs[i] = string(l)
		}
		if len(rule.Levels) > 0 && !utils.ContainsString(levelStrs, string(notif.Level)) {
			continue
		}

		switch rule.Strategy {
		case SuppressDedupe:
			if lastSent, exists := nm.dedupeCache[notif.DedupeKey]; exists {
				if time.Since(lastSent) < rule.Window {
					return true
				}
			}

			var recentCount int64
			since := time.Now().Add(-rule.Window)
			nm.db.DB().Model(&Notification{}).
				Where("dedupe_key = ? AND status != ? AND created_at >= ?",
					notif.DedupeKey, StatusSuppressed, since).
				Count(&recentCount)
			if recentCount > 0 {
				return true
			}

		case SuppressThrottle:
			var count int64
			since := time.Now().Add(-rule.Window)
			nm.db.DB().Model(&Notification{}).
				Where("level = ? AND status != ? AND created_at >= ?",
					notif.Level, StatusSuppressed, since).
				Count(&count)
			if count >= int64(rule.Threshold) {
				return true
			}

		case SuppressWindow:
			var count int64
			nm.db.DB().Model(&Notification{}).
				Where("dedupe_key = ? AND status != ?", notif.DedupeKey, StatusSuppressed).
				Count(&count)
			if count >= int64(rule.Threshold) {
				return true
			}
		}
	}

	return false
}

func (nm *NotificationManager) trackDedupe(notif *Notification) {
	nm.mu.Lock()
	defer nm.mu.Unlock()
	nm.dedupeCache[notif.DedupeKey] = time.Now()
}

func (nm *NotificationManager) cleanupDedupeCache() {
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()

	for {
		select {
		case <-nm.stopChan:
			return
		case <-ticker.C:
			nm.mu.Lock()
			cutoff := time.Now().Add(-24 * time.Hour)
			for k, v := range nm.dedupeCache {
				if v.Before(cutoff) {
					delete(nm.dedupeCache, k)
				}
			}
			nm.mu.Unlock()
		}
	}
}

func (nm *NotificationManager) Get(id string) (*Notification, error) {
	var notif Notification
	if err := nm.db.DB().First(&notif, "id = ?", id).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}
	return &notif, nil
}

func (nm *NotificationManager) List(page, pageSize int, level, status, channel, recipient string, unreadOnly bool) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var notifs []Notification
	var total int64

	query := nm.db.DB().Model(&Notification{})
	if level != "" {
		query = query.Where("level = ?", level)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if channel != "" {
		query = query.Where("channel = ?", channel)
	}
	if recipient != "" {
		query = query.Where("recipients LIKE ?", "%"+recipient+"%")
	}
	if unreadOnly {
		query = query.Where("read_at IS NULL")
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("priority DESC, created_at DESC").Find(&notifs).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    notifs,
	}, nil
}

func (nm *NotificationManager) MarkAsRead(id string, userID string) error {
	now := time.Now()
	result := nm.db.DB().Model(&Notification{}).
		Where("id = ? AND read_at IS NULL", id).
		Updates(map[string]interface{}{
			"read_at": now,
			"read_by": userID,
		})

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return common.ErrNotFound
	}
	return nil
}

func (nm *NotificationManager) MarkAllAsRead(recipient string) (int64, error) {
	now := time.Now()
	result := nm.db.DB().Model(&Notification{}).
		Where("recipients LIKE ? AND read_at IS NULL", "%"+recipient+"%").
		Updates(map[string]interface{}{
			"read_at": now,
			"read_by": recipient,
		})

	return result.RowsAffected, result.Error
}

func (nm *NotificationManager) GetTemplate(code string) (*NotifyTemplate, error) {
	var tpl NotifyTemplate
	if err := nm.db.DB().Where("code = ? AND enabled = ?", code, true).First(&tpl).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, common.ErrNotFound
		}
		return nil, err
	}
	return &tpl, nil
}

func (nm *NotificationManager) ListTemplates(page, pageSize int, channel NotifyChannel, enabledOnly bool) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var templates []NotifyTemplate
	var total int64

	query := nm.db.DB().Model(&NotifyTemplate{})
	if channel != "" {
		query = query.Where("channel = ?", channel)
	}
	if enabledOnly {
		query = query.Where("enabled = ?", true)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("name ASC").Find(&templates).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    templates,
	}, nil
}

func (nm *NotificationManager) AddSuppressionRule(rule SuppressionRule) {
	nm.mu.Lock()
	defer nm.mu.Unlock()
	nm.suppressionRules = append(nm.suppressionRules, rule)
}

func (nm *NotificationManager) GetSuppressionRules() []SuppressionRule {
	nm.mu.RLock()
	defer nm.mu.RUnlock()
	return nm.suppressionRules
}

func (nm *NotificationManager) GetStats() map[string]interface{} {
	var totalSent, totalFailed, totalSuppressed, pending int64

	nm.db.DB().Model(&Notification{}).Where("status = ?", StatusSent).Count(&totalSent)
	nm.db.DB().Model(&Notification{}).Where("status = ?", StatusFailed).Count(&totalFailed)
	nm.db.DB().Model(&Notification{}).Where("status = ?", StatusSuppressed).Count(&totalSuppressed)
	nm.db.DB().Model(&Notification{}).Where("status = ?", StatusPending).Count(&pending)

	byLevel := make(map[string]int64)
	levels := []NotifyLevel{LevelInfo, LevelWarning, LevelError, LevelCritical}
	for _, level := range levels {
		var count int64
		nm.db.DB().Model(&Notification{}).Where("level = ?", level).Count(&count)
		byLevel[string(level)] = count
	}

	byChannel := make(map[string]int64)
	rows, _ := nm.db.DB().Model(&Notification{}).Select("channel, COUNT(*)").Group("channel").Rows()
	for rows.Next() {
		var ch string
		var count int64
		rows.Scan(&ch, &count)
		byChannel[ch] = count
	}
	rows.Close()

	successRate := float64(0)
	if totalSent+totalFailed > 0 {
		successRate = float64(totalSent) / float64(totalSent+totalFailed) * 100
	}

	return map[string]interface{}{
		"total_sent":      totalSent,
		"total_failed":    totalFailed,
		"total_suppressed": totalSuppressed,
		"pending":         pending,
		"success_rate":    successRate,
		"by_level":        byLevel,
		"by_channel":      byChannel,
		"queue_size":      len(nm.queue),
		"dedupe_cache_size": len(nm.dedupeCache),
	}
}

func (nm *NotificationManager) Stop() {
	close(nm.stopChan)
	nm.running = false
	logger.Info("Notification manager stopped")
}

func getPriorityByLevel(level NotifyLevel) int {
	switch level {
	case LevelCritical:
		return 100
	case LevelError:
		return 75
	case LevelWarning:
		return 50
	case LevelInfo:
		return 25
	default:
		return 10
	}
}

func renderTemplate(tmplStr string, data interface{}) (string, error) {
	if data == nil {
		return tmplStr, nil
	}

	result := tmplStr
	dataMap, ok := data.(map[string]interface{})
	if !ok {
		return tmplStr, nil
	}

	for k, v := range dataMap {
		placeholder := fmt.Sprintf("{{.%s}}", k)
		result = strings.ReplaceAll(result, placeholder, fmt.Sprintf("%v", v))
	}

	return result, nil
}

func normalizePagination(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}
