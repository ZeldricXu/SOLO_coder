package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"pixelrealm/internal/game"
	"pixelrealm/internal/persistence"
	"pixelrealm/pkg/config"
)

func main() {
	cfg := config.Load()
	
	log.Printf("Starting PixelRealm Game Server V2 on port %s...", cfg.Server.Port)
	log.Println("Features: Grid-based AOI, Shard Locks, Decoupled Battle/Broadcast")
	
	var playerStore *persistence.PlayerStore
	var playerCache *persistence.PlayerCache
	
	mongoDB, err := persistence.NewMongoDB(&cfg.MongoDB)
	if err != nil {
		log.Printf("Warning: Failed to connect to MongoDB: %v. Running in memory-only mode.", err)
		playerStore = nil
	} else {
		defer mongoDB.Disconnect()
		
		playerStore = persistence.NewPlayerStore(mongoDB)
		if err := playerStore.CreateIndexes(); err != nil {
			log.Printf("Warning: Failed to create indexes: %v", err)
		}
		log.Println("MongoDB connected successfully")
	}
	
	asyncWriter := persistence.NewAsyncWriter(playerStore, 1000, 100, time.Second)
	asyncWriter.Start()
	defer asyncWriter.Stop()
	
	playerCache = persistence.NewPlayerCache(playerStore, asyncWriter)
	
	gameService := game.NewGameServiceV2(cfg, playerCache)
	if err := gameService.Initialize(); err != nil {
		log.Fatalf("Failed to initialize game service: %v", err)
	}
	
	wsServer := gameService.GetWebSocketServer()
	sceneMgr := gameService.GetSceneManager()
	broadcastService := gameService.GetBroadcastService()
	
	http.HandleFunc("/ws", wsServer.HandleRequest)
	
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		
		stats := map[string]interface{}{
			"status":     "ok",
			"online":     wsServer.GetConnectedCount(),
			"version":    "v2-grid-aoi",
			"timestamp":  time.Now().Format(time.RFC3339),
		}
		json.NewEncoder(w).Encode(stats)
	})
	
	http.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		broadcastStats := broadcastService.GetStats()
		
		stats := map[string]interface{}{
			"connected_players": wsServer.GetConnectedCount(),
			"online_players":    sceneMgr.GetOnlineCount(),
			"maps": map[string]int{
				"forest_01":  sceneMgr.GetMapPlayerCount("forest_01"),
				"forest_02":  sceneMgr.GetMapPlayerCount("forest_02"),
				"village_01": sceneMgr.GetMapPlayerCount("village_01"),
			},
			"broadcast_stats": map[string]interface{}{
				"total_messages":   broadcastStats.TotalMessages,
				"aoi_messages":     broadcastStats.AOIMessages,
				"map_messages":     broadcastStats.MapMessages,
				"world_messages":   broadcastStats.WorldMessages,
				"private_messages": broadcastStats.PrivateMessages,
				"players_reached":  broadcastStats.PlayersReached,
			},
			"timestamp": time.Now().Format(time.RFC3339),
		}
		
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(stats)
	})
	
	http.HandleFunc("/debug/grids", func(w http.ResponseWriter, r *http.Request) {
		gridInfo := make(map[string]interface{})
		
		for _, mapID := range []string{"forest_01", "forest_02", "village_01"} {
			if shard, exists := sceneMgr.GetGridShard(mapID); exists {
				gridData := make(map[string]interface{})
				gridData["total_players"] = shard.GetTotalPlayers()
				gridData["grid_count"] = shard.GetGridCount()
				gridData["grid_size"] = shard.GetGridSize()
				gridInfo[mapID] = gridData
			}
		}
		
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(gridInfo)
	})
	
	server := &http.Server{
		Addr:         ":" + cfg.Server.Port,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}
	
	go func() {
		log.Printf("Server listening on http://localhost:%s", cfg.Server.Port)
		log.Printf("WebSocket endpoint: ws://localhost:%s/ws", cfg.Server.Port)
		log.Printf("Health check: http://localhost:%s/health", cfg.Server.Port)
		log.Printf("Stats endpoint: http://localhost:%s/stats", cfg.Server.Port)
		log.Printf("Grid debug: http://localhost:%s/debug/grids", cfg.Server.Port)
		
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()
	
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	
	log.Println("Shutting down server...")
	
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	
	if err := server.Shutdown(ctx); err != nil {
		log.Printf("Server forced to shutdown: %v", err)
	}
	
	log.Println("Server stopped gracefully")
}
