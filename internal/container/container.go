package container

import (
	"context"
	"sync"

	"github.com/edgevision/edgevision/internal/domain/aggregation"
	"github.com/edgevision/edgevision/internal/domain/offline"
	"github.com/edgevision/edgevision/internal/domain/ota"
	"github.com/edgevision/edgevision/internal/infrastructure/cache"
	"github.com/edgevision/edgevision/internal/infrastructure/mqtt"
	repo_agg "github.com/edgevision/edgevision/internal/infrastructure/repository/aggregation"
	repo_offline "github.com/edgevision/edgevision/internal/infrastructure/repository/offline"
	repo_ota "github.com/edgevision/edgevision/internal/infrastructure/repository/ota"
	agg_service "github.com/edgevision/edgevision/internal/service/aggregation"
	offline_service "github.com/edgevision/edgevision/internal/service/offline"
	ota_service "github.com/edgevision/edgevision/internal/service/ota"
	"github.com/edgevision/edgevision/internal/infrastructure/wal"
	"github.com/redis/go-redis/v9"
	"gorm.io/gorm"
)

type Container struct {
	db     *gorm.DB
	redis  *redis.Client
	mqtt   *mqtt.Client
	wal    *wal.WAL
	cache  *cache.Cache

	repos   *Repositories
	services *Services

	once sync.Once
}

type Repositories struct {
	FirmwareRepo        ota.FirmwareRepository
	DeltaPackageRepo    ota.DeltaPackageRepository
	UpgradeTaskRepo     ota.UpgradeTaskRepository
	DeviceUpgradeRepo   ota.DeviceUpgradeRepository
	DataRecordRepo      offline.DataRecordRepository
	SyncSessionRepo     offline.SyncSessionRepository
	DataStreamRepo      aggregation.DataStreamRepository
	RawDataPointRepo    aggregation.RawDataPointRepository
	AggregatedDataRepo  aggregation.AggregatedDataRepository
}

type Services struct {
	OTAService         ota.OTAService
	OfflineService     offline.OfflineService
	AggregationService aggregation.DataAggregationService
}

func NewContainer(db *gorm.DB, redisClient *redis.Client, mqttClient *mqtt.Client, wal *wal.WAL, cacheInst *cache.Cache) *Container {
	return &Container{
		db:    db,
		redis: redisClient,
		mqtt:  mqttClient,
		wal:   wal,
		cache: cacheInst,
	}
}

func (c *Container) Init(ctx context.Context) {
	c.once.Do(func() {
		c.initRepositories()
		c.initServices(ctx)
	})
}

func (c *Container) initRepositories() {
	c.repos = &Repositories{
		FirmwareRepo:       repo_ota.NewFirmwareRepository(c.db),
		DeltaPackageRepo:   repo_ota.NewDeltaPackageRepository(c.db),
		UpgradeTaskRepo:    repo_ota.NewUpgradeTaskRepository(c.db),
		DeviceUpgradeRepo:  repo_ota.NewDeviceUpgradeRepository(c.db),
		DataRecordRepo:     repo_offline.NewDataRecordRepository(c.db),
		SyncSessionRepo:    repo_offline.NewSyncSessionRepository(c.db),
		DataStreamRepo:     repo_agg.NewDataStreamRepository(c.db),
		RawDataPointRepo:   repo_agg.NewRawDataPointRepository(c.db),
		AggregatedDataRepo: repo_agg.NewAggregatedDataRepository(c.db),
	}
}

func (c *Container) initServices(ctx context.Context) {
	otaEventPublisher := ota_service.NewEventPublisher(c.mqtt, c.cache)
	offlineEventPublisher := offline_service.NewEventPublisher(c.cache)
	aggEventPublisher := agg_service.NewEventPublisher(c.cache)

	cloudUploader := offline_service.NewCloudUploader()

	otaConfigManager := ota_service.NewConfigManager(c.cache)

	otaSvc := ota_service.NewService(
		c.repos.FirmwareRepo,
		c.repos.DeltaPackageRepo,
		c.repos.UpgradeTaskRepo,
		c.repos.DeviceUpgradeRepo,
		otaEventPublisher,
		otaConfigManager,
	)

	strategyManager := offline.NewStrategyRegistry()

	offlineSvc := offline_service.NewService(
		c.repos.DataRecordRepo,
		c.repos.SyncSessionRepo,
		cloudUploader,
		offlineEventPublisher,
		strategyManager,
	)
	offlineSvc.Start(ctx)

	asyncManager := agg_service.NewAsyncTaskManager(
		c.repos.DataStreamRepo,
		c.repos.RawDataPointRepo,
		c.repos.AggregatedDataRepo,
		aggEventPublisher,
		3,
	)
	asyncManager.Start(ctx)

	aggSvc := agg_service.NewService(
		c.repos.DataStreamRepo,
		c.repos.RawDataPointRepo,
		c.repos.AggregatedDataRepo,
		aggEventPublisher,
		asyncManager,
	)

	c.services = &Services{
		OTAService:         otaSvc,
		OfflineService:     offlineSvc,
		AggregationService: aggSvc,
	}
}

func (c *Container) GetOTAService() ota.OTAService {
	return c.services.OTAService
}

func (c *Container) GetOfflineService() offline.OfflineService {
	return c.services.OfflineService
}

func (c *Container) GetAggregationService() aggregation.DataAggregationService {
	return c.services.AggregationService
}

func (c *Container) Close(ctx context.Context) {
	if c.services != nil {
		if offlineSvc, ok := c.services.OfflineService.(*offline_service.Service); ok {
			offlineSvc.Stop()
		}
		if aggSvc, ok := c.services.AggregationService.(*agg_service.Service); ok {
			if asyncManager := aggSvc.GetAsyncManager(); asyncManager != nil {
				if mgr, ok := asyncManager.(*agg_service.AsyncTaskManagerImpl); ok {
					mgr.Stop()
				}
			}
		}
	}
}
