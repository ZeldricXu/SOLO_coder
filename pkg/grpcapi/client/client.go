package client

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"os"
	"sync"
	"time"

	"go.uber.org/zap"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
)

type GRPCClientConfig struct {
	Address            string
	TLSEnabled         bool
	TLSCertFile        string
	TLSKeyFile         string
	TLSCAFile          string
	TLSServerName      string
	InsecureSkipVerify bool
	Block              bool
	Timeout            time.Duration
	DialTimeout        time.Duration
	KeepaliveTime      time.Duration
	KeepaliveTimeout   time.Duration
	MaxRecvMsgSize     int
	MaxSendMsgSize     int
	UnaryInterceptors  []grpc.UnaryClientInterceptor
	StreamInterceptors []grpc.StreamClientInterceptor
	RetryPolicy        *RetryPolicy
}

type RetryPolicy struct {
	MaxAttempts       int
	InitialBackoff    time.Duration
	MaxBackoff        time.Duration
	BackoffMultiplier float64
	RetryableStatusCodes []string
}

type GRPCClient struct {
	config      GRPCClientConfig
	logger      *zap.Logger
	conn        *grpc.ClientConn
	mu          sync.RWMutex
	connected   bool
	closeOnce   sync.Once
}

func DefaultGRPCClientConfig() GRPCClientConfig {
	return GRPCClientConfig{
		Address:          "localhost:50051",
		TLSEnabled:       false,
		Timeout:          30 * time.Second,
		DialTimeout:      10 * time.Second,
		KeepaliveTime:    time.Minute,
		KeepaliveTimeout: 20 * time.Second,
		MaxRecvMsgSize:   1024 * 1024 * 100,
		MaxSendMsgSize:   1024 * 1024 * 100,
	}
}

func NewGRPCClient(config GRPCClientConfig, logger *zap.Logger) *GRPCClient {
	if logger == nil {
		logger = zap.NewNop()
	}

	if config.Address == "" {
		config.Address = "localhost:50051"
	}
	if config.Timeout == 0 {
		config.Timeout = 30 * time.Second
	}
	if config.DialTimeout == 0 {
		config.DialTimeout = 10 * time.Second
	}
	if config.KeepaliveTime == 0 {
		config.KeepaliveTime = time.Minute
	}
	if config.KeepaliveTimeout == 0 {
		config.KeepaliveTimeout = 20 * time.Second
	}
	if config.MaxRecvMsgSize == 0 {
		config.MaxRecvMsgSize = 1024 * 1024 * 100
	}
	if config.MaxSendMsgSize == 0 {
		config.MaxSendMsgSize = 1024 * 1024 * 100
	}

	return &GRPCClient{
		config: config,
		logger: logger,
	}
}

func (c *GRPCClient) Connect(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.connected && c.conn != nil {
		return nil
	}

	dialOpts, err := c.buildDialOptions()
	if err != nil {
		return fmt.Errorf("failed to build dial options: %w", err)
	}

	dialCtx, cancel := context.WithTimeout(ctx, c.config.DialTimeout)
	defer cancel()

	conn, err := grpc.DialContext(dialCtx, c.config.Address, dialOpts...)
	if err != nil {
		return fmt.Errorf("failed to dial %s: %w", c.config.Address, err)
	}

	c.conn = conn
	c.connected = true
	c.closeOnce = sync.Once{}

	c.logger.Info("gRPC client connected",
		zap.String("address", c.config.Address),
		zap.Bool("tls_enabled", c.config.TLSEnabled),
	)

	return nil
}

func (c *GRPCClient) Close() error {
	var err error
	c.closeOnce.Do(func() {
		c.mu.Lock()
		defer c.mu.Unlock()

		if c.conn != nil {
			c.logger.Info("Closing gRPC client connection")
			err = c.conn.Close()
			c.conn = nil
		}
		c.connected = false
	})
	return err
}

func (c *GRPCClient) IsConnected() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.connected && c.conn != nil
}

func (c *GRPCClient) GetConn() *grpc.ClientConn {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.conn
}

func (c *GRPCClient) GetConfig() GRPCClientConfig {
	return c.config
}

func (c *GRPCClient) buildDialOptions() ([]grpc.DialOption, error) {
	opts := []grpc.DialOption{
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(c.config.MaxRecvMsgSize),
			grpc.MaxCallSendMsgSize(c.config.MaxSendMsgSize),
		),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Time:                c.config.KeepaliveTime,
			Timeout:             c.config.KeepaliveTimeout,
			PermitWithoutStream: true,
		}),
	}

	if c.config.TLSEnabled {
		creds, err := c.loadTLSCredentials()
		if err != nil {
			return nil, err
		}
		opts = append(opts, grpc.WithTransportCredentials(creds))
		c.logger.Info("TLS credentials loaded for client")
	} else {
		opts = append(opts, grpc.WithTransportCredentials(insecure.NewCredentials()))
	}

	if c.config.Block {
		opts = append(opts, grpc.WithBlock())
	}

	if len(c.config.UnaryInterceptors) > 0 {
		opts = append(opts, grpc.WithChainUnaryInterceptor(c.config.UnaryInterceptors...))
		c.logger.Info("Unary client interceptors registered", zap.Int("count", len(c.config.UnaryInterceptors)))
	}

	if len(c.config.StreamInterceptors) > 0 {
		opts = append(opts, grpc.WithChainStreamInterceptor(c.config.StreamInterceptors...))
		c.logger.Info("Stream client interceptors registered", zap.Int("count", len(c.config.StreamInterceptors)))
	}

	return opts, nil
}

func (c *GRPCClient) loadTLSCredentials() (credentials.TransportCredentials, error) {
	tlsConfig := &tls.Config{
		InsecureSkipVerify: c.config.InsecureSkipVerify,
		MinVersion:         tls.VersionTLS12,
	}

	if c.config.TLSServerName != "" {
		tlsConfig.ServerName = c.config.TLSServerName
	}

	if c.config.TLSCAFile != "" {
		caCert, err := os.ReadFile(c.config.TLSCAFile)
		if err != nil {
			return nil, fmt.Errorf("failed to read CA certificate: %w", err)
		}
		caPool := x509.NewCertPool()
		if !caPool.AppendCertsFromPEM(caCert) {
			return nil, fmt.Errorf("failed to parse CA certificate")
		}
		tlsConfig.RootCAs = caPool
		c.logger.Info("CA certificate loaded")
	}

	if c.config.TLSCertFile != "" && c.config.TLSKeyFile != "" {
		cert, err := tls.LoadX509KeyPair(c.config.TLSCertFile, c.config.TLSKeyFile)
		if err != nil {
			return nil, fmt.Errorf("failed to load client key pair: %w", err)
		}
		tlsConfig.Certificates = []tls.Certificate{cert}
		c.logger.Info("Client certificate loaded")
	}

	return credentials.NewTLS(tlsConfig), nil
}

func (c *GRPCClient) NewContext(ctx context.Context) (context.Context, context.CancelFunc) {
	if c.config.Timeout > 0 {
		return context.WithTimeout(ctx, c.config.Timeout)
	}
	return context.WithCancel(ctx)
}

func TimeoutInterceptor(timeout time.Duration) grpc.UnaryClientInterceptor {
	return func(ctx context.Context, method string, req, reply interface{}, cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
		if _, ok := ctx.Deadline(); !ok && timeout > 0 {
			var cancel context.CancelFunc
			ctx, cancel = context.WithTimeout(ctx, timeout)
			defer cancel()
		}
		return invoker(ctx, method, req, reply, cc, opts...)
	}
}

func LoggingInterceptor(logger *zap.Logger) grpc.UnaryClientInterceptor {
	if logger == nil {
		logger = zap.NewNop()
	}
	return func(ctx context.Context, method string, req, reply interface{}, cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
		start := time.Now()
		err := invoker(ctx, method, req, reply, cc, opts...)
		duration := time.Since(start)

		if err != nil {
			logger.Error("gRPC call failed",
				zap.String("method", method),
				zap.Duration("duration", duration),
				zap.Error(err),
			)
		} else {
			logger.Debug("gRPC call completed",
				zap.String("method", method),
				zap.Duration("duration", duration),
			)
		}
		return err
	}
}

func StreamLoggingInterceptor(logger *zap.Logger) grpc.StreamClientInterceptor {
	if logger == nil {
		logger = zap.NewNop()
	}
	return func(ctx context.Context, desc *grpc.StreamDesc, cc *grpc.ClientConn, method string, streamer grpc.Streamer, opts ...grpc.CallOption) (grpc.ClientStream, error) {
		logger.Debug("gRPC stream started", zap.String("method", method))

		stream, err := streamer(ctx, desc, cc, method, opts...)
		if err != nil {
			logger.Error("gRPC stream failed to start",
				zap.String("method", method),
				zap.Error(err),
			)
			return nil, err
		}

		return &loggedClientStream{
			ClientStream: stream,
			method:       method,
			logger:       logger,
			startTime:    time.Now(),
		}, nil
	}
}

type loggedClientStream struct {
	grpc.ClientStream
	method    string
	logger    *zap.Logger
	startTime time.Time
}

func (s *loggedClientStream) SendMsg(m interface{}) error {
	err := s.ClientStream.SendMsg(m)
	if err != nil {
		s.logger.Debug("gRPC stream send failed",
			zap.String("method", s.method),
			zap.Error(err),
		)
	}
	return err
}

func (s *loggedClientStream) RecvMsg(m interface{}) error {
	err := s.ClientStream.RecvMsg(m)
	if err != nil {
		if err.Error() != "EOF" {
			s.logger.Debug("gRPC stream recv failed",
				zap.String("method", s.method),
				zap.Duration("duration", time.Since(s.startTime)),
				zap.Error(err),
			)
		}
	}
	return err
}
