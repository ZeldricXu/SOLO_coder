package trigger

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/robfig/cron/v3"
	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/config"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/storage"
	"go.uber.org/zap"
	"gorm.io/datatypes"
	"gorm.io/gorm"
)

type EventAdapter interface {
	Adapt(ctx context.Context, source types.EventSource, payload interface{}, headers map[string]string) (*types.InternalEvent, error)
}

type TriggerAdapter struct {
	db          *gorm.DB
	redisClient *storage.RedisClient
	cfg         *config.WebhookConfig
	cron        *cron.Cron
	eventChan   chan *types.InternalEvent
	handlers    map[types.EventSource]EventHandler
}

type EventHandler interface {
	Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error)
	ValidateSignature(payload []byte, signature string, secret string) bool
}

func NewTriggerAdapter(cfg *config.WebhookConfig) *TriggerAdapter {
	ta := &TriggerAdapter{
		db:          storage.GetDB(),
		redisClient: &storage.RedisClient{},
		cfg:         cfg,
		cron:        cron.New(cron.WithSeconds()),
		eventChan:   make(chan *types.InternalEvent, 1000),
		handlers:    make(map[types.EventSource]EventHandler),
	}

	ta.handlers[types.EventSourceGitHub] = &GitHubHandler{secret: cfg.Secret}
	ta.handlers[types.EventSourceGitLab] = &GitLabHandler{secret: cfg.Secret}
	ta.handlers[types.EventSourceManual] = &ManualHandler{}
	ta.handlers[types.EventSourceAPI] = &APIHandler{}

	return ta
}

func (ta *TriggerAdapter) Start() {
	ta.cron.Start()
	go ta.loadScheduledTriggers()
	go ta.processEvents()
}

func (ta *TriggerAdapter) Stop() {
	ta.cron.Stop()
}

func (ta *TriggerAdapter) Events() <-chan *types.InternalEvent {
	return ta.eventChan
}

func (ta *TriggerAdapter) HandleWebhook(ctx context.Context, source types.EventSource, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	handler, ok := ta.handlers[source]
	if !ok {
		return nil, fmt.Errorf("unsupported event source: %s", source)
	}

	signature := headers["X-Hub-Signature-256"]
	if signature == "" {
		signature = headers["X-Gitlab-Token"]
	}

	if !handler.ValidateSignature(payload, signature, ta.cfg.Secret) {
		return nil, fmt.Errorf("invalid webhook signature")
	}

	event, err := handler.Handle(ctx, payload, headers)
	if err != nil {
		return nil, fmt.Errorf("failed to handle webhook: %w", err)
	}

	dedupKey := event.DeduplicationKey
	if dedupKey == "" {
		dedupKey = fmt.Sprintf("%s-%s-%d", source, event.EventType, time.Now().UnixNano())
	}

	isNew, err := ta.redisClient.Deduplicate(ctx, dedupKey, 24*time.Hour)
	if err != nil {
		logger.Warn("deduplication check failed", zap.Error(err))
	}
	if !isNew {
		logger.Info("duplicate event skipped",
			zap.String("source", string(source)),
			zap.String("dedup_key", dedupKey))
		return event, nil
	}

	webhookEvent := &models.WebhookEvent{
		ID:               types.ID(types.NewID()),
		Source:           source,
		EventType:        event.EventType,
		DeduplicationKey: dedupKey,
		ProjectID:        event.ProjectID,
		Commit:           event.Commit,
		Branch:           event.Branch,
		Tag:              event.Tag,
		Ref:              event.Ref,
		Message:          event.Message,
		Author:           event.Author,
		AuthorEmail:      event.AuthorEmail,
		Payload:          mustJSON(event.Payload),
		Headers:          mustJSON(headers),
		Signature:        signature,
		SignatureValid:   boolPtr(true),
	}

	if err := ta.db.Create(webhookEvent).Error; err != nil {
		logger.Error("failed to save webhook event", zap.Error(err))
	}

	event.ID = webhookEvent.ID

	ta.eventChan <- event
	return event, nil
}

func (ta *TriggerAdapter) TriggerManual(ctx context.Context, pipelineID types.ID, variables map[string]string) (*types.InternalEvent, error) {
	event := &types.InternalEvent{
		ID:          types.ID(types.NewID()),
		EventSource: types.EventSourceManual,
		EventType:   types.EventTypeManual,
		PipelineID:  pipelineID,
		Payload:     map[string]interface{}{"variables": variables},
		ReceivedAt:  time.Now(),
	}

	ta.eventChan <- event
	return event, nil
}

func (ta *TriggerAdapter) TriggerAPI(ctx context.Context, projectID string, params map[string]interface{}) (*types.InternalEvent, error) {
	event := &types.InternalEvent{
		ID:          types.ID(types.NewID()),
		EventSource: types.EventSourceAPI,
		EventType:   types.EventTypeManual,
		ProjectID:   projectID,
		Payload:     params,
		ReceivedAt:  time.Now(),
	}

	ta.eventChan <- event
	return event, nil
}

func (ta *TriggerAdapter) AddCronTrigger(pipelineID types.ID, schedule string, variables map[string]string) error {
	_, err := ta.cron.AddFunc(schedule, func() {
		event := &types.InternalEvent{
			ID:          types.ID(types.NewID()),
			EventSource: types.EventSourceCron,
			EventType:   types.EventTypeSchedule,
			PipelineID:  pipelineID,
			Payload:     map[string]interface{}{"variables": variables},
			ReceivedAt:  time.Now(),
		}
		ta.eventChan <- event

		ta.db.Model(&models.ScheduledTrigger{}).
			Where("pipeline_id = ? AND cron_expression = ?", pipelineID, schedule).
			Update("last_run", time.Now())
	})
	if err != nil {
		return fmt.Errorf("invalid cron schedule: %w", err)
	}

	st := &models.ScheduledTrigger{
		ID:             types.ID(types.NewID()),
		PipelineID:     pipelineID,
		CronExpression: schedule,
		Variables:      mustJSON(variables),
		NextRun:        calculateNextRun(schedule),
		Enabled:        true,
	}
	return ta.db.Create(st).Error
}

func (ta *TriggerAdapter) loadScheduledTriggers() {
	var triggers []models.ScheduledTrigger
	if err := ta.db.Where("enabled = ?", true).Find(&triggers).Error; err != nil {
		logger.Error("failed to load scheduled triggers", zap.Error(err))
		return
	}

	for _, t := range triggers {
		var vars map[string]string
		json.Unmarshal(t.Variables, &vars)

		_, err := ta.cron.AddFunc(t.CronExpression, func(pipelineID types.ID, v map[string]string) func() {
			return func() {
				event := &types.InternalEvent{
					ID:          types.ID(types.NewID()),
					EventSource: types.EventSourceCron,
					EventType:   types.EventTypeSchedule,
					PipelineID:  pipelineID,
					Payload:     map[string]interface{}{"variables": v},
					ReceivedAt:  time.Now(),
				}
				ta.eventChan <- event

				ta.db.Model(&models.ScheduledTrigger{}).
					Where("id = ?", t.ID).
					Updates(map[string]interface{}{
						"last_run": time.Now(),
						"next_run": calculateNextRun(t.CronExpression),
					})
			}
		}(t.PipelineID, vars))

		if err != nil {
			logger.Error("failed to add cron trigger",
				zap.String("pipeline_id", string(t.PipelineID)),
				zap.Error(err))
		}
	}

	logger.Info("loaded scheduled triggers", zap.Int("count", len(triggers)))
}

func (ta *TriggerAdapter) processEvents() {
	for event := range ta.eventChan {
		matchedPipelines, err := ta.matchPipelines(event)
		if err != nil {
			logger.Error("failed to match pipelines", zap.Error(err))
			continue
		}

		if len(matchedPipelines) == 0 {
			logger.Info("no pipelines matched for event",
				zap.String("source", string(event.EventSource)),
				zap.String("event_type", string(event.EventType)))
			continue
		}

		logger.Info("matched pipelines for event",
			zap.String("source", string(event.EventSource)),
			zap.Int("count", len(matchedPipelines)))

		event.ProcessedAt = timePtr(time.Now())

		for _, pipelineID := range matchedPipelines {
			ta.enqueueExecution(event, pipelineID)
		}
	}
}

func (ta *TriggerAdapter) matchPipelines(event *types.InternalEvent) ([]types.ID, error) {
	var pipelines []models.Pipeline
	query := ta.db.Where("status = ?", types.PipelineStatusActive)

	if event.ProjectID != "" {
		query = query.Where("project_id = ?", event.ProjectID)
	}
	if event.PipelineID != "" {
		query = query.Where("id = ?", event.PipelineID)
	}

	if err := query.Find(&pipelines).Error; err != nil {
		return nil, err
	}

	var matched []types.ID
	for _, pipeline := range pipelines {
		def, err := pipeline.GetDefinition()
		if err != nil {
			logger.Warn("failed to parse pipeline definition",
				zap.String("pipeline_id", string(pipeline.ID)),
				zap.Error(err))
			continue
		}

		if ta.matchesTriggers(def.Triggers, event) {
			matched = append(matched, pipeline.ID)
		}
	}

	return matched, nil
}

func (ta *TriggerAdapter) matchesTriggers(triggers []types.PipelineTrigger, event *types.InternalEvent) bool {
	if len(triggers) == 0 {
		return true
	}

	for _, trigger := range triggers {
		if trigger.EventSource != event.EventSource {
			continue
		}
		if trigger.EventType != event.EventType {
			continue
		}

		if trigger.Condition != nil {
			if !ta.matchesCondition(trigger.Condition, event) {
				continue
			}
		}

		return true
	}

	return false
}

func (ta *TriggerAdapter) matchesCondition(cond *types.TriggerCondition, event *types.InternalEvent) bool {
	if cond.Branch != "" && event.Branch != cond.Branch {
		return false
	}
	if len(cond.Tags) > 0 {
		found := false
		for _, tag := range cond.Tags {
			if event.Tag == tag {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	if len(cond.EventTypes) > 0 {
		found := false
		for _, et := range cond.EventTypes {
			if string(event.EventType) == et {
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}

func (ta *TriggerAdapter) enqueueExecution(event *types.InternalEvent, pipelineID types.ID) {
	queuePayload, _ := json.Marshal(map[string]interface{}{
		"event_id":    event.ID,
		"pipeline_id": pipelineID,
		"event":       event,
		"enqueued_at": time.Now(),
	})

	if err := ta.redisClient.Enqueue(context.Background(), "executions", string(queuePayload)); err != nil {
		logger.Error("failed to enqueue execution", zap.Error(err))
	}
}

type GitHubHandler struct {
	secret string
}

func (h *GitHubHandler) Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	var ghPayload map[string]interface{}
	if err := json.Unmarshal(payload, &ghPayload); err != nil {
		return nil, err
	}

	eventType := headers["X-GitHub-Event"]
	deliveryID := headers["X-GitHub-Delivery"]

	event := &types.InternalEvent{
		EventSource:      types.EventSourceGitHub,
		DeduplicationKey: deliveryID,
		Payload:          ghPayload,
		ReceivedAt:       time.Now(),
	}

	switch eventType {
	case "push":
		event.EventType = types.EventTypePush
		if ref, ok := ghPayload["ref"].(string); ok {
			event.Ref = ref
			if strings.HasPrefix(ref, "refs/heads/") {
				event.Branch = strings.TrimPrefix(ref, "refs/heads/")
			} else if strings.HasPrefix(ref, "refs/tags/") {
				event.Tag = strings.TrimPrefix(ref, "refs/tags/")
				event.EventType = types.EventTypeTag
			}
		}
		if head, ok := ghPayload["head_commit"].(map[string]interface{}); ok {
			event.Commit = getString(head, "id")
			event.Message = getString(head, "message")
			if author, ok := head["author"].(map[string]interface{}); ok {
				event.Author = getString(author, "name")
				event.AuthorEmail = getString(author, "email")
			}
		}
		if repo, ok := ghPayload["repository"].(map[string]interface{}); ok {
			event.ProjectID = getString(repo, "full_name")
		}

	case "pull_request":
		event.EventType = types.EventTypePullRequest
		if pr, ok := ghPayload["pull_request"].(map[string]interface{}); ok {
			event.Message = getString(pr, "title")
			if head, ok := pr["head"].(map[string]interface{}); ok {
				event.Ref = getString(head, "ref")
				event.Branch = getString(head, "ref")
				event.Commit = getString(head, "sha")
			}
			if base, ok := pr["base"].(map[string]interface{}); ok {
				event.ProjectID = getString(base, "repo.full_name")
			}
			if user, ok := pr["user"].(map[string]interface{}); ok {
				event.Author = getString(user, "login")
			}
		}

	case "create":
		event.EventType = types.EventTypeTag
		if refType, ok := ghPayload["ref_type"].(string); ok && refType == "tag" {
			event.Tag = getString(ghPayload, "ref")
			if repo, ok := ghPayload["repository"].(map[string]interface{}); ok {
				event.ProjectID = getString(repo, "full_name")
			}
		}

	case "release":
		event.EventType = types.EventTypeRelease
		if release, ok := ghPayload["release"].(map[string]interface{}); ok {
			event.Tag = getString(release, "tag_name")
			event.Message = getString(release, "name")
		}
		if repo, ok := ghPayload["repository"].(map[string]interface{}); ok {
			event.ProjectID = getString(repo, "full_name")
		}

	default:
		event.EventType = types.EventType(eventType)
	}

	return event, nil
}

func (h *GitHubHandler) ValidateSignature(payload []byte, signature string, secret string) bool {
	if secret == "" || signature == "" {
		return true
	}

	if !strings.HasPrefix(signature, "sha256=") {
		return false
	}

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(payload)
	expected := "sha256=" + hex.EncodeToString(mac.Sum(nil))

	return hmac.Equal([]byte(signature), []byte(expected))
}

type GitLabHandler struct {
	secret string
}

func (h *GitLabHandler) Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	var glPayload map[string]interface{}
	if err := json.Unmarshal(payload, &glPayload); err != nil {
		return nil, err
	}

	event := &types.InternalEvent{
		EventSource: types.EventSourceGitLab,
		Payload:     glPayload,
		ReceivedAt:  time.Now(),
	}

	if objectKind, ok := glPayload["object_kind"].(string); ok {
		switch objectKind {
		case "push":
			event.EventType = types.EventTypePush
			event.Ref = getString(glPayload, "ref")
			event.Branch = strings.TrimPrefix(event.Ref, "refs/heads/")
			event.Commit = getString(glPayload, "checkout_sha")
			event.Message = getString(glPayload, "commits.0.message")
			event.Author = getString(glPayload, "user_username")
			event.AuthorEmail = getString(glPayload, "user_email")
			if project, ok := glPayload["project"].(map[string]interface{}); ok {
				event.ProjectID = getString(project, "path_with_namespace")
			}

		case "merge_request":
			event.EventType = types.EventTypePullRequest
			if mr, ok := glPayload["object_attributes"].(map[string]interface{}); ok {
				event.Message = getString(mr, "title")
				event.Branch = getString(mr, "source_branch")
				event.Commit = getString(mr, "last_commit.id")
			}
			if project, ok := glPayload["project"].(map[string]interface{}); ok {
				event.ProjectID = getString(project, "path_with_namespace")
			}

		case "tag_push":
			event.EventType = types.EventTypeTag
			event.Ref = getString(glPayload, "ref")
			event.Tag = strings.TrimPrefix(event.Ref, "refs/tags/")
			if project, ok := glPayload["project"].(map[string]interface{}); ok {
				event.ProjectID = getString(project, "path_with_namespace")
			}
		}
	}

	return event, nil
}

func (h *GitLabHandler) ValidateSignature(payload []byte, signature string, secret string) bool {
	if secret == "" {
		return true
	}
	return signature == secret
}

type ManualHandler struct{}

func (h *ManualHandler) Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	return &types.InternalEvent{
		EventSource: types.EventSourceManual,
		EventType:   types.EventTypeManual,
		ReceivedAt:  time.Now(),
	}, nil
}

func (h *ManualHandler) ValidateSignature(payload []byte, signature string, secret string) bool {
	return true
}

type APIHandler struct{}

func (h *APIHandler) Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	return &types.InternalEvent{
		EventSource: types.EventSourceAPI,
		EventType:   types.EventTypeManual,
		ReceivedAt:  time.Now(),
	}, nil
}

func (h *APIHandler) ValidateSignature(payload []byte, signature string, secret string) bool {
	return true
}

func mustJSON(v interface{}) datatypes.JSON {
	data, _ := json.Marshal(v)
	return datatypes.JSON(data)
}

func boolPtr(b bool) *bool {
	return &b
}

func timePtr(t time.Time) *time.Time {
	return &t
}

func getString(m map[string]interface{}, key string) string {
	keys := strings.Split(key, ".")
	var current interface{} = m
	for _, k := range keys {
		if currMap, ok := current.(map[string]interface{}); ok {
			current = currMap[k]
		} else {
			return ""
		}
	}
	if s, ok := current.(string); ok {
		return s
	}
	return ""
}

func calculateNextRun(schedule string) time.Time {
	s, err := cron.ParseStandard(schedule)
	if err != nil {
		return time.Now().Add(time.Hour)
	}
	return s.Next(time.Now())
}
