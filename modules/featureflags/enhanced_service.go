package featureflags

import (
	"context"
	"depguard/database"
	"depguard/events"
	"depguard/logger"
	"depguard/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"sync"
	"time"
)

type AsyncOperationType string

const (
	AsyncOpCreateFlag    AsyncOperationType = "create_flag"
	AsyncOpUpdateFlag  AsyncOperationType = "update_flag"
	AsyncOpDeleteFlag  AsyncOperationType = "delete_flag"
	AsyncOpToggleFlag  AsyncOperationType = "toggle_flag"
	AsyncOpEvaluate    AsyncOperationType = "evaluate"
	AsyncOpBatchEval   AsyncOperationType = "batch_evaluate"
)

type AsyncOperation struct {
	ID        string
	Type      AsyncOperationType
	Payload   interface{}
	Status    string
	Error     error
	Result    interface{}
	CreatedAt time.Time
	UpdatedAt time.Time
}

type OperationCallback func(op *AsyncOperation)

type AsyncHandler interface {
	Execute(ctx context.Context, op *AsyncOperation) error
}

type EnhancedService struct {
	Service
	asyncPool    *AsyncOperationPool
	eventBus   *events.EventBus
	callbacks  map[AsyncOperationType][]OperationCallback
	operations map[string]*AsyncOperation
	workers    int
	stopCh     chan struct{}
	started    bool
	mu         sync.RWMutex
}

type AsyncOperationPool struct {
	workerCount int
	taskCh      chan asyncTask
	wg          sync.WaitGroup
	stopCh      chan struct{}
	running     bool
	mu          sync.RWMutex
}

type asyncTask struct {
	ctx    context.Context
	op     *AsyncOperation
	svc    *EnhancedService
	callback OperationCallback
}

var (
	enhancedFlagInstance *EnhancedService
	enhancedFlagOnce   sync.Once
)

func NewEnhancedService() *EnhancedService {
	enhancedFlagOnce.Do(func() {
		enhancedFlagInstance = &EnhancedService{
			Service: Service{
				db: database.Get(),
			},
			workers:    4,
			stopCh:   make(chan struct{}),
			callbacks: make(map[AsyncOperationType][]OperationCallback),
			operations: make(map[string]*AsyncOperation),
			started:    false,
		}
		enhancedFlagInstance.initialize()
	})
	return enhancedFlagInstance
}

func NewEnhancedServiceWithDeps(db *gorm.DB, cache CacheClient) *EnhancedService {
	svc := &EnhancedService{
		Service: Service{
			db:    db,
			cache: cache,
		},
		workers:    4,
		stopCh:   make(chan struct{}),
		callbacks: make(map[AsyncOperationType][]OperationCallback),
		operations: make(map[string]*AsyncOperation),
		started:    false,
	}
	svc.initialize()
	return svc
}

func (s *EnhancedService) initialize() {
	s.asyncPool = &AsyncOperationPool{
		workerCount: s.workers,
		taskCh:      make(chan asyncTask, 100),
		stopCh:      make(chan struct{}),
		running:     false,
	}
	s.eventBus = events.Get()
	s.startWorkers()
	s.started = true
	logger.Get().Info("FeatureFlags EnhancedService initialized", zap.Int("workers", s.workers))
}

func (s *EnhancedService) startWorkers() {
	if s.asyncPool.running {
		return
	}

	s.asyncPool.mu.Lock()
	s.asyncPool.running = true
	s.asyncPool.mu.Unlock()

	for i := 0; i < s.workers; i++ {
		s.asyncPool.wg.Add(1)
		go s.worker(i + 1)
	}
}

func (s *EnhancedService) worker(id int) {
	defer s.asyncPool.wg.Done()
	logger.Get().Info("async worker started", zap.Int("worker_id", id))

	for {
		select {
		case task := <-s.asyncPool.taskCh:
			s.executeTask(task)
		case <-s.asyncPool.stopCh:
			logger.Get().Info("async worker stopping", zap.Int("worker_id", id))
			return
		}
	}
}

func (s *EnhancedService) executeTask(task asyncTask) {
	ctx, cancel := context.WithTimeout(task.ctx, 30*time.Second)
	defer cancel()

	op := task.op
	op.Status = "running"
	op.UpdatedAt = time.Now()
	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.eventBus.Publish(ctx, events.Event{
		Type: "featureflag.operation.started",
		Payload: map[string]interface{}{
			"operation_id": op.ID,
			"type":         string(op.Type),
		},
	})

	var err error
	var result interface{}

	switch op.Type {
	case AsyncOpCreateFlag:
		if req, ok := op.Payload.(*FeatureFlag); ok {
			err = s.createFlagInternal(ctx, req)
		}
	case AsyncOpUpdateFlag:
		if req, ok := op.Payload.(*FeatureFlag); ok {
			err = s.updateFlagInternal(ctx, req)
		}
	case AsyncOpDeleteFlag:
		if id, ok := op.Payload.(string); ok {
			err = s.deleteFlagInternal(ctx, id)
		}
	case AsyncOpToggleFlag:
		if payload, ok := op.Payload.(TogglePayload); ok {
			err = s.toggleFlagInternal(ctx, payload.ID, payload.Enabled)
		}
	case AsyncOpEvaluate:
		if req, ok := op.Payload.(EvaluateRequest); ok {
			result, err = s.evaluateInternal(ctx, req)
		}
	case AsyncOpBatchEval:
		if req, ok := op.Payload.(BatchEvaluateRequest); ok {
			result, err = s.batchEvaluateInternal(ctx, req)
		}
	}

	op.Error = err
	op.Result = result

	if err != nil {
		op.Status = "failed"
	} else {
		op.Status = "completed"
	}
	op.UpdatedAt = time.Now()

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	eventType := "featureflag.operation.completed"
	if err != nil {
		eventType = "featureflag.operation.failed"
	}
	s.eventBus.Publish(ctx, events.Event{
		Type: eventType,
		Payload: map[string]interface{}{
			"operation_id": op.ID,
			"type":         string(op.Type),
			"error":        err,
			"result":       result,
		},
	})

	if task.callback != nil {
		task.callback(op)
	}

	s.mu.RLock()
	callbacks := s.callbacks[op.Type]
	s.mu.RUnlock()

	for _, cb := range callbacks {
		cb(op)
	}
}

func (s *EnhancedService) Stop() {
	if !s.started {
		return
	}

	s.asyncPool.mu.Lock()
	if s.asyncPool.running {
		close(s.asyncPool.stopCh)
		s.asyncPool.running = false
	}
	s.asyncPool.mu.Unlock()

	s.asyncPool.wg.Wait()
	close(s.stopCh)
	s.started = false
	logger.Get().Info("FeatureFlags EnhancedService stopped")
}

func (s *EnhancedService) RegisterCallback(opType AsyncOperationType, callback OperationCallback) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.callbacks[opType] = append(s.callbacks[opType], callback)
}

func (s *EnhancedService) UnregisterCallback(opType AsyncOperationType, callback OperationCallback) {
	s.mu.Lock()
	defer s.mu.Unlock()

	callbacks, ok := s.callbacks[opType]
	if !ok {
		return
	}

	var newCallbacks []OperationCallback
	for _, cb := range callbacks {
		if &cb != &callback {
			newCallbacks = append(newCallbacks, cb)
		}
	}
	s.callbacks[opType] = newCallbacks
}

func (s *EnhancedService) CreateFlagAsync(ctx context.Context, flag *FeatureFlag, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpCreateFlag,
		Payload:   flag,
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

func (s *EnhancedService) UpdateFlagAsync(ctx context.Context, flag *FeatureFlag, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpUpdateFlag,
		Payload:   flag,
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

func (s *EnhancedService) DeleteFlagAsync(ctx context.Context, id string, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpDeleteFlag,
		Payload:   id,
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

type TogglePayload struct {
	ID      string
	Enabled bool
}

func (s *EnhancedService) ToggleFlagAsync(ctx context.Context, id string, enabled bool, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpToggleFlag,
		Payload:   TogglePayload{ID: id, Enabled: enabled},
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

type EvaluateRequest struct {
	FlagKey string
	UserID  string
	UserTags map[string]interface{}
	Context map[string]interface{}
}

func (s *EnhancedService) EvaluateAsync(ctx context.Context, req EvaluateRequest, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpEvaluate,
		Payload:   req,
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

type BatchEvaluateRequest struct {
	FlagKeys []string
	UserID   string
	UserTags map[string]interface{}
	Context  map[string]interface{}
}

func (s *EnhancedService) BatchEvaluateAsync(ctx context.Context, req BatchEvaluateRequest, callback OperationCallback) (string, error) {
	op := &AsyncOperation{
		ID:        utils.GenerateID("op"),
		Type:      AsyncOpBatchEval,
		Payload:   req,
		Status:    "pending",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	}

	s.mu.Lock()
	s.operations[op.ID] = op
	s.mu.Unlock()

	s.asyncPool.taskCh <- asyncTask{
		ctx:      ctx,
		op:       op,
		svc:      s,
		callback: callback,
	}

	return op.ID, nil
}

func (s *EnhancedService) GetOperation(opID string) (*AsyncOperation, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	op, exists := s.operations[opID]
	return op, exists
}

func (s *EnhancedService) WaitForOperation(ctx context.Context, opID string, timeout time.Duration) (*AsyncOperation, error) {
	deadline := time.Now().Add(timeout)

	for time.Now().Before(deadline) {
		op, exists := s.GetOperation(opID)
		if exists && (op.Status == "completed" || op.Status == "failed") {
			return op, nil
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}

	op, _ := s.GetOperation(opID)
	return op, nil
}

func (s *EnhancedService) IsStarted() bool {
	return s.started
}

func (s *EnhancedService) createFlagInternal(ctx context.Context, flag *FeatureFlag) error {
	if flag.ID == "" {
		flag.ID = utils.GenerateID("flag")
	}
	flag.CreatedAt = time.Now()
	flag.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(flag).Error; err != nil {
		return err
	}

	s.invalidateCache(flag.Key)
	logger.Get().Info("flag created async", zap.String("id", flag.ID), zap.String("key", flag.Key))
	return nil
}

func (s *EnhancedService) updateFlagInternal(ctx context.Context, flag *FeatureFlag) error {
	flag.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Save(flag).Error; err != nil {
		return err
	}

	s.invalidateCache(flag.Key)
	logger.Get().Info("flag updated async", zap.String("id", flag.ID), zap.String("key", flag.Key))
	return nil
}

func (s *EnhancedService) deleteFlagInternal(ctx context.Context, id string) error {
	var flag FeatureFlag
	if err := s.db.WithContext(ctx).First(&flag, "id = ?", id).Error; err != nil {
		return err
	}

	if err := s.db.WithContext(ctx).Delete(&flag).Error; err != nil {
		return err
	}

	s.invalidateCache(flag.Key)
	logger.Get().Info("flag deleted async", zap.String("id", id), zap.String("key", flag.Key))
	return nil
}

func (s *EnhancedService) toggleFlagInternal(ctx context.Context, id string, enabled bool) error {
	var flag FeatureFlag
	if err := s.db.WithContext(ctx).First(&flag, "id = ?", id).Error; err != nil {
		return err
	}

	flag.Enabled = enabled
	flag.UpdatedAt = time.Now()
	if err := s.db.WithContext(ctx).Save(&flag).Error; err != nil {
		return err
	}

	s.invalidateCache(flag.Key)
	logger.Get().Info("flag toggled async", zap.String("id", id), zap.Bool("enabled", enabled))
	return nil
}

func (s *EnhancedService) evaluateInternal(ctx context.Context, req EvaluateRequest) (bool, error) {
	var flag FeatureFlag
	if err := s.db.WithContext(ctx).First(&flag, "key = ? AND enabled = ?", req.FlagKey, true).Error; err != nil {
		return false, nil
	}

	if !flag.Enabled {
		return false, nil
	}

	if flag.UserOverride != nil {
		if enabled, ok := flag.UserOverride[req.UserID]; ok {
			return enabled, nil
		}
	}

	if len(flag.Segments) > 0 {
		for _, seg := range flag.Segments {
			if s.matchSegment(seg, req.UserID, req.UserTags, req.Context) {
				return true, nil
			}
		}
	}

	if flag.GlobalPercentage > 0 {
		if s.shouldShow(flag.ID, req.UserID, flag.GlobalPercentage) {
			return true, nil
		}
	}

	return false, nil
}

func (s *EnhancedService) batchEvaluateInternal(ctx context.Context, req BatchEvaluateRequest) (map[string]bool, error) {
	results := make(map[string]bool)

	for _, key := range req.FlagKeys {
		flag, err := s.evaluateInternal(ctx, EvaluateRequest{
			FlagKey:  key,
			UserID:   req.UserID,
			UserTags: req.UserTags,
			Context:  req.Context,
		})
		if err == nil {
			results[key] = flag
		}
	}

	return results, nil
}

func (s *EnhancedService) shouldShow(flagID string, userID string, percentage float64) bool {
	if percentage <= 0 {
		return false
	}
	if percentage >= 100 {
		return true
	}

	hash := 0
	for _, c := range flagID+userID {
		hash = (hash*31 + int(c)) % 100
	}
	return float64(hash) < percentage
}

func (s *EnhancedService) matchSegment(seg *Segment, userID string, userTags map[string]interface{}, ctx map[string]interface{}) bool {
	if seg == nil {
		return false
	}

	if len(seg.UserIDs) > 0 {
		for _, id := range seg.UserIDs {
			if id == userID {
				return true
			}
		}
	}

	if len(seg.Rules) > 0 {
		allMatch := true
		for _, rule := range seg.Rules {
			if !s.matchRule(rule, userTags, ctx) {
				allMatch = false
				break
			}
		}
		if allMatch {
			return true
		}
	}

	return false
}

func (s *EnhancedService) matchRule(rule *SegmentRule, userTags map[string]interface{}, ctx map[string]interface{}) bool {
	var value interface{}
	if v, ok := userTags[rule.Key]; ok {
		value = v
	} else if v, ok := ctx[rule.Key]; ok {
		value = v
	} else {
		return false
	}

	switch rule.Operator {
	case "eq":
		return value == rule.Value
	case "ne":
		return value != rule.Value
	case "in":
		if arr, ok := rule.Value.([]interface{}); ok {
			for _, v := range arr {
				if v == value {
					return true
				}
			}
		}
		return false
	case "not_in":
		if arr, ok := rule.Value.([]interface{}); ok {
			for _, v := range arr {
				if v == value {
					return false
				}
			}
		}
		return true
	}
	return false
}
