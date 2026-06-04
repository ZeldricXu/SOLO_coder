package proto

import (
	context "context"
	fmt "fmt"
	math "math"

	_ "github.com/golang/protobuf/ptypes/any"
	grpc "google.golang.org/grpc"
	codes "google.golang.org/grpc/codes"
	status "google.golang.org/grpc/status"
	protoreflect "google.golang.org/protobuf/reflect/protoreflect"
	protoimpl "google.golang.org/protobuf/runtime/protoimpl"
	reflect "reflect"
	sync "sync"
)

const (
	_ = protoimpl.EnforceVersion(20 - protoimpl.MinVersion)
	_ = protoimpl.EnforceVersion(protoimpl.MaxVersion - 20)
)

type RegisterWorkerRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Id           string   `protobuf:"bytes,1,opt,name=id,proto3" json:"id,omitempty"`
	Namespace    string   `protobuf:"bytes,2,opt,name=namespace,proto3" json:"namespace,omitempty"`
	Hostname     string   `protobuf:"bytes,3,opt,name=hostname,proto3" json:"hostname,omitempty"`
	GrpcAddr     string   `protobuf:"bytes,4,opt,name=grpc_addr,json=grpcAddr,proto3" json:"grpc_addr,omitempty"`
	HttpAddr     string   `protobuf:"bytes,5,opt,name=http_addr,json=httpAddr,proto3" json:"http_addr,omitempty"`
	Capabilities []string `protobuf:"bytes,6,rep,name=capabilities,proto3" json:"capabilities,omitempty"`
	MaxLoad      int32    `protobuf:"varint,7,opt,name=max_load,json=maxLoad,proto3" json:"max_load,omitempty"`
}

func (x *RegisterWorkerRequest) Reset()         { *x = RegisterWorkerRequest{} }
func (x *RegisterWorkerRequest) String() string { return protoimpl.X.MessageStringOf(x) }
func (*RegisterWorkerRequest) ProtoMessage()    {}
func (x *RegisterWorkerRequest) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *RegisterWorkerRequest) GetId() string {
	if x != nil {
		return x.Id
	}
	return ""
}
func (x *RegisterWorkerRequest) GetNamespace() string {
	if x != nil {
		return x.Namespace
	}
	return ""
}
func (x *RegisterWorkerRequest) GetHostname() string {
	if x != nil {
		return x.Hostname
	}
	return ""
}
func (x *RegisterWorkerRequest) GetGrpcAddr() string {
	if x != nil {
		return x.GrpcAddr
	}
	return ""
}
func (x *RegisterWorkerRequest) GetHttpAddr() string {
	if x != nil {
		return x.HttpAddr
	}
	return ""
}
func (x *RegisterWorkerRequest) GetCapabilities() []string {
	if x != nil {
		return x.Capabilities
	}
	return nil
}
func (x *RegisterWorkerRequest) GetMaxLoad() int32 {
	if x != nil {
		return x.MaxLoad
	}
	return 0
}

type RegisterWorkerResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	WorkerId string `protobuf:"bytes,1,opt,name=worker_id,json=workerId,proto3" json:"worker_id,omitempty"`
	Status   string `protobuf:"bytes,2,opt,name=status,proto3" json:"status,omitempty"`
}

func (x *RegisterWorkerResponse) Reset()         { *x = RegisterWorkerResponse{} }
func (x *RegisterWorkerResponse) String() string { return protoimpl.X.MessageStringOf(x) }
func (*RegisterWorkerResponse) ProtoMessage()    {}
func (x *RegisterWorkerResponse) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *RegisterWorkerResponse) GetWorkerId() string {
	if x != nil {
		return x.WorkerId
	}
	return ""
}
func (x *RegisterWorkerResponse) GetStatus() string {
	if x != nil {
		return x.Status
	}
	return ""
}

type DeregisterWorkerRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	WorkerId string `protobuf:"bytes,1,opt,name=worker_id,json=workerId,proto3" json:"worker_id,omitempty"`
}

func (x *DeregisterWorkerRequest) Reset()         { *x = DeregisterWorkerRequest{} }
func (x *DeregisterWorkerRequest) String() string { return protoimpl.X.MessageStringOf(x) }
func (*DeregisterWorkerRequest) ProtoMessage()    {}
func (x *DeregisterWorkerRequest) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *DeregisterWorkerRequest) GetWorkerId() string {
	if x != nil {
		return x.WorkerId
	}
	return ""
}

type DeregisterWorkerResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Success bool `protobuf:"varint,1,opt,name=success,proto3" json:"success,omitempty"`
}

func (x *DeregisterWorkerResponse) Reset()         { *x = DeregisterWorkerResponse{} }
func (x *DeregisterWorkerResponse) String() string { return protoimpl.X.MessageStringOf(x) }
func (*DeregisterWorkerResponse) ProtoMessage()    {}
func (x *DeregisterWorkerResponse) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *DeregisterWorkerResponse) GetSuccess() bool {
	if x != nil {
		return x.Success
	}
	return false
}

type HeartbeatRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	WorkerId    string `protobuf:"bytes,1,opt,name=worker_id,json=workerId,proto3" json:"worker_id,omitempty"`
	CurrentLoad int32  `protobuf:"varint,2,opt,name=current_load,json=currentLoad,proto3" json:"current_load,omitempty"`
}

func (x *HeartbeatRequest) Reset()         { *x = HeartbeatRequest{} }
func (x *HeartbeatRequest) String() string { return protoimpl.X.MessageStringOf(x) }
func (*HeartbeatRequest) ProtoMessage()    {}
func (x *HeartbeatRequest) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *HeartbeatRequest) GetWorkerId() string {
	if x != nil {
		return x.WorkerId
	}
	return ""
}
func (x *HeartbeatRequest) GetCurrentLoad() int32 {
	if x != nil {
		return x.CurrentLoad
	}
	return 0
}

type HeartbeatResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Success bool   `protobuf:"varint,1,opt,name=success,proto3" json:"success,omitempty"`
	Status  string `protobuf:"bytes,2,opt,name=status,proto3" json:"status,omitempty"`
}

func (x *HeartbeatResponse) Reset()         { *x = HeartbeatResponse{} }
func (x *HeartbeatResponse) String() string { return protoimpl.X.MessageStringOf(x) }
func (*HeartbeatResponse) ProtoMessage()    {}
func (x *HeartbeatResponse) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *HeartbeatResponse) GetSuccess() bool {
	if x != nil {
		return x.Success
	}
	return false
}
func (x *HeartbeatResponse) GetStatus() string {
	if x != nil {
		return x.Status
	}
	return ""
}

type ListWorkersRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Namespace   string `protobuf:"bytes,1,opt,name=namespace,proto3" json:"namespace,omitempty"`
	HealthyOnly bool   `protobuf:"varint,2,opt,name=healthy_only,json=healthyOnly,proto3" json:"healthy_only,omitempty"`
}

func (x *ListWorkersRequest) Reset()         { *x = ListWorkersRequest{} }
func (x *ListWorkersRequest) String() string { return protoimpl.X.MessageStringOf(x) }
func (*ListWorkersRequest) ProtoMessage()    {}
func (x *ListWorkersRequest) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *ListWorkersRequest) GetNamespace() string {
	if x != nil {
		return x.Namespace
	}
	return ""
}
func (x *ListWorkersRequest) GetHealthyOnly() bool {
	if x != nil {
		return x.HealthyOnly
	}
	return false
}

type WorkerInfo struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Id            string   `protobuf:"bytes,1,opt,name=id,proto3" json:"id,omitempty"`
	Namespace     string   `protobuf:"bytes,2,opt,name=namespace,proto3" json:"namespace,omitempty"`
	Hostname      string   `protobuf:"bytes,3,opt,name=hostname,proto3" json:"hostname,omitempty"`
	GrpcAddr      string   `protobuf:"bytes,4,opt,name=grpc_addr,json=grpcAddr,proto3" json:"grpc_addr,omitempty"`
	HttpAddr      string   `protobuf:"bytes,5,opt,name=http_addr,json=httpAddr,proto3" json:"http_addr,omitempty"`
	Status        string   `protobuf:"bytes,6,opt,name=status,proto3" json:"status,omitempty"`
	LastHeartbeat int64    `protobuf:"varint,7,opt,name=last_heartbeat,json=lastHeartbeat,proto3" json:"last_heartbeat,omitempty"`
	CurrentLoad   int32    `protobuf:"varint,8,opt,name=current_load,json=currentLoad,proto3" json:"current_load,omitempty"`
	MaxLoad       int32    `protobuf:"varint,9,opt,name=max_load,json=maxLoad,proto3" json:"max_load,omitempty"`
	Capabilities  []string `protobuf:"bytes,10,rep,name=capabilities,proto3" json:"capabilities,omitempty"`
}

func (x *WorkerInfo) Reset()         { *x = WorkerInfo{} }
func (x *WorkerInfo) String() string { return protoimpl.X.MessageStringOf(x) }
func (*WorkerInfo) ProtoMessage()    {}
func (x *WorkerInfo) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *WorkerInfo) GetId() string {
	if x != nil {
		return x.Id
	}
	return ""
}
func (x *WorkerInfo) GetNamespace() string {
	if x != nil {
		return x.Namespace
	}
	return ""
}
func (x *WorkerInfo) GetHostname() string {
	if x != nil {
		return x.Hostname
	}
	return ""
}
func (x *WorkerInfo) GetGrpcAddr() string {
	if x != nil {
		return x.GrpcAddr
	}
	return ""
}
func (x *WorkerInfo) GetHttpAddr() string {
	if x != nil {
		return x.HttpAddr
	}
	return ""
}
func (x *WorkerInfo) GetStatus() string {
	if x != nil {
		return x.Status
	}
	return ""
}
func (x *WorkerInfo) GetLastHeartbeat() int64 {
	if x != nil {
		return x.LastHeartbeat
	}
	return 0
}
func (x *WorkerInfo) GetCurrentLoad() int32 {
	if x != nil {
		return x.CurrentLoad
	}
	return 0
}
func (x *WorkerInfo) GetMaxLoad() int32 {
	if x != nil {
		return x.MaxLoad
	}
	return 0
}
func (x *WorkerInfo) GetCapabilities() []string {
	if x != nil {
		return x.Capabilities
	}
	return nil
}

type ListWorkersResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Workers []*WorkerInfo `protobuf:"bytes,1,rep,name=workers,proto3" json:"workers,omitempty"`
}

func (x *ListWorkersResponse) Reset()         { *x = ListWorkersResponse{} }
func (x *ListWorkersResponse) String() string { return protoimpl.X.MessageStringOf(x) }
func (*ListWorkersResponse) ProtoMessage()    {}
func (x *ListWorkersResponse) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *ListWorkersResponse) GetWorkers() []*WorkerInfo {
	if x != nil {
		return x.Workers
	}
	return nil
}

type GetWorkerRequest struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	WorkerId string `protobuf:"bytes,1,opt,name=worker_id,json=workerId,proto3" json:"worker_id,omitempty"`
}

func (x *GetWorkerRequest) Reset()         { *x = GetWorkerRequest{} }
func (x *GetWorkerRequest) String() string { return protoimpl.X.MessageStringOf(x) }
func (*GetWorkerRequest) ProtoMessage()    {}
func (x *GetWorkerRequest) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *GetWorkerRequest) GetWorkerId() string {
	if x != nil {
		return x.WorkerId
	}
	return ""
}

type GetWorkerResponse struct {
	state         protoimpl.MessageState
	sizeCache     protoimpl.SizeCache
	unknownFields protoimpl.UnknownFields

	Worker *WorkerInfo `protobuf:"bytes,1,opt,name=worker,proto3" json:"worker,omitempty"`
}

func (x *GetWorkerResponse) Reset()         { *x = GetWorkerResponse{} }
func (x *GetWorkerResponse) String() string { return protoimpl.X.MessageStringOf(x) }
func (*GetWorkerResponse) ProtoMessage()    {}
func (x *GetWorkerResponse) ProtoReflect() protoreflect.Message {
	return nil
}
func (x *GetWorkerResponse) GetWorker() *WorkerInfo {
	if x != nil {
		return x.Worker
	}
	return nil
}

type RegistryServiceClient interface {
	RegisterWorker(ctx context.Context, in *RegisterWorkerRequest, opts ...grpc.CallOption) (*RegisterWorkerResponse, error)
	DeregisterWorker(ctx context.Context, in *DeregisterWorkerRequest, opts ...grpc.CallOption) (*DeregisterWorkerResponse, error)
	Heartbeat(ctx context.Context, in *HeartbeatRequest, opts ...grpc.CallOption) (*HeartbeatResponse, error)
	ListWorkers(ctx context.Context, in *ListWorkersRequest, opts ...grpc.CallOption) (*ListWorkersResponse, error)
	GetWorker(ctx context.Context, in *GetWorkerRequest, opts ...grpc.CallOption) (*GetWorkerResponse, error)
}

type registryServiceClient struct {
	cc grpc.ClientConnInterface
}

func NewRegistryServiceClient(cc grpc.ClientConnInterface) RegistryServiceClient {
	return &registryServiceClient{cc}
}

func (c *registryServiceClient) RegisterWorker(ctx context.Context, in *RegisterWorkerRequest, opts ...grpc.CallOption) (*RegisterWorkerResponse, error) {
	out := new(RegisterWorkerResponse)
	err := c.cc.Invoke(ctx, "/registry.RegistryService/RegisterWorker", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *registryServiceClient) DeregisterWorker(ctx context.Context, in *DeregisterWorkerRequest, opts ...grpc.CallOption) (*DeregisterWorkerResponse, error) {
	out := new(DeregisterWorkerResponse)
	err := c.cc.Invoke(ctx, "/registry.RegistryService/DeregisterWorker", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *registryServiceClient) Heartbeat(ctx context.Context, in *HeartbeatRequest, opts ...grpc.CallOption) (*HeartbeatResponse, error) {
	out := new(HeartbeatResponse)
	err := c.cc.Invoke(ctx, "/registry.RegistryService/Heartbeat", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *registryServiceClient) ListWorkers(ctx context.Context, in *ListWorkersRequest, opts ...grpc.CallOption) (*ListWorkersResponse, error) {
	out := new(ListWorkersResponse)
	err := c.cc.Invoke(ctx, "/registry.RegistryService/ListWorkers", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func (c *registryServiceClient) GetWorker(ctx context.Context, in *GetWorkerRequest, opts ...grpc.CallOption) (*GetWorkerResponse, error) {
	out := new(GetWorkerResponse)
	err := c.cc.Invoke(ctx, "/registry.RegistryService/GetWorker", in, out, opts...)
	if err != nil {
		return nil, err
	}
	return out, nil
}

type RegistryServiceServer interface {
	RegisterWorker(context.Context, *RegisterWorkerRequest) (*RegisterWorkerResponse, error)
	DeregisterWorker(context.Context, *DeregisterWorkerRequest) (*DeregisterWorkerResponse, error)
	Heartbeat(context.Context, *HeartbeatRequest) (*HeartbeatResponse, error)
	ListWorkers(context.Context, *ListWorkersRequest) (*ListWorkersResponse, error)
	GetWorker(context.Context, *GetWorkerRequest) (*GetWorkerResponse, error)
	mustEmbedUnimplementedRegistryServiceServer()
}

type UnimplementedRegistryServiceServer struct{}

func (UnimplementedRegistryServiceServer) RegisterWorker(context.Context, *RegisterWorkerRequest) (*RegisterWorkerResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method RegisterWorker not implemented")
}
func (UnimplementedRegistryServiceServer) DeregisterWorker(context.Context, *DeregisterWorkerRequest) (*DeregisterWorkerResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method DeregisterWorker not implemented")
}
func (UnimplementedRegistryServiceServer) Heartbeat(context.Context, *HeartbeatRequest) (*HeartbeatResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method Heartbeat not implemented")
}
func (UnimplementedRegistryServiceServer) ListWorkers(context.Context, *ListWorkersRequest) (*ListWorkersResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method ListWorkers not implemented")
}
func (UnimplementedRegistryServiceServer) GetWorker(context.Context, *GetWorkerRequest) (*GetWorkerResponse, error) {
	return nil, status.Errorf(codes.Unimplemented, "method GetWorker not implemented")
}
func (UnimplementedRegistryServiceServer) mustEmbedUnimplementedRegistryServiceServer() {}

func RegisterRegistryServiceServer(s *grpc.Server, srv RegistryServiceServer) {
	s.RegisterService(&RegistryService_ServiceDesc, srv)
}

var RegistryService_ServiceDesc = grpc.ServiceDesc{
	ServiceName: "registry.RegistryService",
	HandlerType: (*RegistryServiceServer)(nil),
	Methods: []grpc.MethodDesc{
		{
			MethodName: "RegisterWorker",
			Handler:    _RegistryService_RegisterWorker_Handler,
		},
		{
			MethodName: "DeregisterWorker",
			Handler:    _RegistryService_DeregisterWorker_Handler,
		},
		{
			MethodName: "Heartbeat",
			Handler:    _RegistryService_Heartbeat_Handler,
		},
		{
			MethodName: "ListWorkers",
			Handler:    _RegistryService_ListWorkers_Handler,
		},
		{
			MethodName: "GetWorker",
			Handler:    _RegistryService_GetWorker_Handler,
		},
	},
	Streams:  []grpc.StreamDesc{},
	Metadata: "registry.proto",
}

func _RegistryService_RegisterWorker_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(RegisterWorkerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(RegistryServiceServer).RegisterWorker(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/registry.RegistryService/RegisterWorker",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(RegistryServiceServer).RegisterWorker(ctx, req.(*RegisterWorkerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _RegistryService_DeregisterWorker_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(DeregisterWorkerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(RegistryServiceServer).DeregisterWorker(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/registry.RegistryService/DeregisterWorker",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(RegistryServiceServer).DeregisterWorker(ctx, req.(*DeregisterWorkerRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _RegistryService_Heartbeat_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(HeartbeatRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(RegistryServiceServer).Heartbeat(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/registry.RegistryService/Heartbeat",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(RegistryServiceServer).Heartbeat(ctx, req.(*HeartbeatRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _RegistryService_ListWorkers_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(ListWorkersRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(RegistryServiceServer).ListWorkers(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/registry.RegistryService/ListWorkers",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(RegistryServiceServer).ListWorkers(ctx, req.(*ListWorkersRequest))
	}
	return interceptor(ctx, in, info, handler)
}

func _RegistryService_GetWorker_Handler(srv interface{}, ctx context.Context, dec func(interface{}) error, interceptor grpc.UnaryServerInterceptor) (interface{}, error) {
	in := new(GetWorkerRequest)
	if err := dec(in); err != nil {
		return nil, err
	}
	if interceptor == nil {
		return srv.(RegistryServiceServer).GetWorker(ctx, in)
	}
	info := &grpc.UnaryServerInfo{
		Server:     srv,
		FullMethod: "/registry.RegistryService/GetWorker",
	}
	handler := func(ctx context.Context, req interface{}) (interface{}, error) {
		return srv.(RegistryServiceServer).GetWorker(ctx, req.(*GetWorkerRequest))
	}
	return interceptor(ctx, in, info, handler)
}
