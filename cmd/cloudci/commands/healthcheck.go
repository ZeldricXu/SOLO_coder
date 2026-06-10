package commands

import (
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/spf13/cobra"
)

var healthcheckCmd = &cobra.Command{
	Use:   "healthcheck",
	Short: "Check if the CloudCI server is healthy",
	Long:  `Check the health status of the running CloudCI server by calling the /health endpoint.`,
	Run:   runHealthcheck,
}

var (
	healthcheckHost string
	healthcheckPort int
	healthcheckTimeout int
)

func init() {
	rootCmd.AddCommand(healthcheckCmd)

	healthcheckCmd.Flags().StringVar(&healthcheckHost, "host", "127.0.0.1", "Server host")
	healthcheckCmd.Flags().IntVar(&healthcheckPort, "port", 8080, "Server port")
	healthcheckCmd.Flags().IntVar(&healthcheckTimeout, "timeout", 5, "Timeout in seconds")
}

func runHealthcheck(cmd *cobra.Command, args []string) {
	url := fmt.Sprintf("http://%s:%d/health", healthcheckHost, healthcheckPort)

	client := &http.Client{
		Timeout: time.Duration(healthcheckTimeout) * time.Second,
	}

	resp, err := client.Get(url)
	if err != nil {
		fmt.Printf("UNHEALTHY: %v\n", err)
		fmt.Println(`{"status":"unhealthy"}`)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		fmt.Printf("UNHEALTHY: HTTP %d\n%s\n", resp.StatusCode, string(body))
		return
	}

	body, _ := io.ReadAll(resp.Body)
	fmt.Printf("HEALTHY: HTTP %d\n%s\n", resp.StatusCode, string(body))
}
