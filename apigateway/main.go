package main

import (
	"apigateway/server"
	"flag"
	"fmt"
	"os"
)

func main() {
	gatewayPort := flag.Int("port", 8080, "Gateway server port")
	adminPort := flag.Int("admin-port", 9090, "Admin API server port")
	withDemo := flag.Bool("demo", true, "Initialize with demo data")
	flag.Parse()

	gateway := server.NewGatewayServer(*gatewayPort, *adminPort)

	if *withDemo {
		gateway.InitDemoData()
		fmt.Println("Demo data initialized")
	}

	if err := gateway.Start(); err != nil {
		fmt.Printf("Failed to start server: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("")
	fmt.Println("========================================")
	fmt.Println("    API Gateway Server Started")
	fmt.Println("========================================")
	fmt.Printf("Gateway Port: %d\n", *gatewayPort)
	fmt.Printf("Admin Port:   %d\n", *adminPort)
	fmt.Println("========================================")
	fmt.Println("")
	fmt.Println("Available Demo Routes:")
	fmt.Println("  GET /api/users/*    -> user-service (requires auth)")
	fmt.Println("  GET /api/orders/*   -> order-service (no auth required)")
	fmt.Println("")
	fmt.Println("Demo Auth:")
	fmt.Println("  API Key:   ak_demo_key_001")
	fmt.Println("  Secret:    sk_demo_secret_001")
	fmt.Println("  Bearer:    demo_token_001")
	fmt.Println("")
	fmt.Println("Admin API Endpoints:")
	fmt.Println("  GET  /health                              - Health check")
	fmt.Println("  POST /api/v1/routes/create               - Create route")
	fmt.Println("  GET  /api/v1/routes/list                 - List routes")
	fmt.Println("  GET  /api/v1/routes/get?route_id=xxx     - Get route")
	fmt.Println("  GET  /api/v1/stats/query                 - Query stats")
	fmt.Println("  GET  /api/v1/stats/summary               - Stats summary")
	fmt.Println("  GET  /api/v1/circuit/status?service_name=xxx - Circuit status")
	fmt.Println("  GET  /api/v1/circuit/list                - List circuits")
	fmt.Println("  GET  /api/v1/logs/query                  - Query logs")
	fmt.Println("  GET  /api/v1/services/list               - List services")
	fmt.Println("")

	gateway.WaitForShutdown()
}
