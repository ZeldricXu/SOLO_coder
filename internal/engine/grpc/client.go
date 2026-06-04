package grpc

import (
	"context"
	"fmt"

	"github.com/jhump/protoreflect/desc"
	"github.com/jhump/protoreflect/dynamic"
	"github.com/jhump/protoreflect/dynamic/grpcdynamic"
	"github.com/jhump/protoreflect/grpcreflect"
	descriptorpb "google.golang.org/protobuf/types/descriptorpb"
	"google.golang.org/grpc"
)

type Client struct {
	conn   *grpc.ClientConn
	target string
}

func NewClient(target string, opts ...grpc.DialOption) (*Client, error) {
	conn, err := grpc.Dial(target, opts...)
	if err != nil {
		return nil, fmt.Errorf("dialing %s: %w", target, err)
	}

	return &Client{
		conn:   conn,
		target: target,
	}, nil
}

func (c *Client) newReflectClient(ctx context.Context) *grpcreflect.Client {
	return grpcreflect.NewClientAuto(ctx, c.conn)
}

func (c *Client) ListServices() ([]string, error) {
	ctx := context.Background()
	refCli := c.newReflectClient(ctx)
	defer refCli.Reset()

	services, err := refCli.ListServices()
	if err != nil {
		return nil, fmt.Errorf("listing services: %w", err)
	}

	names := make([]string, 0, len(services))
	for _, svc := range services {
		names = append(names, svc)
	}
	return names, nil
}

func (c *Client) ListMethods(serviceName string) ([]string, error) {
	ctx := context.Background()
	refCli := c.newReflectClient(ctx)
	defer refCli.Reset()

	svcDesc, err := refCli.ResolveService(serviceName)
	if err != nil {
		return nil, fmt.Errorf("resolving service %s: %w", serviceName, err)
	}

	methods := svcDesc.GetMethods()
	names := make([]string, 0, len(methods))
	for _, m := range methods {
		names = append(names, m.GetName())
	}
	return names, nil
}

func (c *Client) Invoke(ctx context.Context, service, method string, requestJSON string) (string, error) {
	refCli := c.newReflectClient(ctx)
	defer refCli.Reset()

	svcDesc, err := refCli.ResolveService(service)
	if err != nil {
		return "", fmt.Errorf("resolving service %s: %w", service, err)
	}

	var methodDesc *desc.MethodDescriptor
	for _, m := range svcDesc.GetMethods() {
		if m.GetName() == method {
			methodDesc = m
			break
		}
	}
	if methodDesc == nil {
		return "", fmt.Errorf("method %s/%s not found", service, method)
	}

	reqMsg := dynamic.NewMessage(methodDesc.GetInputType())
	if requestJSON != "" {
		err = reqMsg.UnmarshalJSON([]byte(requestJSON))
		if err != nil {
			return "", fmt.Errorf("unmarshaling request JSON: %w", err)
		}
	}

	stub := grpcdynamic.NewStub(c.conn)
	respMsg, err := stub.InvokeRpc(ctx, methodDesc, reqMsg)
	if err != nil {
		return "", fmt.Errorf("invoking RPC: %w", err)
	}

	dynResp, ok := respMsg.(*dynamic.Message)
	if !ok {
		return "", fmt.Errorf("response is not a dynamic message")
	}

	respBytes, err := dynResp.MarshalJSON()
	if err != nil {
		return "", fmt.Errorf("marshaling response: %w", err)
	}

	return string(respBytes), nil
}

func (c *Client) Close() error {
	return c.conn.Close()
}

type MethodDesc struct {
	Name       string
	InputType  string
	OutputType string
}

type FieldDesc struct {
	Name   string
	Type   string
	Number int32
}

type MessageDesc struct {
	Name   string
	Fields []FieldDesc
}

type ServiceDescription struct {
	Service  string
	Methods  []MethodDesc
	Messages map[string]MessageDesc
}

func (c *Client) Describe(ctx context.Context, serviceName string) (*ServiceDescription, error) {
	refCli := c.newReflectClient(ctx)
	defer refCli.Reset()

	svcDesc, err := refCli.ResolveService(serviceName)
	if err != nil {
		return nil, fmt.Errorf("resolving service %s: %w", serviceName, err)
	}

	desc := &ServiceDescription{
		Service:  serviceName,
		Messages: make(map[string]MessageDesc),
	}

	for _, m := range svcDesc.GetMethods() {
		md := MethodDesc{
			Name:       m.GetName(),
			InputType:  m.GetInputType().GetFullyQualifiedName(),
			OutputType: m.GetOutputType().GetFullyQualifiedName(),
		}
		desc.Methods = append(desc.Methods, md)

		resolveMessageFields(m.GetInputType(), desc.Messages)
		resolveMessageFields(m.GetOutputType(), desc.Messages)
	}

	return desc, nil
}

func resolveMessageFields(msgDesc *desc.MessageDescriptor, messages map[string]MessageDesc) {
	name := msgDesc.GetFullyQualifiedName()
	if _, ok := messages[name]; ok {
		return
	}

	md := MessageDesc{
		Name:   name,
		Fields: make([]FieldDesc, 0),
	}

	for _, f := range msgDesc.GetFields() {
		fd := FieldDesc{
			Name:   f.GetName(),
			Type:   fieldTypeString(f),
			Number: f.GetNumber(),
		}
		md.Fields = append(md.Fields, fd)
	}
	messages[name] = md

	for _, f := range msgDesc.GetFields() {
		if f.GetMessageType() != nil {
			resolveMessageFields(f.GetMessageType(), messages)
		}
	}
}

func fieldTypeString(f *desc.FieldDescriptor) string {
	label := ""
	if f.IsRepeated() {
		label = "repeated "
	}

	switch f.GetType() {
	case descriptorpb.FieldDescriptorProto_TYPE_DOUBLE:
		return label + "double"
	case descriptorpb.FieldDescriptorProto_TYPE_FLOAT:
		return label + "float"
	case descriptorpb.FieldDescriptorProto_TYPE_INT64:
		return label + "int64"
	case descriptorpb.FieldDescriptorProto_TYPE_UINT64:
		return label + "uint64"
	case descriptorpb.FieldDescriptorProto_TYPE_INT32:
		return label + "int32"
	case descriptorpb.FieldDescriptorProto_TYPE_FIXED64:
		return label + "fixed64"
	case descriptorpb.FieldDescriptorProto_TYPE_FIXED32:
		return label + "fixed32"
	case descriptorpb.FieldDescriptorProto_TYPE_BOOL:
		return label + "bool"
	case descriptorpb.FieldDescriptorProto_TYPE_STRING:
		return label + "string"
	case descriptorpb.FieldDescriptorProto_TYPE_BYTES:
		return label + "bytes"
	case descriptorpb.FieldDescriptorProto_TYPE_UINT32:
		return label + "uint32"
	case descriptorpb.FieldDescriptorProto_TYPE_SFIXED32:
		return label + "sfixed32"
	case descriptorpb.FieldDescriptorProto_TYPE_SFIXED64:
		return label + "sfixed64"
	case descriptorpb.FieldDescriptorProto_TYPE_SINT32:
		return label + "sint32"
	case descriptorpb.FieldDescriptorProto_TYPE_SINT64:
		return label + "sint64"
	case descriptorpb.FieldDescriptorProto_TYPE_MESSAGE:
		return label + f.GetMessageType().GetFullyQualifiedName()
	case descriptorpb.FieldDescriptorProto_TYPE_ENUM:
		return label + f.GetEnumType().GetFullyQualifiedName()
	default:
		return label + "unknown"
	}
}
