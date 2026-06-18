package router

import (
	"context"
	"net/http"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/handler"
	"github.com/enterprise/knowledgebase/internal/middleware"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/enterprise/knowledgebase/internal/search"
	"github.com/enterprise/knowledgebase/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

type RouterContainer struct {
	AuthHandler         *handler.AuthHandler
	DocumentHandler     *handler.DocumentHandler
	OTHandler           *handler.OTHandler
	SpaceHandler        *handler.SpaceHandler

	AuthSvc             *service.AuthService
	DocSvc              *service.DocumentService
	SearchSvc           *service.SearchService
	OTSvc               *service.OTService
	IeSvc               *service.ImportExportService

	TenantRepo          *repository.TenantRepository
	UserRepo            *repository.UserRepository
	PermissionRepo      *repository.PermissionRepository
	SpaceRepo           *repository.SpaceRepository
	DocumentRepo        *repository.DocumentRepository

	JwtCfg              config.JWTConfig
	CorsCfg             config.CORSConfig
	RedisClient         *database.RedisClient
	DB                  *gorm.DB
}

func NewRouter(cfg *config.Config, r *gin.Engine) (*RouterContainer, error) {
	db, err := database.InitPostgreSQL(cfg.PostgreSQL)
	if err != nil {
		return nil, err
	}
	redisClient, err := database.InitRedis(cfg.Redis)
	if err != nil {
		return nil, err
	}
	minioClient, err := database.InitMinIO(cfg.MinIO)
	if err != nil {
		return nil, err
	}
	bleveMgr, err := database.InitBleve(cfg.Bleve)
	if err != nil {
		return nil, err
	}

	indexManager, err := search.NewIndexManager(cfg.Bleve.IndexPath)
	if err != nil {
		return nil, err
	}

	tikaClient := search.NewTikaClient(search.TikaConfig{
		Endpoint: search.DefaultTikaEndpoint,
		Timeout:  search.DefaultTikaTimeout,
	})

	tenantRepo := repository.NewTenantRepository(db)
	userRepo := repository.NewUserRepository(db)
	permRepo := repository.NewPermissionRepository(db)
	spaceRepo := repository.NewSpaceRepository(db)
	documentRepo := repository.NewDocumentRepository(db)
	versionRepo := repository.NewVersionRepository(db)
	i18nRepo := repository.NewI18nRepository(db)

	authSvc := service.NewAuthService(userRepo, tenantRepo, permRepo, cfg.JWT)
	searchSvc := service.NewSearchService(indexManager, tikaClient, documentRepo, spaceRepo, permRepo, bleveMgr)
	docSvc := service.NewDocumentService(documentRepo, spaceRepo, permRepo, tenantRepo, searchSvc, minioClient)
	otSvc := service.NewOTService(redisClient, documentRepo, cfg.OT)
	ieSvc := service.NewImportExportService(documentRepo, spaceRepo, versionRepo, i18nRepo, permRepo)

	authHandler := handler.NewAuthHandler(authSvc)
	docHandler := handler.NewDocumentHandler(docSvc, searchSvc, ieSvc, userRepo)
	otHandler := handler.NewOTHandler(otSvc)

	spaceHandler := handler.NewSpaceHandler(
		&spaceRepoAdapter{spaceRepo: spaceRepo},
		&permRepoAdapter{permRepo: permRepo},
	)

	container := &RouterContainer{
		AuthHandler:     authHandler,
		DocumentHandler: docHandler,
		OTHandler:       otHandler,
		SpaceHandler:    spaceHandler,
		AuthSvc:         authSvc,
		DocSvc:          docSvc,
		SearchSvc:       searchSvc,
		OTSvc:           otSvc,
		IeSvc:           ieSvc,
		TenantRepo:      tenantRepo,
		UserRepo:        userRepo,
		PermissionRepo:  permRepo,
		SpaceRepo:       spaceRepo,
		DocumentRepo:    documentRepo,
		JwtCfg:          cfg.JWT,
		CorsCfg:         cfg.CORS,
		RedisClient:     redisClient,
		DB:              db,
	}

	r.GET("/healthz", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"status": "ok",
			"postgres": "connected",
			"redis":    "connected",
			"version":  "1.0.0",
		})
	})

	api := r.Group("/api/v1")
	api.Use(middleware.CORSMiddleware(cfg.CORS))
	{
		public := api.Group("")
		{
			public.POST("/auth/login", authHandler.Login)
			public.POST("/auth/register", authHandler.Register)
		}

		authed := api.Group("")
		authed.Use(middleware.AuthMiddleware(cfg.JWT, userRepo, tenantRepo))
		authed.Use(middleware.RateLimitMiddleware(redisClient, userRepo))
		{
			authGroup := authed.Group("/auth")
			{
				authGroup.POST("/refresh", authHandler.RefreshToken)
				authGroup.POST("/api-tokens", middleware.APITokenScopeMiddleware("token:write"), authHandler.CreateAPIToken)
			}

			searchGroup := authed.Group("/search")
			searchGroup.Use(middleware.QuotaMiddleware(tenantRepo, "api_calls"))
			{
				searchGroup.GET("", docHandler.Search)
				searchGroup.GET("/suggest", docHandler.Suggest)
			}

			docGroup := authed.Group("/documents")
			{
				docGroup.GET("", docHandler.List)
				docGroup.POST("",
					middleware.QuotaMiddleware(tenantRepo, "api_calls"),
					docHandler.Create,
				)
				docGroup.GET("/:id", docHandler.Get)
				docGroup.PUT("/:id", docHandler.Update)
				docGroup.DELETE("/:id", docHandler.Delete)
				docGroup.GET("/:id/diff", docHandler.GetDiff)
				docGroup.POST("/attachments", docHandler.UploadAttachment)
			}

			otGroup := authed.Group("/ot")
			{
				otGroup.POST("/connect/:doc_id", otHandler.Connect)
				otGroup.POST("/disconnect/:doc_id", otHandler.Disconnect)
				otGroup.POST("/ops/:doc_id", otHandler.SubmitOps)
				otGroup.GET("/presence/:doc_id", otHandler.Presence)
			}

			exportGroup := authed.Group("/export")
			{
				exportGroup.GET("/json", middleware.APITokenScopeMiddleware("export:read"), docHandler.ExportJSON)
				exportGroup.GET("/html", middleware.APITokenScopeMiddleware("export:read"), docHandler.ExportHTML)
			}

			importGroup := authed.Group("/import")
			{
				importGroup.POST("/markdown", middleware.APITokenScopeMiddleware("import:write"), docHandler.ImportMarkdown)
			}

			_ = spaceHandler
			_ = cfg
		}
	}

	r.NoRoute(func(c *gin.Context) {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "Route not found",
		})
	})

	return container, nil
}

type spaceRepoAdapter struct {
	spaceRepo *repository.SpaceRepository
}

func (s *spaceRepoAdapter) Create(ctx context.Context, space *model.Space) error {
	return s.spaceRepo.Create(ctx, space)
}
func (s *spaceRepoAdapter) GetByID(ctx context.Context, id uuid.UUID) (*model.Space, error) {
	return s.spaceRepo.GetByID(ctx, id)
}
func (s *spaceRepoAdapter) List(ctx context.Context, tenantID uuid.UUID, ids []uuid.UUID, status model.SpaceStatus, keyword string, page, pageSize int) (*gin.H, error) {
	return nil, nil
}
func (s *spaceRepoAdapter) Update(ctx context.Context, space *model.Space) error {
	return s.spaceRepo.Update(ctx, space)
}
func (s *spaceRepoAdapter) Delete(ctx context.Context, id uuid.UUID) error {
	return s.spaceRepo.Delete(ctx, id)
}

type permRepoAdapter struct {
	permRepo *repository.PermissionRepository
}

func (p *permRepoAdapter) GrantRole(ctx context.Context, resourceType model.ResourceType, resourceID uuid.UUID, subjectType model.SubjectType, subjectID uuid.UUID, role model.Role, grantedBy uuid.UUID) error {
	return p.permRepo.GrantRole(ctx, resourceType, resourceID, subjectType, subjectID, role, grantedBy)
}
