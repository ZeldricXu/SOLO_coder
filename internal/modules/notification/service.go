package notification

import (
	"bytes"
	"context"
	"fmt"
	"html/template"
	"sync"
	"time"

	"notificationplatform/config"
	"notificationplatform/internal/common/database"
	"notificationplatform/internal/common/errors"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"
	"notificationplatform/internal/modules/notification/channels"
	"notificationplatform/internal/modules/notification/queue"
	"notificationplatform/internal/modules/notification/router"
	"notificationplatform/internal/modules/notification/suppression"
	"notificationplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type SendRequest struct {
	Type        string            `json:"type" binding:"required"`
	Title       string            `json:"title" binding:"required"`
	Content     string            `json:"content" binding:"required"`
	Channel     string            `json:"channel" binding:"required"`
	Recipient   string            `json:"recipient" binding:"required"`
	Priority    int               `json:"priority"`
	DedupKey    string            `json:"dedup_key"`
	TemplateID  string            `json:"template_id"`
	Variables   map[string]string `json:"variables"`
	MaxRetries  int               `json:"max_retries"`
	TraceID     string            `json:"trace_id"`
	Metadata    map[string]string `json:"metadata"`
}

type TemplateRequest struct {
	Name            string            `json:"name" binding:"required"`
	Type            string            `json:"type" binding:"required"`
	Channel         string            `json:"channel" binding:"required"`
	TitleTmpl       string            `json:"title_tmpl" binding:"required"`
	ContentTmpl     string            `json:"content_tmpl" binding:"required"`
	DefaultPriority int               `json:"default_priority"`
	Variables       map[string]string `json:"variables"`
	Enabled         bool              `json:"enabled"`
}

type Service struct {
	db               *gorm.DB
	channels         map[models.NotificationChannel]channels.Channel
	queue            *queue.QueueManager
	suppressionMgr   *suppression.Manager
	routerMgr        *router.Manager
	stopChan         chan struct{}
	running          bool
	workers          int
	retryInterval    time.Duration
	mu               sync.RWMutex
	wg               sync.WaitGroup
	templates        map[string]*models.NotificationTemplate
	templatesMu      sync.RWMutex
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			db:             database.GetDB(),
			channels:       make(map[models.NotificationChannel]channels.Channel),
			queue:          queue.NewQueueManager(config.DefaultQueueSize),
			suppressionMgr: suppression.NewManager(),
			routerMgr:      router.NewManager(),
			stopChan:       make(chan struct{}),
			workers:        config.DefaultWorkerCount,
			retryInterval:  config.DefaultRetryInterval,
			templates:      make(map[string]*models.NotificationTemplate),
		}

		instance.RegisterChannel(channels.NewEmailChannel())
		instance.RegisterChannel(channels.NewSMSChannel())
		instance.RegisterChannel(channels.NewWebhookChannel())
		instance.RegisterChannel(channels.NewDingtalkChannel())
		instance.RegisterChannel(channels.NewWechatChannel())

		instance.loadTemplates()
		instance.initDefaultTemplates()
	})
	return instance
}

func (s *Service) RegisterChannel(ch channels.Channel) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.channels[ch.Name()] = ch
}

func (s *Service) loadTemplates() {
	if s.db == nil {
		return
	}

	var templates []models.NotificationTemplate
	if err := s.db.Where("enabled = ?", true).Find(&templates).Error; err != nil {
		logger.Get().Warn("failed to load templates from DB", zap.Error(err))
		return
	}

	s.templatesMu.Lock()
	defer s.templatesMu.Unlock()

	for i := range templates {
		s.templates[templates[i].ID] = &templates[i]
	}
}

func (s *Service) initDefaultTemplates() {
	s.templatesMu.Lock()
	defer s.templatesMu.Unlock()

	if len(s.templates) > 0 {
		return
	}

	defaultTemplates := []*models.NotificationTemplate{
		{
			ID:              "tmpl_alert_high",
			Name:            "High Priority Alert",
			Type:            "alert",
			Channel:         string(models.ChannelDingtalk),
			TitleTmpl:       "[ALERT] {{.Level}}: {{.Title}}",
			ContentTmpl:     "**Alert Details:**\n- Level: {{.Level}}\n- Service: {{.Service}}\n- Time: {{.Time}}\n- Message: {{.Message}}\n\nPlease investigate immediately.",
			DefaultPriority: int(models.PriorityHigh),
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
		{
			ID:              "tmpl_system_info",
			Name:            "System Info",
			Type:            "info",
			Channel:         string(models.ChannelEmail),
			TitleTmpl:       "[INFO] {{.Title}}",
			ContentTmpl:     "Dear User,\n\n{{.Message}}\n\nBest regards,\nSystem Team",
			DefaultPriority: int(models.PriorityNormal),
			Enabled:         true,
			CreatedAt:       time.Now(),
			UpdatedAt:       time.Now(),
		},
	}

	for _, tmpl := range defaultTemplates {
		s.templates[tmpl.ID] = tmpl
	}
}

func (s *Service) Send(ctx context.Context, req *SendRequest) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)

	if req.TraceID == "" {
		req.TraceID = utils.NewTraceID()
	}

	ctx = logger.ToContext(ctx, log.With(zap.String("trace_id", req.TraceID)))

	if err := s.validateRequest(req); err != nil {
		return nil, err
	}

	priority := req.Priority
	if priority == 0 {
		priority = int(models.PriorityNormal)
	}

	title, content, err := s.applyTemplate(req)
	if err != nil {
		log.Warn("failed to apply template, using raw content", zap.Error(err))
		title = req.Title
		content = req.Content
	}

	dedupKey := req.DedupKey
	if dedupKey == "" {
		dedupKey = utils.GenerateDedupKey(req.Type, req.Channel, req.Recipient, content)
	}

	maxRetries := req.MaxRetries
	if maxRetries <= 0 {
		maxRetries = config.DefaultMaxRetries
	}

	now := time.Now()
	baseRecord := &models.NotificationRecord{
		ID:         utils.NewID("notif"),
		Type:       req.Type,
		Title:      title,
		Content:    content,
		Channel:    req.Channel,
		Recipient:  req.Recipient,
		Priority:   priority,
		Status:     string(models.StatusPending),
		DedupKey:   dedupKey,
		MaxRetries: maxRetries,
		RetryCount: 0,
		TraceID:    req.TraceID,
		Metadata:   req.Metadata,
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	routingResult := s.routerMgr.Evaluate(ctx, baseRecord)
	if routingResult.Matched {
		log.Info("routing rule matched",
			zap.String("route_id", routingResult.RouteID),
			zap.String("route_name", routingResult.RouteName),
			zap.String("strategy", routingResult.Strategy),
			zap.Int("target_count", len(routingResult.Targets)),
		)

		if len(routingResult.Targets) > 0 {
			return s.sendWithRouting(ctx, baseRecord, routingResult)
		}
	}

	return s.sendSingle(ctx, baseRecord)
}

func (s *Service) sendWithRouting(ctx context.Context, baseRecord *models.NotificationRecord, routingResult *models.RoutingResult) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)
	strategy := models.DistributionStrategy(routingResult.Strategy)

	switch strategy {
	case models.StrategyMultiAll:
		return s.sendMultiAll(ctx, baseRecord, routingResult.Targets)
	case models.StrategyFailover:
		return s.sendFailover(ctx, baseRecord, routingResult.Targets)
	case models.StrategyMultiAny, models.StrategyLoadBalance, models.StrategyWeighted, models.StrategySingle:
		if len(routingResult.Targets) > 0 {
			target := routingResult.Targets[0]
			record := s.createRecordForTarget(baseRecord, target)
			return s.sendSingle(ctx, record)
		}
	default:
		if len(routingResult.Targets) > 0 {
			target := routingResult.Targets[0]
			record := s.createRecordForTarget(baseRecord, target)
			return s.sendSingle(ctx, record)
		}
	}

	log.Warn("routing strategy could not determine target, using default channel")
	baseRecord.Channel = routingResult.DefaultChannel
	return s.sendSingle(ctx, baseRecord)
}

func (s *Service) createRecordForTarget(baseRecord *models.NotificationRecord, target models.RouteTarget) *models.NotificationRecord {
	record := *baseRecord
	record.ID = utils.NewID("notif")
	record.Channel = target.Channel
	if target.Priority > 0 {
		record.Priority = target.Priority
	}
	record.Metadata = make(map[string]string)
	for k, v := range baseRecord.Metadata {
		record.Metadata[k] = v
	}
	record.Metadata["route_weight"] = fmt.Sprintf("%d", target.Weight)
	record.CreatedAt = time.Now()
	record.UpdatedAt = time.Now()
	return &record
}

func (s *Service) sendMultiAll(ctx context.Context, baseRecord *models.NotificationRecord, targets []models.RouteTarget) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)
	var firstRecord *models.NotificationRecord
	var lastErr error

	for _, target := range targets {
		record := s.createRecordForTarget(baseRecord, target)
		result, err := s.sendSingle(ctx, record)
		if err != nil {
			lastErr = err
			log.Warn("failed to send to one of multi-all targets",
				zap.String("channel", target.Channel),
				zap.Error(err),
			)
			continue
		}
		if firstRecord == nil {
			firstRecord = result
		}
	}

	if firstRecord != nil {
		return firstRecord, nil
	}
	return nil, lastErr
}

func (s *Service) sendFailover(ctx context.Context, baseRecord *models.NotificationRecord, targets []models.RouteTarget) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)

	for i, target := range targets {
		record := s.createRecordForTarget(baseRecord, target)
		result, err := s.sendSingle(ctx, record)
		if err == nil {
			if i > 0 {
				log.Info("failover succeeded on secondary channel",
					zap.String("primary_channel", targets[0].Channel),
					zap.String("used_channel", target.Channel),
					zap.Int("failover_index", i),
				)
			}
			return result, nil
		}
		log.Warn("failover channel failed, trying next",
			zap.String("channel", target.Channel),
			zap.Int("failover_index", i),
			zap.Error(err),
		)
	}

	return nil, errors.NewInternal("all failover channels failed", "")
}

func (s *Service) sendSingle(ctx context.Context, record *models.NotificationRecord) (*models.NotificationRecord, error) {
	log := logger.FromContext(ctx)

	checkResult, err := s.suppressionMgr.Check(ctx, record)
	if err != nil {
		log.Warn("suppression check failed, proceeding anyway", zap.Error(err))
	}

	if checkResult != nil && checkResult.Suppressed {
		record.Status = string(models.StatusSuppressed)
		record.SuppressionType = checkResult.SuppressionType
		record.SuppressionReason = checkResult.Reason
		record.UpdatedAt = time.Now()

		if s.db != nil {
			s.db.Create(record)
		}

		log.Info("notification suppressed",
			zap.String("id", record.ID),
			zap.String("suppression_type", checkResult.SuppressionType),
			zap.String("reason", checkResult.Reason),
		)

		return record, nil
	}

	record.Status = string(models.StatusQueued)
	record.UpdatedAt = time.Now()

	if s.db != nil {
		if err := s.db.Create(record).Error; err != nil {
			log.Error("failed to create notification record", zap.Error(err))
			return nil, errors.NewInternal("failed to create notification", err.Error())
		}
	}

	if !s.queue.Enqueue(record) {
		record.Status = string(models.StatusFailed)
		errMsg := "notification queue is full"
		record.ErrorMsg = &errMsg
		record.FailedAt = utils.TimePtr(time.Now())

		if s.db != nil {
			s.db.Save(record)
		}

		log.Error("notification queue is full, dropping notification", zap.String("id", record.ID))
		return nil, errors.NewUnavailable("notification queue is full", "")
	}

	log.Info("notification queued",
		zap.String("id", record.ID),
		zap.String("channel", record.Channel),
		zap.Int("priority", record.Priority),
	)

	return record, nil
}

func (s *Service) validateRequest(req *SendRequest) error {
	if req.Priority < 0 || req.Priority > 5 {
		if req.Priority != 0 {
			return errors.ErrInvalidPriority
		}
	}

	validChannels := map[string]bool{
		string(models.ChannelEmail):    true,
		string(models.ChannelSMS):      true,
		string(models.ChannelWebhook):  true,
		string(models.ChannelDingtalk): true,
		string(models.ChannelWechat):   true,
	}

	if !validChannels[req.Channel] {
		return errors.ErrInvalidChannel
	}

	return nil
}

func (s *Service) applyTemplate(req *SendRequest) (string, string, error) {
	if req.TemplateID == "" {
		return req.Title, req.Content, nil
	}

	s.templatesMu.RLock()
	tmpl, exists := s.templates[req.TemplateID]
	s.templatesMu.RUnlock()

	if !exists {
		return req.Title, req.Content, errors.ErrTemplateNotFound
	}

	titleTmpl, err := template.New("title").Parse(tmpl.TitleTmpl)
	if err != nil {
		return "", "", fmt.Errorf("parse title template: %w", err)
	}

	contentTmpl, err := template.New("content").Parse(tmpl.ContentTmpl)
	if err != nil {
		return "", "", fmt.Errorf("parse content template: %w", err)
	}

	var titleBuf, contentBuf bytes.Buffer
	if err := titleTmpl.Execute(&titleBuf, req.Variables); err != nil {
		return "", "", fmt.Errorf("execute title template: %w", err)
	}

	if err := contentTmpl.Execute(&contentBuf, req.Variables); err != nil {
		return "", "", fmt.Errorf("execute content template: %w", err)
	}

	return titleBuf.String(), contentBuf.String(), nil
}

func (s *Service) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	log := logger.Get()
	log.Info("starting notification service workers", zap.Int("worker_count", s.workers))

	for i := 0; i < s.workers; i++ {
		s.wg.Add(1)
		go s.worker(i)
	}

	go s.retryWorker()
}

func (s *Service) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	s.mu.Unlock()

	close(s.stopChan)
	s.queue.Close()
	s.wg.Wait()

	logger.Get().Info("notification service stopped")
}

func (s *Service) worker(id int) {
	defer s.wg.Done()

	log := logger.Get().With(zap.Int("worker_id", id))
	log.Info("notification worker started")

	for {
		select {
		case <-s.stopChan:
			log.Info("notification worker stopping")
			return
		default:
		}

		notification := s.queue.DequeueWithTimeout(1 * time.Second)
		if notification == nil {
			continue
		}

		ctx := context.Background()
		ctx = logger.ToContext(ctx, log.With(zap.String("notification_id", notification.ID)))

		s.processNotification(ctx, notification)
	}
}

func (s *Service) processNotification(ctx context.Context, record *models.NotificationRecord) {
	log := logger.FromContext(ctx)

	channel, exists := s.channels[models.NotificationChannel(record.Channel)]
	if !exists {
		s.markFailed(ctx, record, fmt.Sprintf("channel %s not found", record.Channel))
		return
	}

	now := time.Now()
	record.SentAt = &now
	record.Status = string(models.StatusSent)
	record.UpdatedAt = now

	if s.db != nil {
		s.db.Save(record)
	}

	log.Info("sending notification",
		zap.String("channel", record.Channel),
		zap.String("recipient", record.Recipient),
	)

	startTime := time.Now()
	err := channel.Send(ctx, record.Recipient, record.Title, record.Content, record.Metadata)
	latency := time.Since(startTime)

	if err != nil {
		log.Warn("notification send failed",
			zap.Error(err),
			zap.Int("retry_count", record.RetryCount),
			zap.Duration("latency", latency),
		)

		if record.RetryCount < record.MaxRetries {
			record.RetryCount++
			record.Status = string(models.StatusRetrying)
			record.UpdatedAt = time.Now()

			if s.db != nil {
				s.db.Save(record)
			}

			go func() {
				backoff := utils.GetBackoffDuration(s.retryInterval, record.RetryCount)
				log.Debug("scheduling retry",
					zap.Duration("backoff", backoff),
					zap.Int("retry_count", record.RetryCount),
				)
				time.Sleep(backoff)

				if s.running {
					s.queue.Enqueue(record)
				}
			}()
			return
		}

		s.markFailed(ctx, record, err.Error())
		return
	}

	deliveredAt := time.Now()
	record.DeliveredAt = &deliveredAt
	record.Status = string(models.StatusDelivered)
	record.UpdatedAt = deliveredAt

	if s.db != nil {
		s.db.Save(record)
	}

	s.suppressionMgr.RecordSent(ctx, record)

	log.Info("notification delivered successfully",
		zap.String("channel", record.Channel),
		zap.Duration("latency", latency),
	)
}

func (s *Service) markFailed(ctx context.Context, record *models.NotificationRecord, errMsg string) {
	log := logger.FromContext(ctx)
	log.Error("notification failed permanently", zap.String("error", errMsg))

	now := time.Now()
	record.FailedAt = &now
	record.ErrorMsg = &errMsg
	record.Status = string(models.StatusFailed)
	record.UpdatedAt = now

	if s.db != nil {
		s.db.Save(record)
	}
}

func (s *Service) retryWorker() {
	log := logger.Get().With(zap.String("component", "retry-worker"))
	log.Info("retry worker started")

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-s.stopChan:
			log.Info("retry worker stopping")
			return
		case <-ticker.C:
			s.retryStuckNotifications()
		}
	}
}

func (s *Service) retryStuckNotifications() {
	if s.db == nil {
		return
	}

	var stuck []models.NotificationRecord
	err := s.db.Where("status IN ? AND updated_at < ?",
		[]string{string(models.StatusPending), string(models.StatusQueued), string(models.StatusRetrying)},
		time.Now().Add(-5*time.Minute),
	).Limit(100).Find(&stuck).Error

	if err != nil {
		logger.Get().Warn("failed to query stuck notifications", zap.Error(err))
		return
	}

	for i := range stuck {
		notif := &stuck[i]
		if notif.RetryCount < notif.MaxRetries {
			notif.RetryCount++
			notif.Status = string(models.StatusRetrying)
			notif.UpdatedAt = time.Now()
			s.db.Save(notif)
			s.queue.Enqueue(notif)
			logger.Get().Info("requeued stuck notification", zap.String("id", notif.ID))
		}
	}
}

func (s *Service) GetNotification(ctx context.Context, id string) (*models.NotificationRecord, error) {
	if s.db == nil {
		return nil, errors.NewInternal("database not available", "")
	}

	var record models.NotificationRecord
	if err := s.db.First(&record, "id = ?", id).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.NewNotFound("notification not found", id)
		}
		return nil, errors.NewInternal("database error", err.Error())
	}

	return &record, nil
}

func (s *Service) ListNotifications(ctx context.Context, page, pageSize int, filters map[string]interface{}) ([]*models.NotificationRecord, int64, error) {
	if s.db == nil {
		return nil, 0, errors.NewInternal("database not available", "")
	}

	query := s.db.Model(&models.NotificationRecord{})

	if status, ok := filters["status"].(string); ok && status != "" {
		query = query.Where("status = ?", status)
	}
	if channel, ok := filters["channel"].(string); ok && channel != "" {
		query = query.Where("channel = ?", channel)
	}
	if nType, ok := filters["type"].(string); ok && nType != "" {
		query = query.Where("type = ?", nType)
	}

	var total int64
	if err := query.Count(&total).Error; err != nil {
		return nil, 0, errors.NewInternal("database error", err.Error())
	}

	var records []models.NotificationRecord
	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&records).Error; err != nil {
		return nil, 0, errors.NewInternal("database error", err.Error())
	}

	result := make([]*models.NotificationRecord, len(records))
	for i := range records {
		result[i] = &records[i]
	}

	return result, total, nil
}

func (s *Service) CreateTemplate(ctx context.Context, req *TemplateRequest) (*models.NotificationTemplate, error) {
	s.templatesMu.Lock()
	defer s.templatesMu.Unlock()

	tmpl := &models.NotificationTemplate{
		ID:              utils.NewID("tmpl"),
		Name:            req.Name,
		Type:            req.Type,
		Channel:         req.Channel,
		TitleTmpl:       req.TitleTmpl,
		ContentTmpl:     req.ContentTmpl,
		DefaultPriority: req.DefaultPriority,
		Variables:       req.Variables,
		Enabled:         req.Enabled,
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
	}

	if s.db != nil {
		if err := s.db.Create(tmpl).Error; err != nil {
			return nil, errors.NewInternal("failed to create template", err.Error())
		}
	}

	s.templates[tmpl.ID] = tmpl
	return tmpl, nil
}

func (s *Service) GetTemplate(ctx context.Context, id string) (*models.NotificationTemplate, error) {
	s.templatesMu.RLock()
	defer s.templatesMu.RUnlock()

	tmpl, exists := s.templates[id]
	if !exists {
		return nil, errors.ErrTemplateNotFound
	}
	return tmpl, nil
}

func (s *Service) ListTemplates(ctx context.Context) []*models.NotificationTemplate {
	s.templatesMu.RLock()
	defer s.templatesMu.RUnlock()

	result := make([]*models.NotificationTemplate, 0, len(s.templates))
	for _, tmpl := range s.templates {
		result = append(result, tmpl)
	}
	return result
}

func (s *Service) UpdateTemplate(ctx context.Context, id string, req *TemplateRequest) (*models.NotificationTemplate, error) {
	s.templatesMu.Lock()
	defer s.templatesMu.Unlock()

	existing, exists := s.templates[id]
	if !exists {
		return nil, errors.ErrTemplateNotFound
	}

	existing.Name = req.Name
	existing.Type = req.Type
	existing.Channel = req.Channel
	existing.TitleTmpl = req.TitleTmpl
	existing.ContentTmpl = req.ContentTmpl
	existing.DefaultPriority = req.DefaultPriority
	existing.Variables = req.Variables
	existing.Enabled = req.Enabled
	existing.UpdatedAt = time.Now()

	if s.db != nil {
		if err := s.db.Save(existing).Error; err != nil {
			return nil, errors.NewInternal("failed to update template", err.Error())
		}
	}

	s.templates[id] = existing
	return existing, nil
}

func (s *Service) DeleteTemplate(ctx context.Context, id string) error {
	s.templatesMu.Lock()
	defer s.templatesMu.Unlock()

	if _, exists := s.templates[id]; !exists {
		return errors.ErrTemplateNotFound
	}

	if s.db != nil {
		if err := s.db.Delete(&models.NotificationTemplate{}, "id = ?", id).Error; err != nil {
			return errors.NewInternal("failed to delete template", err.Error())
		}
	}

	delete(s.templates, id)
	return nil
}

func (s *Service) GetStats(ctx context.Context) *queue.QueueMetrics {
	metrics := s.queue.GetMetrics()
	return &metrics
}

func (s *Service) GetSuppressionManager() *suppression.Manager {
	return s.suppressionMgr
}

func (s *Service) GetRouterManager() *router.Manager {
	return s.routerMgr
}
