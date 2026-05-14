package main

import (
	"fmt"
	"log"
	"net/http"
	"notifypush/internal/api"
	"notifypush/internal/channels"
	"notifypush/internal/config"
	"notifypush/internal/models"
	"notifypush/internal/services"
	"notifypush/internal/storage"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"
)

func main() {
	cfg := config.DefaultConfig()

	storage := storage.NewMemoryStorage()

	redisClient, err := storage.NewRedisClient(&cfg.Redis)
	if err != nil {
		log.Printf("Warning: Failed to create Redis client, using mock: %v", err)
	}

	channelRegistry := channels.NewChannelRegistry()
	smsChannel := channels.NewSMSChannel(
		cfg.SMS.Provider,
		cfg.SMS.APIKey,
		cfg.SMS.SignName,
		cfg.SMS.TemplateCode,
	)
	channelRegistry.Register(models.ChannelTypeSMS, smsChannel)

	emailChannel := channels.NewEmailChannel(
		cfg.Email.SMTPHost,
		cfg.Email.SMTPPort,
		cfg.Email.Username,
		cfg.Email.Password,
		cfg.Email.FromName,
		cfg.Email.FromAddr,
	)
	channelRegistry.Register(models.ChannelTypeEmail, emailChannel)

	appPushChannel := channels.NewAppPushChannel(
		cfg.AppPush.Provider,
		cfg.AppPush.APIKey,
		cfg.AppPush.ProjectID,
	)
	channelRegistry.Register(models.ChannelTypeApp, appPushChannel)

	templateService := services.NewTemplateService(storage)
	statusTracker := services.NewStatusTracker(storage)
	statisticsService := services.NewStatisticsService(storage)

	retryService := services.NewRetryService(storage, channelRegistry, statusTracker, statisticsService)
	retryService.SetMaxRetries(cfg.Retry.MaxRetries)
	retryService.SetRetryDelay(cfg.Retry.RetryDelayMs)

	statusQueryService := services.NewStatusQueryService(storage, &cfg.StatusQuery, statusTracker)
	statusQueryService.Start()
	defer statusQueryService.Stop()

	smsQueueService := services.NewSMSQueueService(
		redisClient,
		channelRegistry,
		storage,
		statusTracker,
		statisticsService,
		&cfg.SMSQueue,
	)
	smsQueueService.Start()
	defer smsQueueService.Stop()

	batchQueueService := services.NewBatchQueueService(
		redisClient,
		channelRegistry,
		storage,
		templateService,
		statusTracker,
		statisticsService,
		retryService,
		&cfg.BatchQueue,
	)
	batchQueueService.Start()
	defer batchQueueService.Stop()

	notificationService := services.NewNotificationService(
		storage,
		channelRegistry,
		templateService,
		statusTracker,
		retryService,
		statisticsService,
	)
	notificationService.WithSMSQueue(smsQueueService, statusQueryService)

	batchService := services.NewBatchService(
		storage,
		channelRegistry,
		templateService,
		statusTracker,
		retryService,
		statisticsService,
	)
	batchService.WithBatchQueue(batchQueueService)

	handler := api.NewHandler(notificationService, batchService, templateService, statisticsService)
	router := api.NewRouter(handler)

	seedDefaultTemplates(templateService)

	fmt.Println("===========================================")
	fmt.Println("   NotifyPush 统一消息通知推送服务")
	fmt.Println("===========================================")
	fmt.Printf("服务启动中, 端口: %d\n", cfg.Server.Port)
	fmt.Printf("启动时间: %s\n", time.Now().Format("2006-01-02 15:04:05"))
	fmt.Println("-------------------------------------------")
	fmt.Println("功能增强:")
	fmt.Println("  ✓ 短信任务Redis队列持久化")
	fmt.Println("  ✓ 批量任务Redis队列持久化")
	fmt.Println("  ✓ 状态查询间隔动态化")
	fmt.Println("  ✓ 模板变量表达式引擎")
	fmt.Println("-------------------------------------------")
	fmt.Println("API 接口:")
	fmt.Println("  POST /api/v1/template       - 创建模板")
	fmt.Println("  GET  /api/v1/template/get   - 获取模板")
	fmt.Println("  POST /api/v1/notify/send    - 单条通知发送")
	fmt.Println("  POST /api/v1/notify/batch   - 批量通知发送")
	fmt.Println("  GET  /api/v1/notify/status  - 通知状态查询")
	fmt.Println("  GET  /api/v1/batch/status   - 批量任务状态")
	fmt.Println("  GET  /api/v1/statistics     - 发送统计")
	fmt.Println("  GET  /api/v1/statistics/today - 今日统计")
	fmt.Println("  GET  /health                - 健康检查")
	fmt.Println("-------------------------------------------")
	fmt.Println("状态查询间隔配置:")
	fmt.Printf("  Urgent: %d秒\n", cfg.StatusQuery.UrgentIntervalSec)
	fmt.Printf("  High:   %d秒\n", cfg.StatusQuery.HighIntervalSec)
	fmt.Printf("  Medium: %d秒\n", cfg.StatusQuery.MediumIntervalSec)
	fmt.Printf("  Low:    %d秒\n", cfg.StatusQuery.LowIntervalSec)
	fmt.Println("-------------------------------------------")

	addr := ":" + strconv.Itoa(cfg.Server.Port)
	server := &http.Server{
		Addr:         addr,
		Handler:      router,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		log.Printf("NotifyPush server started on %s", addr)
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Server error: %v", err)
		}
	}()

	<-stop
	log.Println("Shutting down server...")
}

func seedDefaultTemplates(templateService *services.TemplateService) {
	templates := []*models.TemplateCreateRequest{
		{
			TemplateID:      "template_order_success",
			TemplateName:    "订单成功通知",
			TemplateType:    "sms",
			TemplateContent: "您的订单{order_id}已成功提交，金额{amount}元",
			Variables:       []string{"order_id", "amount"},
		},
		{
			TemplateID:      "template_order_success_email",
			TemplateName:    "订单成功邮件通知",
			TemplateType:    "email",
			TemplateContent: "尊敬的用户您好，您的订单{order_id}已成功提交，订单金额{amount}元。感谢您的购买！",
			Subject:         "订单提交成功通知",
			Variables:       []string{"order_id", "amount"},
		},
		{
			TemplateID:      "template_promo",
			TemplateName:    "促销活动通知",
			TemplateType:    "sms",
			TemplateContent: "【促销通知】{product}限时{discount}折优惠，活动截止{end_date}",
			Variables:       []string{"product", "discount", "end_date"},
		},
		{
			TemplateID:      "template_app_push",
			TemplateName:    "App推送通知",
			TemplateType:    "app",
			TemplateContent: "{title}: {message}",
			Subject:         "系统通知",
			Variables:       []string{"title", "message"},
		},
		{
			TemplateID:      "template_expression_demo",
			TemplateName:    "表达式引擎演示",
			TemplateType:    "sms",
			TemplateContent: "您好{{username}},您的订单{{order_id}}状态:{{if order_status == \"paid\", \"已支付\", \"待支付\"}}",
			Variables:       []string{"username", "order_id", "order_status"},
		},
	}

	for _, tmpl := range templates {
		_, err := templateService.CreateTemplate(tmpl)
		if err != nil {
			log.Printf("Warning: Failed to create template %s: %v", tmpl.TemplateID, err)
		} else {
			log.Printf("Created default template: %s", tmpl.TemplateID)
		}
	}
}
