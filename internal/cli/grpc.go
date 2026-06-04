package cli

import (
	"context"
	"fmt"
	"time"

	"github.com/htest/htest/internal/engine/grpc"
	"github.com/spf13/cobra"
	googlegrpc "google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

const (
	ansiBold   = "\033[1m"
	ansiCyan   = "\033[36m"
	ansiYellow = "\033[33m"
	ansiReset  = "\033[0m"
)

func NewGRPCCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "grpc",
		Short: "Send gRPC requests",
	}

	cmd.AddCommand(newGRPCListCmd())
	cmd.AddCommand(newGRPCInvokeCmd())
	cmd.AddCommand(newGRPCDescribeCmd())

	return cmd
}

func newGRPCListCmd() *cobra.Command {
	var target string
	var serviceName string

	cmd := &cobra.Command{
		Use:     "list",
		Short:   "List gRPC services and methods",
		Example: "  htest grpc list -t localhost:50051",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			client, err := grpc.NewClient(target, googlegrpc.WithTransportCredentials(insecure.NewCredentials()))
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}
			defer client.Close()

			if serviceName != "" {
				methods, err := client.ListMethods(serviceName)
				if err != nil {
					return AppInstance.Out.FormatError(err)
				}
				fmt.Fprintf(AppInstance.Out.Writer, "\nService: %s\n", serviceName)
				for _, m := range methods {
					fmt.Fprintf(AppInstance.Out.Writer, "  - %s\n", m)
				}
			} else {
				services, err := client.ListServices()
				if err != nil {
					return AppInstance.Out.FormatError(err)
				}
				fmt.Fprintf(AppInstance.Out.Writer, "\nServices:\n")
				for _, s := range services {
					fmt.Fprintf(AppInstance.Out.Writer, "  - %s\n", s)
				}
			}
			return nil
		},
	}

	cmd.Flags().StringVarP(&target, "target", "t", "", "gRPC server target (host:port)")
	cmd.Flags().StringVarP(&serviceName, "service", "s", "", "service name (to list methods)")
	cmd.MarkFlagRequired("target")

	return cmd
}

func newGRPCInvokeCmd() *cobra.Command {
	var target string
	var service string
	var method string
	var requestJSON string
	var timeout int

	cmd := &cobra.Command{
		Use:   "invoke",
		Short: "Invoke a gRPC method",
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			t := timeout
			if t == 0 {
				t = AppInstance.Config.Settings.Timeout
			}

			client, err := grpc.NewClient(target, googlegrpc.WithTransportCredentials(insecure.NewCredentials()))
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}
			defer client.Close()

			ctx, cancel := context.WithTimeout(context.Background(), time.Duration(t)*time.Second)
			defer cancel()

			start := time.Now()
			resp, err := client.Invoke(ctx, service, method, requestJSON)
			duration := time.Since(start)

			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			return AppInstance.Out.FormatGRPC(resp, duration)
		},
	}

	cmd.Flags().StringVarP(&target, "target", "t", "", "gRPC server target (host:port)")
	cmd.Flags().StringVarP(&service, "service", "s", "", "service name")
	cmd.Flags().StringVarP(&method, "method", "m", "", "method name")
	cmd.Flags().StringVarP(&requestJSON, "request", "r", "", "request JSON body")
	cmd.Flags().IntVar(&timeout, "timeout", 0, "request timeout in seconds")
	cmd.MarkFlagRequired("target")
	cmd.MarkFlagRequired("service")
	cmd.MarkFlagRequired("method")

	return cmd
}

func newGRPCDescribeCmd() *cobra.Command {
	var target string
	var serviceName string

	cmd := &cobra.Command{
		Use:   "describe",
		Short: "Describe a gRPC service with method signatures and message fields",
		Example: `  # Describe a gRPC service
  htest grpc describe -t localhost:50051 -s mypackage.MyService`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			client, err := grpc.NewClient(target, googlegrpc.WithTransportCredentials(insecure.NewCredentials()))
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}
			defer client.Close()

			ctx := context.Background()
			desc, err := client.Describe(ctx, serviceName)
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			fmt.Fprintf(AppInstance.Out.Writer, "\n%s━━━ Service: %s ━━━%s\n", ansiBold, desc.Service, ansiReset)
			fmt.Fprintf(AppInstance.Out.Writer, "\n%s─── RPC Methods ───%s\n", ansiCyan, ansiReset)
			for _, m := range desc.Methods {
				fmt.Fprintf(AppInstance.Out.Writer, "  %s%s%s(%s) returns (%s)\n", ansiYellow, m.Name, ansiReset, m.InputType, m.OutputType)
			}

			fmt.Fprintf(AppInstance.Out.Writer, "\n%s─── Message Types ───%s\n", ansiCyan, ansiReset)
			for _, msg := range desc.Messages {
				fmt.Fprintf(AppInstance.Out.Writer, "\n  %smessage %s%s {%s\n", ansiBold, msg.Name, ansiReset, "")
				for _, f := range msg.Fields {
					fmt.Fprintf(AppInstance.Out.Writer, "    %s%s%s %s = %d;\n", ansiCyan, f.Type, ansiReset, f.Name, f.Number)
				}
				fmt.Fprintf(AppInstance.Out.Writer, "  }\n")
			}

			return nil
		},
	}

	cmd.Flags().StringVarP(&target, "target", "t", "", "gRPC server target (host:port)")
	cmd.Flags().StringVarP(&serviceName, "service", "s", "", "service name to describe")
	cmd.MarkFlagRequired("target")
	cmd.MarkFlagRequired("service")

	return cmd
}
