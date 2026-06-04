package cli

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/htest/htest/internal/engine/ws"
	"github.com/spf13/cobra"
)

func NewWSCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "ws",
		Short: "WebSocket client for testing",
	}

	cmd.AddCommand(newWSConnectCmd())
	cmd.AddCommand(newWSSendCmd())

	return cmd
}

func newWSConnectCmd() *cobra.Command {
	var url string
	var headers []string
	var listenDuration time.Duration

	cmd := &cobra.Command{
		Use:   "connect",
		Short: "Connect to WebSocket and listen for messages",
		Example: `  # Connect to a WebSocket and listen for 10 seconds
  htest ws connect -u wss://echo.example.com/ws --listen-duration 10s`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			headerMap := AppInstance.EnvMgr.AuthHeaders()
			for _, h := range headers {
				parts := strings.SplitN(h, ":", 2)
				if len(parts) == 2 {
					headerMap[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
				}
			}

			client := ws.NewClient(url, headerMap)

			ctx, cancel := context.WithTimeout(context.Background(), listenDuration)
			defer cancel()

			if err := client.Connect(ctx); err != nil {
				return AppInstance.Out.FormatError(err)
			}
			defer client.Close()

			msgCh, err := client.Receive()
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			var messages []ws.Message
			fmt.Fprintf(AppInstance.Out.Writer, "\nConnected to %s, listening for %v...\n", url, listenDuration)

			for {
				select {
				case msg, ok := <-msgCh:
					if !ok {
						return AppInstance.Out.FormatWS(messages)
					}
					messages = append(messages, msg)
					if verbose {
						fmt.Fprintf(AppInstance.Out.Writer, "Received: %s\n", msg.Content)
					}
				case <-ctx.Done():
					return AppInstance.Out.FormatWS(messages)
				}
			}
		},
	}

	cmd.Flags().StringVarP(&url, "url", "u", "", "WebSocket URL")
	cmd.Flags().StringArrayVarP(&headers, "header", "H", nil, "request headers (key:value)")
	cmd.Flags().DurationVar(&listenDuration, "listen-duration", 10*time.Second, "how long to listen for messages")
	cmd.MarkFlagRequired("url")

	return cmd
}

func newWSSendCmd() *cobra.Command {
	var url string
	var message string
	var format string
	var headers []string

	cmd := &cobra.Command{
		Use:   "send",
		Short: "Send a WebSocket message",
		Example: `  # Send a raw text message
  htest ws send -u wss://echo.example.com/ws -m "hello"

  # Send a JSON message
  htest ws send -u wss://echo.example.com/ws -m '{"type":"ping"}' --format json`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if AppInstance == nil {
				return fmt.Errorf("app not initialized")
			}

			headerMap := AppInstance.EnvMgr.AuthHeaders()
			for _, h := range headers {
				parts := strings.SplitN(h, ":", 2)
				if len(parts) == 2 {
					headerMap[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
				}
			}

			client := ws.NewClient(url, headerMap)

			ctx := context.Background()
			if err := client.Connect(ctx); err != nil {
				return AppInstance.Out.FormatError(err)
			}
			defer client.Close()

			sentMsg := ws.Message{
				Content:   message,
				Type:      1,
				Timestamp: time.Now(),
				Direction: "sent",
			}

			if format == "json" {
				var jsonData interface{}
				if err := json.Unmarshal([]byte(message), &jsonData); err != nil {
					return AppInstance.Out.FormatError(fmt.Errorf("invalid JSON: %w", err))
				}
				if err := client.SendJSON(jsonData); err != nil {
					return AppInstance.Out.FormatError(err)
				}
			} else {
				if err := client.Send(message); err != nil {
					return AppInstance.Out.FormatError(err)
				}
			}

			msgCh, err := client.Receive()
			if err != nil {
				return AppInstance.Out.FormatError(err)
			}

			var messages []ws.Message
			messages = append(messages, sentMsg)

			timeout := time.After(5 * time.Second)
			for {
				select {
				case msg, ok := <-msgCh:
					if !ok {
						return AppInstance.Out.FormatWS(messages)
					}
					messages = append(messages, msg)
				case <-timeout:
					return AppInstance.Out.FormatWS(messages)
				}
			}
		},
	}

	cmd.Flags().StringVarP(&url, "url", "u", "", "WebSocket URL")
	cmd.Flags().StringVarP(&message, "message", "m", "", "message to send")
	cmd.Flags().StringVar(&format, "format", "raw", "message format (raw/json)")
	cmd.Flags().StringArrayVarP(&headers, "header", "H", nil, "request headers (key:value)")
	cmd.MarkFlagRequired("url")
	cmd.MarkFlagRequired("message")

	return cmd
}
