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

type ScanPlugin struct {
	plugin.UnimplementedStagePluginServer
}

func (s *ScanPlugin) GetInfo(ctx context.Context, req *plugin.PluginInfoRequest) (*plugin.PluginInfoResponse, error) {
	return &plugin.PluginInfoResponse{
		Name:        "code-scan",
		Version:     "1.0.0",
		Description: "Static code analysis plugin",
		Type:        plugin.StageType_STAGE_TYPE_SCAN,
		Author:      "CloudCI Team",
	}, nil
}

func (s *ScanPlugin) Execute(ctx context.Context, req *plugin.StageContext, logStream plugin.StagePlugin_ExecuteServer) error {
	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: "Starting code scan...",
		Stream:  "stdout",
	})

	scanTool := "gosec"
	if tool, ok := req.PluginConfig["scan_tool"]; ok {
		scanTool = tool
	}

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: fmt.Sprintf("Using scan tool: %s", scanTool),
		Stream:  "stdout",
	})

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: "Scanning files...",
		Stream:  "stdout",
	})

	issuesFound := 0
	if severity, ok := req.PluginConfig["severity"]; ok && severity == "high" {
		issuesFound = 2
		logStream.Send(&plugin.LogEntry{
			Level:   "WARN",
			Message: "Found 2 high severity issues",
			Stream:  "stderr",
		})
	}

	logStream.Send(&plugin.LogEntry{
		Level:   "INFO",
		Message: fmt.Sprintf("Scan completed. %d issues found", issuesFound),
		Stream:  "stdout",
	})

	status := plugin.StageStatus_STAGE_STATUS_SUCCESS
	if issuesFound > 0 {
		if failOnIssues, ok := req.PluginConfig["fail_on_issues"]; ok && failOnIssues == "true" {
			status = plugin.StageStatus_STAGE_STATUS_FAILED
		}
	}

	return logStream.Send(&plugin.StageResult{
		Status:   status,
		ExitCode: int32(issuesFound),
		Output: map[string]string{
			"issues_found": fmt.Sprintf("%d", issuesFound),
			"scan_tool":    scanTool,
		},
		DurationMs: 1500,
	})
}

func main() {
	if len(os.Args) < 2 {
		fmt.Println("Usage: scan-plugin <socket-path>")
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
	scanPlugin := &ScanPlugin{}
	plugin.RegisterStagePluginServer(grpcServer, scanPlugin)

	go func() {
		if err := grpcServer.Serve(lis); err != nil {
			fmt.Printf("Failed to serve: %v\n", err)
			os.Exit(1)
		}
	}()

	fmt.Printf("Code scan plugin started on %s\n", socketPath)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	grpcServer.GracefulStop()
	lis.Close()
	os.Remove(socketPath)
}
