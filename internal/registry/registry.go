package registry

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/distributed-task-scheduler/internal/config"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/google/uuid"
)

type Registry struct {
	db     *storage.Database
	redis  *storage.RedisClient
	cfg    config.RegistryConfig
	ctx    context.Context
	cancel context.CancelFunc
}

type WorkerRegistration struct {
	ID           string   `json:"id"`
	Namespace    string   `json:"namespace"`
	Hostname     string   `json:"hostname"`
	GRPCAddr     string   `json:"grpc_addr"`
	HTTPAddr     string   `json:"http_addr"`
	Capabilities []string `json:"capabilities"`
	MaxLoad      int      `json:"max_load"`
}

type HealthCheckResult struct {
	WorkerID  string    `json:"worker_id"`
	Healthy   bool      `json:"healthy"`
	Load      int       `json:"load"`
	Timestamp time.Time `json:"timestamp"`
}

func NewRegistry(db *storage.Database, redis *storage.RedisClient, cfg config.RegistryConfig) *Registry {
	ctx, cancel := context.WithCancel(context.Background())
	return &Registry{
		db:     db,
		redis:  redis,
		cfg:    cfg,
		ctx:    ctx,
		cancel: cancel,
	}
}

func (r *Registry) Register(reg WorkerRegistration) (*models.Worker, error) {
	if reg.ID == "" {
		reg.ID = uuid.New().String()
	}

	worker := &models.Worker{
		ID:             reg.ID,
		Namespace:      reg.Namespace,
		Hostname:       reg.Hostname,
		GRPCAddr:       reg.GRPCAddr,
		HTTPAddr:       reg.HTTPAddr,
		Status:         models.WorkerStatusHealthy,
		LastHeartbeat:  time.Now(),
		RegisteredAt:   time.Now(),
		UnhealthyCount: 0,
		Capabilities:   reg.Capabilities,
		CurrentLoad:    0,
		MaxLoad:        reg.MaxLoad,
	}

	query := `
		INSERT INTO workers (id, namespace, hostname, grpc_addr, http_addr, status, 
			last_heartbeat, registered_at, unhealthy_count, capabilities, current_load, max_load)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
		ON CONFLICT (id) DO UPDATE SET
			hostname = EXCLUDED.hostname,
			grpc_addr = EXCLUDED.grpc_addr,
			http_addr = EXCLUDED.http_addr,
			status = EXCLUDED.status,
			last_heartbeat = EXCLUDED.last_heartbeat,
			capabilities = EXCLUDED.capabilities,
			max_load = EXCLUDED.max_load
	`

	_, err := r.db.Exec(query,
		worker.ID, worker.Namespace, worker.Hostname, worker.GRPCAddr, worker.HTTPAddr,
		worker.Status, worker.LastHeartbeat, worker.RegisteredAt, worker.UnhealthyCount,
		worker.Capabilities, worker.CurrentLoad, worker.MaxLoad)
	if err != nil {
		return nil, fmt.Errorf("failed to register worker: %w", err)
	}

	workerJSON, _ := json.Marshal(worker)
	key := fmt.Sprintf("workers:%s", worker.ID)
	r.redis.Set(r.ctx, key, workerJSON, 5*time.Minute)

	return worker, nil
}

func (r *Registry) Deregister(workerID string) error {
	query := `DELETE FROM workers WHERE id = $1`
	_, err := r.db.Exec(query, workerID)
	if err != nil {
		return fmt.Errorf("failed to deregister worker: %w", err)
	}

	key := fmt.Sprintf("workers:%s", workerID)
	r.redis.Del(r.ctx, key)

	return nil
}

func (r *Registry) Heartbeat(workerID string, load int) error {
	query := `
		UPDATE workers 
		SET last_heartbeat = NOW(), status = 'healthy', unhealthy_count = 0, current_load = $2
		WHERE id = $1
	`
	result, err := r.db.Exec(query, workerID, load)
	if err != nil {
		return fmt.Errorf("failed to update heartbeat: %w", err)
	}

	rows, _ := result.RowsAffected()
	if rows == 0 {
		return fmt.Errorf("worker not found: %s", workerID)
	}

	key := fmt.Sprintf("workers:%s", workerID)
	worker, err := r.GetWorker(workerID)
	if err == nil {
		workerJSON, _ := json.Marshal(worker)
		r.redis.Set(r.ctx, key, workerJSON, 5*time.Minute)
	}

	return nil
}

func (r *Registry) GetWorker(workerID string) (*models.Worker, error) {
	var worker models.Worker
	query := `SELECT * FROM workers WHERE id = $1`
	err := r.db.Get(&worker, query, workerID)
	if err != nil {
		return nil, fmt.Errorf("failed to get worker: %w", err)
	}
	return &worker, nil
}

func (r *Registry) ListWorkers(namespace string) ([]models.Worker, error) {
	var workers []models.Worker
	var query string
	var args []interface{}

	if namespace != "" {
		query = `SELECT * FROM workers WHERE namespace = $1 ORDER BY namespace, id`
		args = append(args, namespace)
	} else {
		query = `SELECT * FROM workers ORDER BY namespace, id`
	}

	err := r.db.Select(&workers, query, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to list workers: %w", err)
	}
	return workers, nil
}

func (r *Registry) ListHealthyWorkers(namespace string) ([]models.Worker, error) {
	var workers []models.Worker
	var query string
	var args []interface{}

	if namespace != "" {
		query = `SELECT * FROM workers WHERE namespace = $1 AND status = 'healthy' ORDER BY current_load ASC`
		args = append(args, namespace)
	} else {
		query = `SELECT * FROM workers WHERE status = 'healthy' ORDER BY current_load ASC`
	}

	err := r.db.Select(&workers, query, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to list healthy workers: %w", err)
	}
	return workers, nil
}

func (r *Registry) GetLeastLoadedWorker(namespace string, capabilities []string) (*models.Worker, error) {
	workers, err := r.ListHealthyWorkers(namespace)
	if err != nil {
		return nil, err
	}

	if len(workers) == 0 {
		return nil, fmt.Errorf("no healthy workers available")
	}

	var best *models.Worker
	for i := range workers {
		worker := workers[i]
		hasAllCaps := true
		for _, cap := range capabilities {
			found := false
			for _, wc := range worker.Capabilities {
				if wc == cap {
					found = true
					break
				}
			}
			if !found {
				hasAllCaps = false
				break
			}
		}
		if hasAllCaps && worker.CurrentLoad < worker.MaxLoad {
			if best == nil || worker.CurrentLoad < best.CurrentLoad {
				best = &worker
			}
		}
	}

	if best == nil {
		return nil, fmt.Errorf("no suitable worker available")
	}

	return best, nil
}

func (r *Registry) HealthCheckLoop() {
	ticker := time.NewTicker(r.cfg.HealthCheckInterval)
	defer ticker.Stop()

	for {
		select {
		case <-r.ctx.Done():
			return
		case <-ticker.C:
			r.checkHealth()
		}
	}
}

func (r *Registry) checkHealth() {
	query := `
		UPDATE workers 
		SET unhealthy_count = unhealthy_count + 1,
			status = CASE 
				WHEN unhealthy_count + 1 >= $1 THEN 'unhealthy'
				ELSE status
			END
		WHERE last_heartbeat < NOW() - $2::interval
	`
	_, err := r.db.Exec(query, r.cfg.UnhealthyThreshold, r.cfg.HealthCheckInterval.String())
	if err != nil {
		fmt.Printf("Failed to update health status: %v\n", err)
	}

	removeQuery := `
		DELETE FROM workers 
		WHERE status = 'unhealthy' AND last_heartbeat < NOW() - $1::interval
	`
	_, err = r.db.Exec(removeQuery, r.cfg.AutoRemoveInterval.String())
	if err != nil {
		fmt.Printf("Failed to remove unhealthy workers: %v\n", err)
	}
}

func (r *Registry) Start() {
	go r.HealthCheckLoop()
}

func (r *Registry) Stop() {
	r.cancel()
}

func (r *Registry) UpdateWorkerLoad(workerID string, loadDelta int) error {
	query := `
		UPDATE workers 
		SET current_load = current_load + $2
		WHERE id = $1
	`
	_, err := r.db.Exec(query, workerID, loadDelta)
	return err
}
