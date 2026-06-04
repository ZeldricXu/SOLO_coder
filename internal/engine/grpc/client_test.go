package grpc

import (
	"context"
	"net"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/health"
	"google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/reflection"
	"google.golang.org/grpc/test/bufconn"
)

const bufSize = 1024 * 1024

func createBufConnListener() (*bufconn.Listener, *grpc.Server) {
	lis := bufconn.Listen(bufSize)
	s := grpc.NewServer()
	reflection.Register(s)
	hs := health.NewServer()
	hs.SetServingStatus("", grpc_health_v1.HealthCheckResponse_SERVING)
	grpc_health_v1.RegisterHealthServer(s, hs)
	go s.Serve(lis)
	return lis, s
}

func newClientWithBufConn(lis *bufconn.Listener) (*Client, error) {
	conn, err := grpc.Dial("bufnet",
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return lis.Dial()
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		return nil, err
	}
	return &Client{conn: conn, target: "bufnet"}, nil
}

func setupTest(t *testing.T) (*Client, *grpc.Server) {
	t.Helper()
	lis, srv := createBufConnListener()
	client, err := newClientWithBufConn(lis)
	require.NoError(t, err)
	t.Cleanup(func() {
		client.Close()
		srv.Stop()
	})
	return client, srv
}

func TestNewClient(t *testing.T) {
	lis, srv := createBufConnListener()
	defer srv.Stop()

	client, err := newClientWithBufConn(lis)
	require.NoError(t, err)
	require.NotNil(t, client)
	assert.Equal(t, "bufnet", client.target)
	assert.NotNil(t, client.conn)

	err = client.Close()
	assert.NoError(t, err)
}

func TestClient_ListServices(t *testing.T) {
	client, _ := setupTest(t)

	services, err := client.ListServices()
	require.NoError(t, err)
	require.NotEmpty(t, services)

	found := false
	for _, svc := range services {
		if svc == "grpc.health.v1.Health" {
			found = true
			break
		}
	}
	assert.True(t, found, "expected grpc.health.v1.Health in service list, got %v", services)
}

func TestClient_ListMethods(t *testing.T) {
	client, _ := setupTest(t)

	methods, err := client.ListMethods("grpc.health.v1.Health")
	require.NoError(t, err)
	require.NotEmpty(t, methods)

	methodSet := make(map[string]bool, len(methods))
	for _, m := range methods {
		methodSet[m] = true
	}
	assert.True(t, methodSet["Check"], "expected Check method, got %v", methods)
	assert.True(t, methodSet["Watch"], "expected Watch method, got %v", methods)
}

func TestClient_Invoke_HealthCheck(t *testing.T) {
	client, _ := setupTest(t)

	resp, err := client.Invoke(context.Background(), "grpc.health.v1.Health", "Check", `{"service":""}`)
	require.NoError(t, err)
	assert.NotEmpty(t, resp)
	assert.Contains(t, resp, "SERVING")
}

func TestClient_Invoke_InvalidService(t *testing.T) {
	client, _ := setupTest(t)

	_, err := client.Invoke(context.Background(), "nonexistent.Service", "DoSomething", `{}`)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "resolving service")
}

func TestClient_Invoke_InvalidMethod(t *testing.T) {
	client, _ := setupTest(t)

	_, err := client.Invoke(context.Background(), "grpc.health.v1.Health", "NonexistentMethod", `{}`)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "not found")
}

func TestClient_Invoke_TypeMapping(t *testing.T) {
	client, _ := setupTest(t)

	_, err := client.Invoke(context.Background(), "grpc.health.v1.Health", "Check", `{"service":123}`)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "unmarshaling request JSON")
}

func TestClient_Close(t *testing.T) {
	lis, srv := createBufConnListener()
	defer srv.Stop()

	client, err := newClientWithBufConn(lis)
	require.NoError(t, err)

	err = client.Close()
	assert.NoError(t, err)
}

func TestClient_Describe(t *testing.T) {
	client, _ := setupTest(t)

	ctx := context.Background()
	desc, err := client.Describe(ctx, "grpc.health.v1.Health")
	require.NoError(t, err)

	assert.Equal(t, "grpc.health.v1.Health", desc.Service)
	assert.True(t, len(desc.Methods) >= 2, "should have at least 2 methods")

	methodNames := make(map[string]bool)
	for _, m := range desc.Methods {
		methodNames[m.Name] = true
		assert.NotEmpty(t, m.InputType)
		assert.NotEmpty(t, m.OutputType)
	}
	assert.True(t, methodNames["Check"], "should have Check method")
	assert.True(t, methodNames["Watch"], "should have Watch method")

	assert.NotEmpty(t, desc.Messages)

	for _, msg := range desc.Messages {
		assert.NotEmpty(t, msg.Name)
		for _, f := range msg.Fields {
			assert.NotEmpty(t, f.Type)
			assert.NotEmpty(t, f.Name)
			assert.Greater(t, f.Number, int32(0))
		}
	}
}
