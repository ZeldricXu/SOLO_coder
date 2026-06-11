package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"github.com/studio/gameroom/pkg/api"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/config"
	"github.com/studio/gameroom/pkg/match"
	"github.com/studio/gameroom/pkg/network"
	"github.com/studio/gameroom/pkg/observer"
	"github.com/studio/gameroom/pkg/protocol"
	"github.com/studio/gameroom/pkg/room"
	"github.com/studio/gameroom/pkg/storage"

	_ "github.com/studio/gameroom/pkg/games/landlord"
	_ "github.com/studio/gameroom/pkg/games/mahjong"
	_ "github.com/studio/gameroom/pkg/games/texas"
)

func main() {
	config.Info.GoVersion = runtime.Version()

	showVersion := flag.Bool("version", false, "print build info and exit")
	configFile := flag.String("config", "", "config file path (optional, env overrides apply)")
	envFile := flag.String("env", ".env", "dotenv file path (local development)")
	flag.Parse()

	if *showVersion {
		fmt.Println(config.Info.String())
		return
	}

	_ = *envFile
	cfg, err := loadConfig(*configFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "config load failed: %v\n", err)
		os.Exit(1)
	}

	setLogLevel(cfg.LogLevel)

	common.LogInfo("=== Game Room Engine Starting ===")
	common.LogInfo("Build: %s", config.Info.String())
	common.LogInfo("Env=%s LogLevel=%s Addr=%s", cfg.Env, cfg.LogLevel, cfg.Server.HTTPAddr)

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
			common.LogInfo("MongoDB connected: %s/%s", maskURI(cfg.Mongo.URI), cfg.Mongo.Database)
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
	mux.HandleFunc("/version", handleVersion)
	mux.HandleFunc("/metrics", handleMetrics)

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

	srv := &http.Server{
		Addr:         cfg.Server.HTTPAddr,
		Handler:      mux,
		ReadTimeout:  time.Duration(cfg.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.Server.WriteTimeout) * time.Second,
	}

	go func() {
		common.LogInfo("Listening on %s", cfg.Server.HTTPAddr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			common.LogError("HTTP server error: %v", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigCh
	common.LogInfo("Received signal %s, shutting down gracefully (30s timeout)...", sig)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		common.LogWarn("HTTP shutdown error: %v", err)
	}

	matchService.Stop()
	observerMgr.StopDrainLoop()

	if mongoStore != nil {
		mongoStore.Close()
	}
	if redisStore != nil {
		redisStore.Close()
	}

	common.LogInfo("Game Room Engine stopped gracefully")
}

func loadConfig(configFile string) (*config.Config, error) {
	if configFile != "" {
		return config.Load(configFile)
	}
	return config.LoadDotenv()
}

func setLogLevel(level string) {
	switch level {
	case "debug":
		common.SetLogLevel(common.LevelDebug)
	case "warn", "warning":
		common.SetLogLevel(common.LevelWarn)
	case "error":
		common.SetLogLevel(common.LevelError)
	default:
		common.SetLogLevel(common.LevelInfo)
	}
}

func handleVersion(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"version":%q,"commit":%q,"built":%q,"go":%q}`,
		config.Info.Version, config.Info.Commit, config.Info.BuildTime, config.Info.GoVersion)
}

func handleMetrics(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	numGoroutine := runtime.NumGoroutine()

	fmt.Fprintf(w, "# HELP gameroom_goroutines Goroutine count\n")
	fmt.Fprintf(w, "# TYPE gameroom_goroutines gauge\n")
	fmt.Fprintf(w, "gameroom_goroutines %d\n", numGoroutine)
	fmt.Fprintf(w, "# HELP gameroom_heap_alloc_bytes Heap allocated bytes\n")
	fmt.Fprintf(w, "# TYPE gameroom_heap_alloc_bytes gauge\n")
	fmt.Fprintf(w, "gameroom_heap_alloc_bytes %d\n", m.HeapAlloc)
	fmt.Fprintf(w, "# HELP gameroom_version Build info\n")
	fmt.Fprintf(w, "# TYPE gameroom_version gauge\n")
	fmt.Fprintf(w, "gameroom_version{version=%q,commit=%q} 1\n",
		config.Info.Version, config.Info.Commit)
}

func maskURI(uri string) string {
	return uri
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
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		handler(w, r)
	}
}
