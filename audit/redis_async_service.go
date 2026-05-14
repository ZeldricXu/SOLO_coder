package audit

import (
	"accessguard/config"
	"accessguard/models"
	"accessguard/storage"
	"accessguard/utils"
	"context"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type RedisAsyncService struct {
	store         storage.AuditStore
	redisClient   *redis.Client
	cfg           config.RedisConfig
	flushInterval time.Duration
	batchSize     int
	workerWg      sync.WaitGroup
	ctx           context.Context
	cancel        context.CancelFunc
	started       bool
	mu            sync.RWMutex
	fallbackBuffer chan *models.AuditRecord
	fallbackSize  int
}

func NewRedisAsyncService(store storage.AuditStore, cfg config.RedisConfig, flushInterval time.Duration, batchSize int) *RedisAsyncService {
	if flushInterval <= 0 {
		flushInterval = 1 * time.Second
	}
	if batchSize <= 0 {
		batchSize = 100
	}

	var redisClient *redis.Client
	if cfg.Enabled {
		redisClient = redis.NewClient(&redis.Options{
			Addr:       cfg.Address,
			Password:   cfg.Password,
			DB:         cfg.DB,
			MaxRetries: cfg.MaxRetries,
		})
	}

	return &RedisAsyncService{
		store:         store,
		redisClient:   redisClient,
		cfg:           cfg,
		flushInterval: flushInterval,
		batchSize:     batchSize,
		started:       false,
		fallbackSize:  1000,
		fallbackBuffer: make(chan *models.AuditRecord, 1000),
	}
}

func (s *RedisAsyncService) Start() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.started {
		return
	}

	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.started = true

	if s.cfg.Enabled && s.redisClient != nil {
		if err := s.redisClient.Ping(s.ctx).Err(); err != nil {
			log.Printf("Redis connection failed, will use fallback memory buffer: %v", err)
		} else {
			log.Printf("Redis audit buffer connected to %s", s.cfg.Address)
		}
	}

	s.workerWg.Add(1)
	go s.worker()
}

func (s *RedisAsyncService) Stop() {
	s.mu.Lock()
	if !s.started {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()

	s.cancel()

	if s.fallbackBuffer != nil {
		close(s.fallbackBuffer)
	}

	s.workerWg.Wait()

	if s.redisClient != nil {
		s.redisClient.Close()
	}

	s.mu.Lock()
	s.started = false
	s.mu.Unlock()
}

func (s *RedisAsyncService) worker() {
	defer s.workerWg.Done()

	ticker := time.NewTicker(s.flushInterval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			s.flushAll()
			return

		case record, ok := <-s.fallbackBuffer:
			if ok {
				if err := s.store.Record(record); err != nil {
					log.Printf("Failed to write fallback audit record: %v", err)
				}
			}

		case <-ticker.C:
			s.flushRedis()
		}
	}
}

func (s *RedisAsyncService) flushRedis() {
	if !s.cfg.Enabled || s.redisClient == nil {
		return
	}

	ctx := context.Background()

	for {
		result, err := s.redisClient.LPop(ctx, s.cfg.AuditQueue).Result()
		if err != nil {
			if err != redis.Nil {
				log.Printf("Failed to pop from redis queue: %v", err)
			}
			return
		}

		var record models.AuditRecord
		if err := json.Unmarshal([]byte(result), &record); err != nil {
			log.Printf("Failed to unmarshal audit record: %v", err)
			continue
		}

		if err := s.store.Record(&record); err != nil {
			log.Printf("Failed to write audit record: %v", err)
			s.redisClient.LPush(ctx, s.cfg.AuditQueue, result)
			return
		}
	}
}

func (s *RedisAsyncService) flushAll() {
	if s.cfg.Enabled && s.redisClient != nil {
		ctx := context.Background()
		for {
			result, err := s.redisClient.LPop(ctx, s.cfg.AuditQueue).Result()
			if err != nil {
				if err != redis.Nil {
					log.Printf("Failed to pop from redis queue during shutdown: %v", err)
				}
				break
			}

			var record models.AuditRecord
			if err := json.Unmarshal([]byte(result), &record); err != nil {
				log.Printf("Failed to unmarshal audit record: %v", err)
				continue
			}

			if err := s.store.Record(&record); err != nil {
				log.Printf("Failed to write audit record during shutdown: %v", err)
			}
		}
	}

	for record := range s.fallbackBuffer {
		if err := s.store.Record(record); err != nil {
			log.Printf("Failed to write fallback audit record during shutdown: %v", err)
		}
	}
}

func (s *RedisAsyncService) RecordAccessAsync(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) {
	audit := &models.AuditRecord{
		AuditID:      utils.GenerateAuditID(),
		UserID:       userID,
		ResourceID:   resourceID,
		Action:       action,
		AccessResult: result,
		IPAddress:    ipAddress,
		SessionID:    sessionID,
		AccessTime:   time.Now(),
		Reason:       reason,
	}

	s.mu.RLock()
	started := s.started
	s.mu.RUnlock()

	if !started {
		s.store.Record(audit)
		return
	}

	if s.cfg.Enabled && s.redisClient != nil {
		data, err := json.Marshal(audit)
		if err != nil {
			log.Printf("Failed to marshal audit record: %v", err)
			s.store.Record(audit)
			return
		}

		ctx := context.Background()
		if err := s.redisClient.RPush(ctx, s.cfg.AuditQueue, data).Err(); err != nil {
			log.Printf("Failed to push to redis, using fallback: %v", err)
			s.fallbackOrSync(audit)
			return
		}
	} else {
		s.fallbackOrSync(audit)
	}
}

func (s *RedisAsyncService) fallbackOrSync(audit *models.AuditRecord) {
	select {
	case s.fallbackBuffer <- audit:
	default:
		s.store.Record(audit)
	}
}

func (s *RedisAsyncService) RecordLoginAsync(userID, ipAddress, sessionID string, success bool) {
	result := models.AccessAllowed
	reason := "login_success"
	if !success {
		result = models.AccessDenied
		reason = "login_failed"
	}

	s.RecordAccessAsync(userID, "system_login", "login", ipAddress, sessionID, result, reason)
}

func (s *RedisAsyncService) RecordLogoutAsync(userID, ipAddress, sessionID string) {
	s.RecordAccessAsync(userID, "system_logout", "logout", ipAddress, sessionID, models.AccessAllowed, "logout_success")
}

func (s *RedisAsyncService) QueryRecords(userID string, startTime, endTime time.Time, limit, offset int) ([]*models.AuditRecord, int, error) {
	return s.store.Query(userID, startTime, endTime, limit, offset)
}

func (s *RedisAsyncService) GetRecordByID(auditID string) (*models.AuditRecord, error) {
	return s.store.GetByID(auditID)
}

func (s *RedisAsyncService) QueryBySession(sessionID string, limit, offset int) ([]*models.AuditRecord, int, error) {
	records, total, err := s.store.Query("", time.Time{}, time.Time{}, 1000, 0)
	if err != nil {
		return nil, 0, err
	}

	filtered := make([]*models.AuditRecord, 0, len(records))
	for _, r := range records {
		if r.SessionID == sessionID {
			filtered = append(filtered, r)
		}
	}

	total = len(filtered)
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}

	start := offset
	end := offset + limit
	if start > total {
		start = total
	}
	if end > total {
		end = total
	}

	return filtered[start:end], total, nil
}

func (s *RedisAsyncService) QueryByResource(resourceID string, limit, offset int) ([]*models.AuditRecord, int, error) {
	records, total, err := s.store.Query("", time.Time{}, time.Time{}, 1000, 0)
	if err != nil {
		return nil, 0, err
	}

	filtered := make([]*models.AuditRecord, 0, len(records))
	for _, r := range records {
		if r.ResourceID == resourceID {
			filtered = append(filtered, r)
		}
	}

	total = len(filtered)
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}

	start := offset
	end := offset + limit
	if start > total {
		start = total
	}
	if end > total {
		end = total
	}

	return filtered[start:end], total, nil
}

func (s *RedisAsyncService) RecordAccess(userID, resourceID, action, ipAddress, sessionID string, result models.AccessResult, reason string) error {
	s.RecordAccessAsync(userID, resourceID, action, ipAddress, sessionID, result, reason)
	return nil
}

func (s *RedisAsyncService) RecordLogin(userID, ipAddress, sessionID string, success bool) error {
	s.RecordLoginAsync(userID, ipAddress, sessionID, success)
	return nil
}

func (s *RedisAsyncService) RecordLogout(userID, ipAddress, sessionID string) error {
	s.RecordLogoutAsync(userID, ipAddress, sessionID)
	return nil
}
