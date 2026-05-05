package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"socialfeed/api"
	"socialfeed/api/handler"
	"socialfeed/config"
	"socialfeed/modules/audit"
	"socialfeed/modules/feed"
	"socialfeed/modules/interaction"
	"socialfeed/modules/notification"
	"socialfeed/modules/popularity"
	"socialfeed/modules/post"
	"socialfeed/modules/push"
	"socialfeed/modules/queue"
	"socialfeed/modules/relation"
	"socialfeed/storage"

	"github.com/gin-gonic/gin"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	mongoDB, err := storage.NewMongoDB(&cfg.MongoDB)
	if err != nil {
		log.Fatalf("Failed to connect to MongoDB: %v", err)
	}
	defer mongoDB.Close(context.Background())
	log.Println("Connected to MongoDB successfully")

	redisDB, err := storage.NewRedisDB(&cfg.Redis)
	if err != nil {
		log.Printf("Warning: Failed to connect to Redis: %v", err)
	} else {
		defer redisDB.Close()
		log.Println("Connected to Redis successfully")
	}

	pushQueue := queue.NewInMemoryQueue(10000)
	auditQueue := queue.NewInMemoryQueue(10000)
	defer pushQueue.Close()
	defer auditQueue.Close()

	queueManager := queue.NewQueueManager(pushQueue, auditQueue, 10, 5)

	notificationService := notification.NewNotificationService(mongoDB, redisDB)

	pushTaskHandler := push.NewPushTaskHandler(mongoDB, redisDB, notificationService)
	queueManager.RegisterPushHandler(pushTaskHandler)

	auditService := audit.NewAuditService(mongoDB)
	auditTaskHandler := audit.NewAuditTaskHandler(mongoDB, auditService.GetSensitiveWords())
	queueManager.RegisterAuditHandler(auditTaskHandler)

	asyncPushService := push.NewAsyncPushService(mongoDB, redisDB, notificationService, queueManager)

	asyncAuditService := audit.NewAsyncAuditService(mongoDB, queueManager)

	feedCacheService := feed.NewFeedCacheService(mongoDB, redisDB)

	feedService := feed.NewFeedService(mongoDB, redisDB)

	relationService := relation.NewRelationService(mongoDB, redisDB)

	popularityService := popularity.NewPopularityService(mongoDB, redisDB)

	postService := post.NewPostServiceWithAsync(
		mongoDB,
		asyncAuditService,
		relationService,
		asyncPushService,
		popularityService,
	)

	interactionService := interaction.NewInteractionService(mongoDB, postService, notificationService, popularityService)

	postHandler := handler.NewPostHandler(postService, feedService, interactionService)
	feedHandler := handler.NewFeedHandler(feedService, feedCacheService)

	router := api.NewRouter(postHandler, feedHandler)

	engine := gin.Default()
	router.SetupRoutes(engine)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	queueManager.Start(ctx)
	log.Println("Queue workers started")

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		addr := ":" + cfg.Server.Port
		log.Printf("Server starting on %s", addr)
		if err := engine.Run(addr); err != nil {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	log.Println("========================================")
	log.Println("SocialFeed Platform Started")
	log.Println("========================================")
	log.Println("Modules:")
	log.Println("  - Post Service: Running")
	log.Println("  - Async Audit Service: Running")
	log.Println("  - Async Push Service: Running")
	log.Println("  - Feed Service: Running")
	log.Println("  - Feed Cache Service: Running")
	log.Println("  - Relation Service: Running")
	log.Println("  - Interaction Service: Running")
	log.Println("  - Notification Service: Running")
	log.Println("  - Popularity Service: Running")
	log.Println("========================================")
	log.Println("Worker Pools:")
	log.Println("  - Push Workers: 10")
	log.Println("  - Audit Workers: 5")
	log.Println("========================================")

	<-stop
	log.Println("Shutting down server...")

	cancel()
	queueManager.Stop()
	log.Println("Queue workers stopped")

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()

	_ = shutdownCtx

	log.Println("Server shutdown complete")
}
