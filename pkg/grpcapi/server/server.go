package server

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"os"
	"sync"
	"time"

	v1 "github.com/df1-96/experiment/pkg/grpcapi/distcomp/v1"
	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
)

type GRPCServerConfig struct {
	Address            string
	Port               int
	TLSEnabled         bool
	TLSCertFile        string
	TLSKeyFile         string
	TLSCAFile          string
	MaxRecvMsgSize     int
	MaxSendMsgSize     int
	KeepaliveTime      time.Duration
	KeepaliveTimeout   time.Duration
	ConnectionTimeout  time.Duration
	UnaryInterceptors  []grpc.UnaryServerInterceptor
	StreamInterceptors []grpc.StreamServerInterceptor
}

type GRPCServer struct {
	config       GRPCServerConfig
	logger       *zap.Logger
	server       *grpc.Server
	listener     net.Listener
	taskServer   *TaskServer
	computeServer *ComputeServer
	workerServer *WorkerServer
	mu           sync.RWMutex
	running      bool
}

func DefaultGRPCServerConfig() GRPCServerConfig {
	return GRPCServerConfig{
		Address:           "0.0.0.0",
		Port:              50051,
		TLSEnabled:        false,
		MaxRecvMsgSize:    1024 * 1024 * 100,
		MaxSendMsgSize:    1024 * 1024 * 100,
		KeepaliveTime:     2 * time.Hour,
		KeepaliveTimeout:  20 * time.Second,
		ConnectionTimeout: 30 * time.Second,
	}
}

func NewGRPCServer(config GRPCServerConfig, logger *zap.Logger) (*GRPCServer, error) {
	if logger == nil {
		logger = zap.NewNop()
	}

	if config.Address == "" {
		config.Address = "0.0.0.0"
	}
	if config.Port == 0 {
		config.Port = 50051
	}
	if config.MaxRecvMsgSize == 0 {
		config.MaxRecvMsgSize = 1024 * 1024 * 100
	}
	if config.MaxSendMsgSize == 0 {
		config.MaxSendMsgSize = 1024 * 1024 * 100
	}
	if config.KeepaliveTime == 0 {
		config.KeepaliveTime = 2 * time.Hour
	}
	if config.KeepaliveTimeout == 0 {
		config.KeepaliveTimeout = 20 * time.Second
	}
	if config.ConnectionTimeout == 0 {
		config.ConnectionTimeout = 30 * time.Second
	}

	return &GRPCServer{
		config: config,
		logger: logger,
	}, nil
}

func (s *GRPCServer) RegisterTaskServer(server *TaskServer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.taskServer = server
}

func (s *GRPCServer) RegisterComputeServer(server *ComputeServer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.computeServer = server
}

func (s *GRPCServer) RegisterWorkerServer(server *WorkerServer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.workerServer = server
}

func (s *GRPCServer) Start(ctx context.Context) error {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return fmt.Errorf("server is already running")
	}
	s.mu.Unlock()

	addr := fmt.Sprintf("%s:%d", s.config.Address, s.config.Port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", addr, err)
	}
	s.listener = listener

	serverOpts, err := s.buildServerOptions()
	if err != nil {
		listener.Close()
		return fmt.Errorf("failed to build server options: %w", err)
	}

	s.server = grpc.NewServer(serverOpts...)

	s.mu.Lock()
	if s.taskServer != nil {
		v1.RegisterTaskServiceServer(s.server, s.taskServer)
		s.logger.Info("TaskService registered")
	}
	if s.computeServer != nil {
		v1.RegisterComputeServiceServer(s.server, s.computeServer)
		s.logger.Info("ComputeService registered")
	}
	if s.workerServer != nil {
		v1.RegisterWorkerServiceServer(s.server, s.workerServer)
		s.logger.Info("WorkerService registered")
	}
	s.running = true
	s.mu.Unlock()

	s.logger.Info("gRPC server starting", zap.String("address", addr), zap.Bool("tls_enabled", s.config.TLSEnabled))

	errCh := make(chan error, 1)
	go func() {
		errCh <- s.server.Serve(listener)
	}()

	select {
	case <-ctx.Done():
		s.logger.Info("Context cancelled, stopping server")
		s.Stop()
		return ctx.Err()
	case err := <-errCh:
		s.mu.Lock()
		s.running = false
		s.mu.Unlock()
		if err != nil && err != grpc.ErrServerStopped {
			return fmt.Errorf("server error: %w", err)
		}
		return nil
	}
}

func (s *GRPCServer) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.running {
		return
	}

	s.logger.Info("Stopping gRPC server")

	if s.server != nil {
		s.server.GracefulStop()
	}
	if s.listener != nil {
		s.listener.Close()
	}

	s.running = false
	s.logger.Info("gRPC server stopped")
}

func (s *GRPCServer) IsRunning() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.running
}

func (s *GRPCServer) GetListenerAddr() net.Addr {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.listener != nil {
		return s.listener.Addr()
	}
	return nil
}

func (s *GRPCServer) buildServerOptions() ([]grpc.ServerOption, error) {
	opts := []grpc.ServerOption{
		grpc.MaxRecvMsgSize(s.config.MaxRecvMsgSize),
		grpc.MaxSendMsgSize(s.config.MaxSendMsgSize),
		grpc.KeepaliveParams(keepalive.ServerParameters{
			MaxConnectionIdle:     s.config.KeepaliveTime,
			MaxConnectionAge:      s.config.KeepaliveTime,
			MaxConnectionAgeGrace: s.config.KeepaliveTimeout,
			Time:                  s.config.KeepaliveTime,
			Timeout:               s.config.KeepaliveTimeout,
		}),
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			MinTime:             5 * time.Minute,
			PermitWithoutStream: true,
		}),
		grpc.ConnectionTimeout(s.config.ConnectionTimeout),
	}

	if s.config.TLSEnabled {
		creds, err := s.loadTLSCredentials()
		if err != nil {
			return nil, err
		}
		opts = append(opts, grpc.Creds(creds))
		s.logger.Info("TLS credentials loaded")
	} else {
		opts = append(opts, grpc.Creds(insecure.NewCredentials()))
	}

	if len(s.config.UnaryInterceptors) > 0 {
		opts = append(opts, grpc.ChainUnaryInterceptor(s.config.UnaryInterceptors...))
		s.logger.Info("Unary interceptors registered", zap.Int("count", len(s.config.UnaryInterceptors)))
	}

	if len(s.config.StreamInterceptors) > 0 {
		opts = append(opts, grpc.ChainStreamInterceptor(s.config.StreamInterceptors...))
		s.logger.Info("Stream interceptors registered", zap.Int("count", len(s.config.StreamInterceptors)))
	}

	return opts, nil
}

func (s *GRPCServer) loadTLSCredentials() (credentials.TransportCredentials, error) {
	if s.config.TLSCertFile == "" || s.config.TLSKeyFile == "" {
		return nil, fmt.Errorf("TLS cert and key files must be specified when TLS is enabled")
	}

	cert, err := tls.LoadX509KeyPair(s.config.TLSCertFile, s.config.TLSKeyFile)
	if err != nil {
		return nil, fmt.Errorf("failed to load TLS key pair: %w", err)
	}

	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}

	if s.config.TLSCAFile != "" {
		caCert, err := os.ReadFile(s.config.TLSCAFile)
		if err != nil {
			return nil, fmt.Errorf("failed to read CA certificate: %w", err)
		}
		caPool := x509.NewCertPool()
		if !caPool.AppendCertsFromPEM(caCert) {
			return nil, fmt.Errorf("failed to parse CA certificate")
		}
		tlsConfig.ClientCAs = caPool
		tlsConfig.ClientAuth = tls.RequireAndVerifyClientCert
		s.logger.Info("Client certificate verification enabled")
	}

	return credentials.NewTLS(tlsConfig), nil
}

func (s *GRPCServer) GetTaskServer() *TaskServer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.taskServer
}

func (s *GRPCServer) GetComputeServer() *ComputeServer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.computeServer
}

func (s *GRPCServer) GetWorkerServer() *WorkerServer {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.workerServer
}
