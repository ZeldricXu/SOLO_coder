package app

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"sync"

	"go.uber.org/zap"

	"github.com/df1-96/experiment/internal/apiserver"
	"github.com/df1-96/experiment/internal/config"
	"github.com/df1-96/experiment/internal/storage"
	"github.com/df1-96/experiment/pkg/util"
)

type APIApp struct {
	cfg       *config.Config
	db        *storage.DB
	apiServer *apiserver.Server
	logger    *zap.Logger
	mu        sync.Mutex
	running   bool
}

func NewAPIApp(cfg *config.Config) (*APIApp, error) {
	if cfg == nil {
		return nil, fmt.Errorf("config is required")
	}

	logger := util.With(zap.String("component", "api-app"))

	return &APIApp{
		cfg:    cfg,
		logger: logger,
	}, nil
}

func (app *APIApp) Start(ctx context.Context) error {
	app.mu.Lock()
	if app.running {
		app.mu.Unlock()
		return fmt.Errorf("api app is already running")
	}
	app.running = true
	app.mu.Unlock()

	var err error

	app.logger.Info("Initializing database connection")
	app.db, err = storage.NewDB(&app.cfg.Database)
	if err != nil {
		return fmt.Errorf("failed to initialize database: %w", err)
	}

	if err := app.db.AutoMigrate(); err != nil {
		app.logger.Warn("Failed to auto-migrate database schema", zap.Error(err))
	}

	app.logger.Info("Initializing API server", zap.Int("port", app.cfg.Server.HTTPPort))
	app.apiServer = apiserver.NewServer(app.cfg, app.db.DB)

	go app.runHTTPServer(ctx)

	app.logger.Info("API app started successfully")
	return nil
}

func (app *APIApp) runHTTPServer(ctx context.Context) {
	addr := ":" + strconv.Itoa(app.cfg.Server.HTTPPort)

	server := &http.Server{
		Addr:         addr,
		Handler:      app.apiServer.Router(),
		ReadTimeout:  30 * 1000000000,
		WriteTimeout: 30 * 1000000000,
		IdleTimeout:  120 * 1000000000,
	}

	go func() {
		app.logger.Info("HTTP server starting", zap.String("addr", addr))
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			app.logger.Error("HTTP server error", zap.Error(err))
		}
	}()

	<-ctx.Done()

	app.logger.Info("Shutting down HTTP server...")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*1000000000)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		app.logger.Error("Server forced to shutdown", zap.Error(err))
	}
}

func (app *APIApp) Stop() error {
	app.mu.Lock()
	if !app.running {
		app.mu.Unlock()
		return nil
	}
	app.running = false
	app.mu.Unlock()

	app.logger.Info("Stopping API app")

	if app.db != nil {
		if err := app.db.Close(); err != nil {
			app.logger.Warn("Failed to close database connection", zap.Error(err))
		}
	}

	if err := util.Sync(); err != nil {
		app.logger.Warn("Failed to sync logger", zap.Error(err))
	}

	app.logger.Info("API app stopped")
	return nil
}

func (app *APIApp) GetDB() *storage.DB {
	return app.db
}

func (app *APIApp) GetAPIServer() *apiserver.Server {
	return app.apiServer
}
