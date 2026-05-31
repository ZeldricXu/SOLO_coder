package dataaccess

import (
	"context"
	"fmt"
	"sync"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	gormlogger "gorm.io/gorm/logger"
	"session172/internal/config"
	applogger "session172/internal/logger"
	"session172/pkg/utils"
)

const (
	defaultMaxOpenConns    = 20
	defaultMaxIdleConns    = 10
	defaultConnMaxLifetime = time.Hour
	defaultConnMaxIdleTime = 30 * time.Minute
	defaultSlowThreshold   = 200 * time.Millisecond
	defaultAcquireTimeout  = 5 * time.Second
	defaultRetryAttempts   = 3
	defaultRetryInterval   = 100 * time.Millisecond
	queryCacheMaxSize      = 1000
	slowQueryMaxRecords    = 100
)

var (
	ErrPoolNotInitialized = fmt.Errorf("connection pool not initialized")
	ErrAcquireTimeout     = fmt.Errorf("timeout acquiring connection")
)

type (
	PoolConfig struct {
		DSN             string
		MaxOpenConns    int
		MaxIdleConns    int
		ConnMaxLifetime time.Duration
		ConnMaxIdleTime time.Duration
		SlowThreshold   time.Duration
	}

	PoolStats struct {
		TotalConns        int64
		IdleConns         int64
		ActiveConns       int64
		WaitCount         int64
		WaitDuration      time.Duration
		MaxIdleClosed     int64
		MaxLifetimeClosed int64
	}

	SlowQuery struct {
		SQL          string
		Duration     time.Duration
		Timestamp    time.Time
		RowsAffected int64
	}

	QueryOptimizer struct {
		enabled     bool
		cacheSize   int
		queryCache  *sync.Map
		slowQueries []SlowQuery
		mu          sync.Mutex
	}

	ConnectionPool struct {
		mu        sync.RWMutex
		db        *gorm.DB
		config    *PoolConfig
		stats     *PoolStats
		available chan *gorm.DB
		createdAt time.Time
	}
)

var (
	poolInstance *ConnectionPool
	poolOnce     sync.Once
)

func NewPoolConfig() *PoolConfig {
	cfg := config.GetManager()
	return &PoolConfig{
		DSN:             cfg.GetString("database.dsn"),
		MaxOpenConns:    cfg.GetInt("database.max_open_conns"),
		MaxIdleConns:    cfg.GetInt("database.max_idle_conns"),
		ConnMaxLifetime: cfg.GetDuration("database.conn_max_lifetime"),
		ConnMaxIdleTime: cfg.GetDuration("database.conn_max_idle_time"),
		SlowThreshold:   cfg.GetDuration("database.slow_threshold"),
	}
}

func (c *PoolConfig) applyDefaults() {
	if c.MaxOpenConns == 0 {
		c.MaxOpenConns = defaultMaxOpenConns
	}
	if c.MaxIdleConns == 0 {
		c.MaxIdleConns = defaultMaxIdleConns
	}
	if c.ConnMaxLifetime == 0 {
		c.ConnMaxLifetime = defaultConnMaxLifetime
	}
	if c.ConnMaxIdleTime == 0 {
		c.ConnMaxIdleTime = defaultConnMaxIdleTime
	}
	if c.SlowThreshold == 0 {
		c.SlowThreshold = defaultSlowThreshold
	}
}

func NewConnectionPool(cfg *PoolConfig) (*ConnectionPool, error) {
	cfg.applyDefaults()

	db, err := gorm.Open(postgres.Open(cfg.DSN), &gorm.Config{
		Logger:      gormlogger.Default.LogMode(gormlogger.Warn),
		PrepareStmt: true,
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("failed to get sql DB: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.MaxIdleConns)
	sqlDB.SetConnMaxLifetime(cfg.ConnMaxLifetime)
	sqlDB.SetConnMaxIdleTime(cfg.ConnMaxIdleTime)

	pool := &ConnectionPool{
		db:        db,
		config:    cfg,
		stats:     &PoolStats{},
		available: make(chan *gorm.DB, cfg.MaxOpenConns),
		createdAt: time.Now(),
	}

	for i := 0; i < cfg.MaxIdleConns; i++ {
		pool.available <- db
	}

	poolInstance = pool
	return pool, nil
}

func GetPool() *ConnectionPool {
	poolOnce.Do(func() {
		if poolInstance == nil {
			cfg := NewPoolConfig()
			pool, err := NewConnectionPool(cfg)
			if err != nil {
				applogger.Errorf("Failed to create connection pool: %v", err)
			}
			poolInstance = pool
		}
	})
	return poolInstance
}

func (p *ConnectionPool) Get(ctx context.Context) (*gorm.DB, error) {
	start := time.Now()
	select {
	case db := <-p.available:
		p.updateStatsOnAcquire()
		return db.WithContext(ctx), nil
	case <-ctx.Done():
		p.updateStatsOnWait(start)
		return nil, ctx.Err()
	case <-time.After(defaultAcquireTimeout):
		p.updateStatsOnWait(start)
		return p.db.WithContext(ctx), nil
	}
}

func (p *ConnectionPool) updateStatsOnAcquire() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.stats.ActiveConns++
	p.stats.IdleConns--
}

func (p *ConnectionPool) updateStatsOnWait(start time.Time) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.stats.WaitCount++
	p.stats.WaitDuration += time.Since(start)
}

func (p *ConnectionPool) Put(db *gorm.DB) {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.stats.ActiveConns--
	p.stats.IdleConns++

	select {
	case p.available <- db:
	default:
		p.stats.MaxIdleClosed++
	}
}

func (p *ConnectionPool) GetDB() *gorm.DB {
	return p.db
}

func (p *ConnectionPool) Stats() PoolStats {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return *p.stats
}

func (p *ConnectionPool) HealthCheck() error {
	sqlDB, err := p.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Ping()
}

func (p *ConnectionPool) Close() error {
	sqlDB, err := p.db.DB()
	if err != nil {
		return err
	}
	return sqlDB.Close()
}

func (p *ConnectionPool) Transaction(ctx context.Context, fn func(tx *gorm.DB) error) error {
	db, err := p.Get(ctx)
	if err != nil {
		return err
	}
	defer p.Put(db)
	return db.Transaction(fn)
}

func NewQueryOptimizer() *QueryOptimizer {
	cfg := config.GetManager()
	return &QueryOptimizer{
		enabled:    cfg.GetBool("query_optimizer.enabled"),
		cacheSize:  cfg.GetInt("query_optimizer.cache_size"),
		queryCache: &sync.Map{},
	}
}

func (qo *QueryOptimizer) Optimize(db *gorm.DB, sql string, args ...interface{}) *gorm.DB {
	if !qo.enabled {
		return db
	}

	cacheKey := fmt.Sprintf("%s:%v", sql, args)
	if cached, ok := qo.queryCache.Load(cacheKey); ok {
		if _, ok := cached.(*gorm.DB); ok {
			return db
		}
	}

	start := time.Now()
	result := db.Raw(sql, args...)
	duration := time.Since(start)

	if duration > qo.getSlowThreshold() {
		qo.recordSlowQuery(sql, duration, result.RowsAffected)
	}

	if qo.cacheSize > 0 {
		qo.queryCache.Store(cacheKey, result)
	}

	return result
}

func (qo *QueryOptimizer) getSlowThreshold() time.Duration {
	if poolInstance != nil {
		return poolInstance.config.SlowThreshold
	}
	return defaultSlowThreshold
}

func (qo *QueryOptimizer) recordSlowQuery(sql string, duration time.Duration, rowsAffected int64) {
	qo.mu.Lock()
	defer qo.mu.Unlock()

	qo.slowQueries = append(qo.slowQueries, SlowQuery{
		SQL:          sql,
		Duration:     duration,
		Timestamp:    time.Now(),
		RowsAffected: rowsAffected,
	})

	if len(qo.slowQueries) > slowQueryMaxRecords {
		qo.slowQueries = qo.slowQueries[1:]
	}
}

func (qo *QueryOptimizer) GetSlowQueries() []SlowQuery {
	qo.mu.Lock()
	defer qo.mu.Unlock()
	return qo.slowQueries
}

func (qo *QueryOptimizer) ClearCache() {
	qo.queryCache = &sync.Map{}
}

func (qo *QueryOptimizer) CacheStats() int {
	count := 0
	qo.queryCache.Range(func(key, value interface{}) bool {
		count++
		return true
	})
	return count
}

func WithRetry(ctx context.Context, fn func(*gorm.DB) error) error {
	return WithRetryAttempts(ctx, defaultRetryAttempts, fn)
}

func WithRetryAttempts(ctx context.Context, attempts int, fn func(*gorm.DB) error) error {
	pool := GetPool()
	if pool == nil {
		return ErrPoolNotInitialized
	}

	return utils.Retry(attempts, defaultRetryInterval, func() error {
		db, err := pool.Get(ctx)
		if err != nil {
			return err
		}
		defer pool.Put(db)
		return fn(db)
	})
}
