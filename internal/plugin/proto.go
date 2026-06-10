package plugin

import (
	"context"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

type StageType int32

const (
	StageTypeUnspecified StageType = 0
	StageTypeScan        StageType = 1
	StageTypeBuild       StageType = 2
	StageTypeTest        StageType = 3
	StageTypeDeploy      StageType = 4
	StageTypeCustom      StageType = 5
)

type StageStatus int32

const (
	StageStatusUnspecified StageStatus = 0
	StageStatusPending     StageStatus = 1
	StageStatusRunning     StageStatus = 2
	StageStatusSuccess     StageStatus = 3
	StageStatusFailed      StageStatus = 4
	StageStatusCancelled   StageStatus = 5
	StageStatusTimeout     StageStatus = 6
)

type ExecutionMode int32

const (
	ExecutionModeUnspecified ExecutionMode = 0
	ExecutionModeSync        ExecutionMode = 1
	ExecutionModeAsync       ExecutionMode = 2
)

type PluginInfo struct {
	Name               string            `json:"name"`
	Version            string            `json:"version"`
	Description        string            `json:"description"`
	Type               StageType         `json:"type"`
	Author             string            `json:"author"`
	Tags               []string          `json:"tags"`
	ConfigSchema       map[string]string `json:"config_schema"`
	ExecutionMode      ExecutionMode     `json:"execution_mode"`
	PollIntervalSeconds int32             `json:"poll_interval_seconds"`
}

type StageContext struct {
	ExecutionID   string            `json:"execution_id"`
	PipelineID    string            `json:"pipeline_id"`
	StageName     string            `json:"stage_name"`
	StageType     StageType         `json:"stage_type"`
	WorkingDir    string            `json:"working_dir"`
	Env           map[string]string `json:"env"`
	Variables     map[string]string `json:"variables"`
	Secrets       map[string][]byte `json:"-"`
	PluginConfig  map[string]string `json:"plugin_config"`
	Commands      []string          `json:"commands"`
	TimeoutSecs   int64             `json:"timeout_seconds"`
	Attempt       int32             `json:"attempt"`
	MaxAttempts   int32             `json:"max_attempts"`
}

type LogEntry struct {
	Timestamp int64  `json:"timestamp"`
	Level     string `json:"level"`
	Message   string `json:"message"`
	Stream    string `json:"stream"`
}

type Artifact struct {
	Name        string `json:"name"`
	Path        string `json:"path"`
	Size        int64  `json:"size"`
	ContentType string `json:"content_type"`
	Content     []byte `json:"-"`
}

type ExecuteResponse struct {
	Status     StageStatus            `json:"status"`
	ExitCode   int32                  `json:"exit_code"`
	Error      string                 `json:"error,omitempty"`
	Logs       []*LogEntry            `json:"logs,omitempty"`
	Artifacts  []*Artifact            `json:"artifacts,omitempty"`
	Output     map[string]string      `json:"output,omitempty"`
	DurationMs int64                  `json:"duration_ms"`
}

type HealthCheckResponse struct {
	Healthy        bool   `json:"healthy"`
	Message        string `json:"message"`
	ResponseTimeMs int64  `json:"response_time_ms"`
}

type StagePluginClient interface {
	GetPluginInfo(ctx context.Context, name string) (*PluginInfo, error)
	HealthCheck(ctx context.Context, name string) (*HealthCheckResponse, error)
	Execute(ctx context.Context, req *StageContext, logCallback func(*LogEntry)) (*ExecuteResponse, error)
	Cancel(ctx context.Context, req *StageContext) (*ExecuteResponse, error)
	StartExecution(ctx context.Context, req *StageContext) (*StartExecutionResponse, error)
	PollStatus(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error)
	CancelExecution(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error)
	GetExecutionMode(ctx context.Context, name string) (ExecutionMode, error)
	Close() error
}

type stagePluginClient struct {
	conn   *grpc.ClientConn
	client StagePluginServiceClient
	mu     sync.Mutex
}

type StagePluginServiceClient interface {
	GetPluginInfo(ctx context.Context, in *HealthCheckRequest, opts ...grpc.CallOption) (*PluginInfo, error)
	HealthCheck(ctx context.Context, in *HealthCheckRequest, opts ...grpc.CallOption) (*HealthCheckResponse, error)
	Execute(ctx context.Context, in *ExecuteRequest, opts ...grpc.CallOption) (StagePlugin_ExecuteClient, error)
	Cancel(ctx context.Context, in *ExecuteRequest, opts ...grpc.CallOption) (*ExecuteResponse, error)
	StartExecution(ctx context.Context, in *StartExecutionRequest, opts ...grpc.CallOption) (*StartExecutionResponse, error)
	PollStatus(ctx context.Context, in *PollStatusRequest, opts ...grpc.CallOption) (*PollStatusResponse, error)
	CancelExecution(ctx context.Context, in *PollStatusRequest, opts ...grpc.CallOption) (*PollStatusResponse, error)
}

type StagePlugin_ExecuteClient interface {
	Recv() (*ExecuteResponse, error)
	grpc.ClientStream
}

type HealthCheckRequest struct {
	PluginName string `json:"plugin_name"`
}

type ExecuteRequest struct {
	Context *StageContext `json:"context"`
}

type StartExecutionRequest struct {
	Context *StageContext `json:"context"`
}

type StartExecutionResponse struct {
	ExecutionID       string      `json:"execution_id"`
	Status            StageStatus `json:"status"`
	Message           string      `json:"message"`
	PollIntervalSeconds int32     `json:"poll_interval_seconds"`
}

type PollStatusRequest struct {
	PluginName  string        `json:"plugin_name"`
	ExecutionID string        `json:"execution_id"`
	Context     *StageContext `json:"context,omitempty"`
}

type PollStatusResponse struct {
	ExecutionID    string            `json:"execution_id"`
	Status         StageStatus       `json:"status"`
	Error          string            `json:"error,omitempty"`
	Logs           []*LogEntry       `json:"logs,omitempty"`
	Artifacts      []*Artifact       `json:"artifacts,omitempty"`
	Output         map[string]string `json:"output,omitempty"`
	DurationMs     int64             `json:"duration_ms"`
	Completed      bool              `json:"completed"`
	ProgressPercent int32            `json:"progress_percent"`
}

func NewStagePluginClient(address string) (StagePluginClient, error) {
	conn, err := grpc.Dial(address,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(1024*1024*100)),
		grpc.WithBlock(),
		grpc.WithTimeout(10*time.Second),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to plugin: %w", err)
	}

	return &stagePluginClient{
		conn:   conn,
		client: newGRPCClient(conn),
	}, nil
}

func (c *stagePluginClient) GetPluginInfo(ctx context.Context, name string) (*PluginInfo, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.GetPluginInfo(ctx, &HealthCheckRequest{PluginName: name})
}

func (c *stagePluginClient) HealthCheck(ctx context.Context, name string) (*HealthCheckResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.HealthCheck(ctx, &HealthCheckRequest{PluginName: name})
}

func (c *stagePluginClient) Execute(ctx context.Context, req *StageContext, logCallback func(*LogEntry)) (*ExecuteResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	stream, err := c.client.Execute(ctx, &ExecuteRequest{Context: req})
	if err != nil {
		return nil, fmt.Errorf("failed to start execution: %w", err)
	}

	var finalResp *ExecuteResponse
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		default:
		}

		resp, err := stream.Recv()
		if err != nil {
			if status.Code(err) == codes.Canceled {
				return finalResp, nil
			}
			return nil, fmt.Errorf("stream error: %w", err)
		}

		for _, log := range resp.Logs {
			if logCallback != nil {
				logCallback(log)
			}
		}

		if resp.Status != StageStatusRunning && resp.Status != StageStatusUnspecified {
			finalResp = resp
			break
		}

		if len(resp.Logs) > 0 {
			finalResp = resp
		}
	}

	return finalResp, nil
}

func (c *stagePluginClient) Cancel(ctx context.Context, req *StageContext) (*ExecuteResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.Cancel(ctx, &ExecuteRequest{Context: req})
}

func (c *stagePluginClient) StartExecution(ctx context.Context, req *StageContext) (*StartExecutionResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.StartExecution(ctx, &StartExecutionRequest{Context: req})
}

func (c *stagePluginClient) PollStatus(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.PollStatus(ctx, &PollStatusRequest{
		PluginName:  pluginName,
		ExecutionID: executionID,
		Context:     context,
	})
}

func (c *stagePluginClient) CancelExecution(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.client.CancelExecution(ctx, &PollStatusRequest{
		PluginName:  pluginName,
		ExecutionID: executionID,
		Context:     context,
	})
}

func (c *stagePluginClient) GetExecutionMode(ctx context.Context, name string) (ExecutionMode, error) {
	info, err := c.GetPluginInfo(ctx, name)
	if err != nil {
		return ExecutionModeSync, err
	}
	if info.ExecutionMode == ExecutionModeUnspecified {
		return ExecutionModeSync, nil
	}
	return info.ExecutionMode, nil
}

func (c *stagePluginClient) Close() error {
	if c.conn != nil {
		return c.conn.Close()
	}
	return nil
}

type StagePluginServer interface {
	OnGetPluginInfo(func(ctx context.Context, name string) (*PluginInfo, error))
	OnHealthCheck(func(ctx context.Context, name string) (*HealthCheckResponse, error))
	OnExecute(func(ctx context.Context, req *StageContext, sendLog func(*LogEntry)) (*ExecuteResponse, error))
	OnCancel(func(ctx context.Context, req *StageContext) (*ExecuteResponse, error))
	OnStartExecution(func(ctx context.Context, req *StageContext) (*StartExecutionResponse, error))
	OnPollStatus(func(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error))
	OnCancelExecution(func(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error))
	Serve(lis net.Listener) error
	Stop()
}

type stagePluginServer struct {
	server          *grpc.Server
	getPluginInfo   func(context.Context, string) (*PluginInfo, error)
	healthCheck     func(context.Context, string) (*HealthCheckResponse, error)
	execute         func(context.Context, *StageContext, func(*LogEntry)) (*ExecuteResponse, error)
	cancel          func(context.Context, *StageContext) (*ExecuteResponse, error)
	startExecution  func(context.Context, *StageContext) (*StartExecutionResponse, error)
	pollStatus      func(context.Context, string, string, *StageContext) (*PollStatusResponse, error)
	cancelExecution func(context.Context, string, string, *StageContext) (*PollStatusResponse, error)
}

func NewStagePluginServer() StagePluginServer {
	return &stagePluginServer{
		server: grpc.NewServer(
			grpc.MaxRecvMsgSize(1024 * 1024 * 100),
			grpc.MaxSendMsgSize(1024 * 1024 * 100),
		),
	}
}

func (s *stagePluginServer) OnGetPluginInfo(fn func(ctx context.Context, name string) (*PluginInfo, error)) {
	s.getPluginInfo = fn
}

func (s *stagePluginServer) OnHealthCheck(fn func(ctx context.Context, name string) (*HealthCheckResponse, error)) {
	s.healthCheck = fn
}

func (s *stagePluginServer) OnExecute(fn func(ctx context.Context, req *StageContext, sendLog func(*LogEntry)) (*ExecuteResponse, error)) {
	s.execute = fn
}

func (s *stagePluginServer) OnCancel(fn func(ctx context.Context, req *StageContext) (*ExecuteResponse, error)) {
	s.cancel = fn
}

func (s *stagePluginServer) OnStartExecution(fn func(ctx context.Context, req *StageContext) (*StartExecutionResponse, error)) {
	s.startExecution = fn
}

func (s *stagePluginServer) OnPollStatus(fn func(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error)) {
	s.pollStatus = fn
}

func (s *stagePluginServer) OnCancelExecution(fn func(ctx context.Context, pluginName, executionID string, context *StageContext) (*PollStatusResponse, error)) {
	s.cancelExecution = fn
}

func (s *stagePluginServer) Serve(lis net.Listener) error {
	RegisterStagePluginServiceServer(s.server, &grpcServerImpl{s: s})
	return s.server.Serve(lis)
}

func (s *stagePluginServer) Stop() {
	s.server.Stop()
}

func ConvertStageType(t types.StageType) StageType {
	switch t {
	case types.StageTypeScan:
		return StageTypeScan
	case types.StageTypeBuild:
		return StageTypeBuild
	case types.StageTypeTest:
		return StageTypeTest
	case types.StageTypeDeploy:
		return StageTypeDeploy
	case types.StageTypeCustom:
		return StageTypeCustom
	default:
		return StageTypeUnspecified
	}
}

func ConvertStageStatus(s types.StageStatus) StageStatus {
	switch s {
	case types.StageStatusPending:
		return StageStatusPending
	case types.StageStatusRunning:
		return StageStatusRunning
	case types.StageStatusSuccess:
		return StageStatusSuccess
	case types.StageStatusFailed:
		return StageStatusFailed
	case types.StageStatusCancelled:
		return StageStatusCancelled
	case types.StageStatusTimeout:
		return StageStatusTimeout
	default:
		return StageStatusUnspecified
	}
}

func ConvertFromStageStatus(s StageStatus) types.StageStatus {
	switch s {
	case StageStatusPending:
		return types.StageStatusPending
	case StageStatusRunning:
		return types.StageStatusRunning
	case StageStatusSuccess:
		return types.StageStatusSuccess
	case StageStatusFailed:
		return types.StageStatusFailed
	case StageStatusCancelled:
		return types.StageStatusCancelled
	case StageStatusTimeout:
		return types.StageStatusTimeout
	default:
		return types.StageStatusPending
	}
}

var _ StagePluginServiceServer = (*grpcServerImpl)(nil)

type grpcServerImpl struct {
	UnimplementedStagePluginServiceServer
	s *stagePluginServer
}

func (g *grpcServerImpl) GetPluginInfo(ctx context.Context, req *HealthCheckRequest) (*PluginInfo, error) {
	if g.s.getPluginInfo == nil {
		return nil, status.Error(codes.Unimplemented, "GetPluginInfo not implemented")
	}
	return g.s.getPluginInfo(ctx, req.PluginName)
}

func (g *grpcServerImpl) HealthCheck(ctx context.Context, req *HealthCheckRequest) (*HealthCheckResponse, error) {
	if g.s.healthCheck == nil {
		return &HealthCheckResponse{Healthy: true, Message: "ok"}, nil
	}
	return g.s.healthCheck(ctx, req.PluginName)
}

func (g *grpcServerImpl) Execute(req *ExecuteRequest, stream StagePlugin_ExecuteServer) error {
	if g.s.execute == nil {
		return status.Error(codes.Unimplemented, "Execute not implemented")
	}

	sendLog := func(log *LogEntry) {
		stream.Send(&ExecuteResponse{
			Status: StageStatusRunning,
			Logs:   []*LogEntry{log},
		})
	}

	resp, err := g.s.execute(stream.Context(), req.Context, sendLog)
	if err != nil {
		return err
	}

	return stream.Send(resp)
}

func (g *grpcServerImpl) Cancel(ctx context.Context, req *ExecuteRequest) (*ExecuteResponse, error) {
	if g.s.cancel == nil {
		return &ExecuteResponse{Status: StageStatusCancelled}, nil
	}
	return g.s.cancel(ctx, req.Context)
}

func (g *grpcServerImpl) StartExecution(ctx context.Context, req *StartExecutionRequest) (*StartExecutionResponse, error) {
	if g.s.startExecution == nil {
		return nil, status.Error(codes.Unimplemented, "StartExecution not implemented")
	}
	return g.s.startExecution(ctx, req.Context)
}

func (g *grpcServerImpl) PollStatus(ctx context.Context, req *PollStatusRequest) (*PollStatusResponse, error) {
	if g.s.pollStatus == nil {
		return &PollStatusResponse{
			ExecutionID: req.ExecutionID,
			Status:      StageStatusSuccess,
			Completed:   true,
		}, nil
	}
	return g.s.pollStatus(ctx, req.PluginName, req.ExecutionID, req.Context)
}

func (g *grpcServerImpl) CancelExecution(ctx context.Context, req *PollStatusRequest) (*PollStatusResponse, error) {
	if g.s.cancelExecution == nil {
		return &PollStatusResponse{
			ExecutionID: req.ExecutionID,
			Status:      StageStatusCancelled,
			Completed:   true,
		}, nil
	}
	return g.s.cancelExecution(ctx, req.PluginName, req.ExecutionID, req.Context)
}

func RegisterStagePluginServiceServer(s *grpc.Server, srv StagePluginServiceServer) {
	s.RegisterService(&StagePluginService_ServiceDesc, srv)
}

func newGRPCClient(cc *grpc.ClientConn) StagePluginServiceClient {
	return &stagePluginServiceClient{cc: cc}
}

type stagePluginServiceClient struct {
	cc *grpc.ClientConn
}

func (c *stagePluginServiceClient) GetPluginInfo(ctx context.Context, in *HealthCheckRequest, opts ...grpc.CallOption) (*PluginInfo, error) {
	out := new(PluginInfo)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/GetPluginInfo", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *stagePluginServiceClient) HealthCheck(ctx context.Context, in *HealthCheckRequest, opts ...grpc.CallOption) (*HealthCheckResponse, error) {
	out := new(HealthCheckResponse)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/HealthCheck", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *stagePluginServiceClient) Execute(ctx context.Context, in *ExecuteRequest, opts ...grpc.CallOption) (StagePlugin_ExecuteClient, error) {
	stream, err := c.cc.NewStream(ctx, &StagePluginService_Streams[0], "/cloudci.plugin.v1.StagePlugin/Execute", opts...)
	if err != nil {
		return nil, err
	}
	x := &stagePluginExecuteClient{stream}
	if err := x.ClientStream.SendMsg(in); err != nil {
		return nil, err
	}
	if err := x.ClientStream.CloseSend(); err != nil {
		return nil, err
	}
	return x, nil
}

type stagePluginExecuteClient struct {
	grpc.ClientStream
}

func (x *stagePluginExecuteClient) Recv() (*ExecuteResponse, error) {
	m := new(ExecuteResponse)
	if err := x.ClientStream.RecvMsg(m); err != nil {
		return nil, err
	}
	return m, nil
}

func (c *stagePluginServiceClient) Cancel(ctx context.Context, in *ExecuteRequest, opts ...grpc.CallOption) (*ExecuteResponse, error) {
	out := new(ExecuteResponse)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/Cancel", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *stagePluginServiceClient) StartExecution(ctx context.Context, in *StartExecutionRequest, opts ...grpc.CallOption) (*StartExecutionResponse, error) {
	out := new(StartExecutionResponse)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/StartExecution", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *stagePluginServiceClient) PollStatus(ctx context.Context, in *PollStatusRequest, opts ...grpc.CallOption) (*PollStatusResponse, error) {
	out := new(PollStatusResponse)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/PollStatus", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *stagePluginServiceClient) CancelExecution(ctx context.Context, in *PollStatusRequest, opts ...grpc.CallOption) (*PollStatusResponse, error) {
	out := new(PollStatusResponse)
	err := c.cc.Invoke(ctx, "/cloudci.plugin.v1.StagePlugin/CancelExecution", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

var StagePluginService_ServiceDesc = grpc.ServiceDesc{
	ServiceName: "cloudci.plugin.v1.StagePlugin",
	HandlerType: (*StagePluginServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "GetPluginInfo",
			Handler:    _StagePlugin_GetPluginInfo_Handler,
		},
		{
			MethodName: "HealthCheck",
			Handler:    _StagePlugin_HealthCheck_Handler,
		},
		{
			MethodName: "Cancel",
			Handler:    _StagePlugin_Cancel_Handler,
		},
		{
			MethodName: "StartExecution",
			Handler:    _StagePlugin_StartExecution_Handler,
		},
		{
			MethodName: "PollStatus",
			Handler:    _StagePlugin_PollStatus_Handler,
		},
		{
			MethodName: "CancelExecution",
			Handler:    _StagePlugin_CancelExecution_Handler,
		},
	},
	Streams: []grpc.StreamDesc{
		{
			StreamName:    "Execute",
			Handler:       _StagePlugin_Execute_Handler,
			ServerStreams: true,
		},
	},
	Metadata: "api/proto/plugin/v1/plugin.proto",
}

func _StagePlugin_GetPluginInfo_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(HealthCheckRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).GetPluginInfo(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/GetPluginInfo",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).GetPluginInfo(ctx, req.(*HealthCheckRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_HealthCheck_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(HealthCheckRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).HealthCheck(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/HealthCheck",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).HealthCheck(ctx, req.(*HealthCheckRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_Cancel_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(ExecuteRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).Cancel(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/Cancel",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).Cancel(ctx, req.(*ExecuteRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_StartExecution_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(StartExecutionRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).StartExecution(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/StartExecution",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).StartExecution(ctx, req.(*StartExecutionRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_PollStatus_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(PollStatusRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).PollStatus(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/PollStatus",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).PollStatus(ctx, req.(*PollStatusRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_CancelExecution_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(PollStatusRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(StagePluginServiceServer).CancelExecution(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/cloudci.plugin.v1.StagePlugin/CancelExecution",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(StagePluginServiceServer).CancelExecution(ctx, req.(*PollStatusRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _StagePlugin_Execute_Handler(srv interface{}, stream grpc.ServerStream) error {
	m := new(ExecuteRequest)
	if err := stream.RecvMsg(m); err != nil {
		return err
	}
	return srv.(StagePluginServiceServer).Execute(m, &stagePluginExecuteServer{stream})
}

type stagePluginExecuteServer struct {
	grpc.ServerStream
}

func (x *stagePluginExecuteServer) Send(m *ExecuteResponse) error {
	return x.ServerStream.SendMsg(m)
}

type StagePluginServiceServer interface {
	GetPluginInfo(context.Context, *HealthCheckRequest) (*PluginInfo, error)
	HealthCheck(context.Context, *HealthCheckRequest) (*HealthCheckResponse, error)
	Execute(*ExecuteRequest, StagePlugin_ExecuteServer) error
	Cancel(context.Context, *ExecuteRequest) (*ExecuteResponse, error)
	StartExecution(context.Context, *StartExecutionRequest) (*StartExecutionResponse, error)
	PollStatus(context.Context, *PollStatusRequest) (*PollStatusResponse, error)
	CancelExecution(context.Context, *PollStatusRequest) (*PollStatusResponse, error)
}

type StagePlugin_ExecuteServer interface {
	Send(*ExecuteResponse) error
	grpc.ServerStream
}

type UnimplementedStagePluginServiceServer struct{}

func (UnimplementedStagePluginServiceServer) GetPluginInfo(context.Context, *HealthCheckRequest) (*PluginInfo, error) {
	return nil, status.Errorf(codes.Unimplemented, "method GetPluginInfo not implemented")
}
func (UnimplementedStagePluginServiceServer) HealthCheck(context.Context, *HealthCheckRequest) (*HealthCheckResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method HealthCheck not implemented")
}
func (UnimplementedStagePluginServiceServer) Execute(*ExecuteRequest, StagePlugin_ExecuteServer) error {
	return status.Errorf(codes.Unimplemented, "method Execute not implemented")
}
func (UnimplementedStagePluginServiceServer) Cancel(context.Context, *ExecuteRequest) (*ExecuteResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method Cancel not implemented")
}
func (UnimplementedStagePluginServiceServer) StartExecution(context.Context, *StartExecutionRequest) (*StartExecutionResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method StartExecution not implemented")
}
func (UnimplementedStagePluginServiceServer) PollStatus(context.Context, *PollStatusRequest) (*PollStatusResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method PollStatus not implemented")
}
func (UnimplementedStagePluginServiceServer) CancelExecution(context.Context, *PollStatusRequest) (*PollStatusResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method CancelExecution not implemented")
}

var StagePluginService_Streams = []grpc.StreamDesc{
	{
		StreamName:    "Execute",
		ServerStreams: true,
		ClientStreams: false,
	},
}
