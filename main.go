package main

import (
	"accessguard/api"
	"accessguard/audit"
	"accessguard/auth"
	"accessguard/cache"
	"accessguard/config"
	"accessguard/models"
	"accessguard/notify"
	"accessguard/permission"
	"accessguard/resource"
	"accessguard/role"
	"accessguard/session"
	"accessguard/storage"
	"accessguard/user"
	"accessguard/utils"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	cfg := config.GetEnvConfig()

	userStore := storage.NewInMemoryUserStore()
	roleStore := storage.NewInMemoryRoleStore()
	resourceStore := storage.NewInMemoryResourceStore()
	sessionStore := storage.NewInMemorySessionStore()
	auditStore := storage.NewInMemoryAuditStore()

	passwordUtils := utils.NewPasswordUtils(cfg.Password.Cost)

	userService := user.NewService(userStore, passwordUtils, cfg)
	roleService := role.NewService(roleStore, userStore)
	resourceService := resource.NewService(resourceStore)
	sessionService := session.NewService(sessionStore, cfg)

	var auditService audit.Service
	if cfg.Redis.Enabled {
		auditService = audit.NewRedisAsyncService(auditStore, cfg.Redis, 1*time.Second, 100)
		fmt.Printf("Using Redis audit buffer (Redis: %s, Queue: %s)\n", cfg.Redis.Address, cfg.Redis.AuditQueue)
	} else {
		auditService = audit.NewAsyncService(auditStore, 1000, 1*time.Second, 100)
		fmt.Println("Using memory audit buffer")
	}

	permConfigManager := permission.NewPermissionConfigManager(&cfg.Permissions)
	roleService.SetPermissionValidator(permConfigManager)
	fmt.Printf("Loaded %d permissions from config\n", len(permConfigManager.ListPermissions()))

	sessionNotify := notify.NewSessionNotify()
	sessionService.SetSessionNotifier(sessionNotify)

	permCache := cache.NewInMemoryPermissionCache()
	permissionManager := cache.NewPermissionManager(permCache, roleService, resourceService, userStore)

	roleService.SetCacheInvalidator(permissionManager)
	resourceService.SetCacheInvalidator(permissionManager)

	authService := auth.NewService(userService, sessionService, auditService, passwordUtils, cfg)
	permissionService := permission.NewService(authService, roleService, resourceService, auditService, permissionManager)

	seedData(userService, roleService, resourceService, passwordUtils, cfg)

	server := api.NewServer(cfg, authService, permissionService, auditService, userService, roleService, resourceService)

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)

	if redisSvc, ok := auditService.(*audit.RedisAsyncService); ok {
		redisSvc.Start()
	} else if memorySvc, ok := auditService.(*audit.AsyncService); ok {
		memorySvc.Start()
	}
	fmt.Println("Async audit service started")

	mux := http.NewServeMux()
	api.RegisterRoutesWithNotify(mux, server, sessionNotify)

	httpServer := &http.Server{
		Addr:    fmt.Sprintf("%s:%d", cfg.Server.Address, cfg.Server.Port),
		Handler: mux,
	}

	go func() {
		fmt.Printf("AccessGuard server starting on %s:%d...\n", cfg.Server.Address, cfg.Server.Port)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start server: %v", err)
		}
	}()

	fmt.Println("AccessGuard service started successfully")
	fmt.Printf("Listening on %s:%d\n", cfg.Server.Address, cfg.Server.Port)
	fmt.Println("WebSocket endpoint: /api/v1/ws/session?session_id=xxx&user_id=xxx")

	<-stop
	fmt.Println("\nShutting down AccessGuard service...")

	fmt.Println("Stopping async audit service...")
	if redisSvc, ok := auditService.(*audit.RedisAsyncService); ok {
		redisSvc.Stop()
	} else if memorySvc, ok := auditService.(*audit.AsyncService); ok {
		memorySvc.Stop()
	}
	fmt.Println("Async audit service stopped")

	fmt.Println("AccessGuard service shutdown complete")
}

func seedData(userService *user.Service, roleService *role.Service, resourceService *resource.Service, passwordUtils *utils.PasswordUtils, cfg *config.Config) {
	adminRole, err := roleService.CreateRole(&models.CreateRoleRequest{
		RoleName:    "系统管理员",
		Permissions: []string{"system:read", "system:write", "user:manage", "role:manage", "resource:manage", "audit:read"},
	})
	if err != nil {
		log.Printf("Failed to create admin role: %v", err)
		return
	}
	fmt.Printf("Created admin role: %s\n", adminRole.RoleID)

	userRole, err := roleService.CreateRole(&models.CreateRoleRequest{
		RoleName:    "普通用户",
		Permissions: []string{"system:read"},
	})
	if err != nil {
		log.Printf("Failed to create user role: %v", err)
		return
	}
	fmt.Printf("Created user role: %s\n", userRole.RoleID)

	adminUser, err := userService.CreateUser(&models.CreateUserRequest{
		Username: "admin",
		Password: "admin123456",
		Email:    "admin@example.com",
	})
	if err != nil {
		log.Printf("Failed to create admin user: %v", err)
		return
	}
	fmt.Printf("Created admin user: %s (password: admin123456)\n", adminUser.UserID)

	err = roleService.AssignRoleToUser(adminUser.UserID, adminRole.RoleID)
	if err != nil {
		log.Printf("Failed to assign role to admin: %v", err)
	}

	userRes, err := resourceService.CreateResource(&models.CreateResourceRequest{
		ResourceName:        "用户管理API",
		ResourceType:        "api",
		RequiredPermissions: []string{"user:manage"},
		Owner:               "system",
	})
	if err != nil {
		log.Printf("Failed to create user resource: %v", err)
		return
	}
	fmt.Printf("Created resource: %s\n", userRes.ResourceID)

	roleRes, err := resourceService.CreateResource(&models.CreateResourceRequest{
		ResourceName:        "角色管理API",
		ResourceType:        "api",
		RequiredPermissions: []string{"role:manage"},
		Owner:               "system",
	})
	if err != nil {
		log.Printf("Failed to create role resource: %v", err)
		return
	}
	fmt.Printf("Created resource: %s\n", roleRes.ResourceID)

	systemRes, err := resourceService.CreateResource(&models.CreateResourceRequest{
		ResourceName:        "系统仪表盘",
		ResourceType:        "dashboard",
		RequiredPermissions: []string{"system:read"},
		Owner:               "system",
	})
	if err != nil {
		log.Printf("Failed to create system resource: %v", err)
		return
	}
	fmt.Printf("Created resource: %s\n", systemRes.ResourceID)
}
