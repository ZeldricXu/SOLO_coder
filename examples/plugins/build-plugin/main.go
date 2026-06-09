package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/signal"
	"syscall"

	"github.com/solocoder/cloudci/internal/plugin"
	"google.golang.org/grpc"
)

type BuildPlugin struct {
	plugin.UnimplementedStagePluginServer
}

func (b *BuildPlugin) GetInfo(ctx context.Context, req *plugin.PluginInfoRequest) (*plugin.PluginInfoResponse, error) {
	return &plugin.PluginInfoResponse{
		Name:        "go-build",
		Version:     "1.0.0",
		Description: "Go build plugin",
		Type:        plugin.StageType_STAGE_TYPE_BUILD,
		Author:      "CloudCI Team",
	}, nil
}

func (b *BuildPlugin) Execute(ctx context.Context, req *plugin.StageContext, logStream plugin.StagePlugin_ExecuteServer) error {
	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: "Starting Go build...",
		Stream:  "stdout",
	})

	output := "app"
	if out, ok := req.PluginConfig["output"]; ok {
		output = out
	}

	goos := "linux"
	if os, ok := req.PluginConfig["goos"]; ok {
		goos = os
	}

	goarch := "amd64"
	if arch, ok := req.PluginConfig["goarch"]; ok {
		goarch = arch
	}

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: fmt.Sprintf("Building for %s/%s, output: %s", goos, goarch, output),
		Stream:  "stdout",
	})

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: "Running: go mod download",
		Stream:  "stdout",
	})

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: "Running: go build -o " + output,
		Stream:  "stdout",
	})

	artifactPath := fmt.Sprintf("%s/%s", req.WorkingDir, output)
	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: fmt.Sprintf("Build completed successfully: %s", artifactPath),
		Stream:  "stdout",
	})

	return logStream.Send(&plugin.StageResult{
		Status:   plugin.StageStatus_STAGE_STATUS_SUCCESS,
		ExitCode: 0,
		Output: map[string]string{
			"output":     output,
			"goos":       goos,
			"goarch":     goarch,
			"artifact":   artifactPath,
			"binary_md5": "d41d8cd98f00b204e9800998ecf8427e",
		},
		Artifacts: []string{artifactPath},
		DurationMs: 3000,
	})
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: build-plugin <socket-path>")
		os.Exit(1)
	}

	socketPath := os.Args[1]

	if err := os.Remove(socketPath); err != nil && !os.IsNotExist(err) {
		fmt.Printf("Failed to remove existing socket: %v\n", err)
		os.Exit(1)
	}

	lis, err := net.Listen("unix", socketPath)
	if err != nil {
		fmt.Printf("Failed to listen: %v\n", err)
		os.Exit(1)
	}

	grpcServer := grpc.NewServer()
	buildPlugin := &BuildPlugin{}
	plugin.RegisterStagePluginServer(grpcServer, buildPlugin)

	go func() {
		if err := grpcServer.Serve(lis); err != nil {
			fmt.Printf("Failed to serve: %v\n", err)
			os.Exit(1)
		}
	}()

	fmt.Printf("Go build plugin started on %s\n", socketPath)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	grpcServer.GracefulStop()
	lis.Close()
	os.Remove(socketPath)
}
