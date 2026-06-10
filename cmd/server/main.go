package main

import (
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/studio/gameroom/pkg/api"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/config"
	"github.com/studio/gameroom/pkg/match"
	"github.com/studio/gameroom/pkg/network"
	"github.com/studio/gameroom/pkg/observer"
	"github.com/studio/gameroom/pkg/room"
	"github.com/studio/gameroom/pkg/storage"

	_ "github.com/studio/gameroom/pkg/games/landlord"
	_ "github.com/studio/gameroom/pkg/games/mahjong"
	_ "github.com/studio/gameroom/pkg/games/texas"
)

func main() {
	cfg := config.DefaultConfig()
	common.SetLogLevel(common.LevelDebug)
	common.LogInfo("=== Game Room Engine Starting ===")
	common.LogInfo("Config loaded, server on %s", cfg.Server.HTTPAddr)

	roomManager := room.NewManager()
	common.LogInfo("Room manager initialized")

	matchService := match.NewService(roomManager)
	matchService.RegisterGame(common.GameTypeMahjong, 4, 4)
	matchService.RegisterGame(common.GameTypeLandlord, 3, 3)
	matchService.RegisterGame(common.GameTypeTexas, 2, 9)
	matchService.Start()
	common.LogInfo("Match service started")

	connMgr := network.NewConnectionManager()
	common.LogInfo("Connection manager initialized")

	observerMgr := observer.NewManager(cfg.Observer.DelaySec)
	observerMgr.StartDrainLoop()
	interactionMgr := observer.NewInteractionManager()
	common.LogInfo("Observer system initialized (delay=%ds)", cfg.Observer.DelaySec)

	var mongoStore *storage.MongoStore
	var redisStore *storage.RedisStore
	var statsAgg *storage.StatsAggregator

	if cfg.Mongo.URI != "" {
		var err error
		mongoStore, err = storage.NewMongoStore(cfg.Mongo.URI, cfg.Mongo.Database)
		if err != nil {
			common.LogWarn("MongoDB connection failed (continuing without persistence): %v", err)
		} else {
			statsAgg = storage.NewStatsAggregator(mongoStore)
			common.LogInfo("MongoDB connected: %s/%s", cfg.Mongo.URI, cfg.Mongo.Database)
		}
	}

	if cfg.Redis.Addr != "" {
		var err error
		redisStore, err = storage.NewRedisStore(cfg.Redis.Addr, cfg.Redis.Password, cfg.Redis.DB)
		if err != nil {
			common.LogWarn("Redis connection failed (continuing without cache): %v", err)
		} else {
			common.LogInfo("Redis connected: %s", cfg.Redis.Addr)
		}
	}

	httpServer := &api.Server{
		RoomManager:    roomManager,
		MatchService:   matchService,
		ObserverMgr:    observerMgr,
		InteractionMgr: interactionMgr,
		MongoStore:     mongoStore,
		RedisStore:     redisStore,
		StatsAgg:       statsAgg,
	}

	wsServer := api.NewWSServer(connMgr, roomManager, observerMgr, interactionMgr)

	observerMgr.SetMessageHandler(func(uid common.UserID, msg *protocol.Message) {
		connMgr.SendToUser(uid, msg)
	})

	mux := http.NewServeMux()

	mux.HandleFunc("/health", httpServer.HealthCheck)

	mux.HandleFunc("/api/room/create", wrap(httpServer.CreateRoom, "POST"))
	mux.HandleFunc("/api/room/join", wrap(httpServer.JoinRoom, "POST"))
	mux.HandleFunc("/api/room/join/invite", wrap(httpServer.JoinByInvite, "POST"))
	mux.HandleFunc("/api/room/leave", wrap(httpServer.LeaveRoom, "POST"))
	mux.HandleFunc("/api/room/ready", wrap(httpServer.SetReady, "POST"))
	mux.HandleFunc("/api/room/start", wrap(httpServer.StartGame, "POST"))
	mux.HandleFunc("/api/room/disband", wrap(httpServer.DisbandRoom, "POST"))
	mux.HandleFunc("/api/room/info", httpServer.GetRoomInfo)
	mux.HandleFunc("/api/room/list", httpServer.ListRooms)

	mux.HandleFunc("/api/match/request", wrap(httpServer.RequestMatch, "POST"))
	mux.HandleFunc("/api/match/cancel", wrap(httpServer.CancelMatch, "POST"))

	mux.HandleFunc("/api/stats/player", httpServer.GetPlayerStats)
	mux.HandleFunc("/api/stats/daily", httpServer.GetDailyTrend)

	mux.HandleFunc("/ws", wsServer.HandleWebSocket)

	common.LogInfo("Routes registered, HTTP server ready")

	go func() {
		addr := cfg.Server.HTTPAddr
		common.LogInfo("Listening on %s", addr)
		if err := http.ListenAndServe(addr, mux); err != nil {
			common.LogError("HTTP server error: %v", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	common.LogInfo("Received signal %s, shutting down...", sig)

	if mongoStore != nil {
		mongoStore.Close()
	}
	if redisStore != nil {
		redisStore.Close()
	}
	common.LogInfo("Game Room Engine stopped gracefully")
}

func wrap(handler http.HandlerFunc, method string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != method {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusMethodNotAllowed)
			fmt.Fprintf(w, `{"code":405,"message":"method not allowed"}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
		handler(w, r)
	}
}
