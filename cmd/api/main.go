package main

import (
	"fmt"
	"log"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"

	"github.com/solocoder/session147/api/handlers"
	"github.com/solocoder/session147/api/router"
	"github.com/solocoder/session147/internal/common/config"
	"github.com/solocoder/session147/internal/common/eventbus"
	"github.com/solocoder/session147/internal/common/logger"
	"github.com/solocoder/session147/internal/common/plugin"
	"github.com/solocoder/session147/internal/common/routing"

	multisigdomain "github.com/solocoder/session147/internal/multisig/domain"
	multisigadapter "github.com/solocoder/session147/internal/multisig/adapter"
	multisigsvc "github.com/solocoder/session147/internal/multisig/service"

	gasdomain "github.com/solocoder/session147/internal/gasestimator/domain"
	gasadapter "github.com/solocoder/session147/internal/gasestimator/adapter"
	gassvc "github.com/solocoder/session147/internal/gasestimator/service"

	txdomain "github.com/solocoder/session147/internal/txbuilder/domain"
	txadapter "github.com/solocoder/session147/internal/txbuilder/adapter"
	txsvc "github.com/solocoder/session147/internal/txbuilder/service"

	indexerdomain "github.com/solocoder/session147/internal/indexer/domain"
	indexeradapter "github.com/solocoder/session147/internal/indexer/adapter"
	indexersvc "github.com/solocoder/session147/internal/indexer/service"

	zkpdomain "github.com/solocoder/session147/internal/zkp/domain"
	zkpadapter "github.com/solocoder/session147/internal/zkp/adapter"
	zkpsvc "github.com/solocoder/session147/internal/zkp/service"

	hdwalldomain "github.com/solocoder/session147/internal/hdwallet/domain"
	hdadapter "github.com/solocoder/session147/internal/hdwallet/adapter"
	hdsvc "github.com/solocoder/session147/internal/hdwallet/service"

	storagedomain "github.com/solocoder/session147/internal/storage/domain"
	storageadapter "github.com/solocoder/session147/internal/storage/adapter"
	storagesvc "github.com/solocoder/session147/internal/storage/service"

	eventdomain "github.com/solocoder/session147/internal/eventlistener/domain"
	eventadapter "github.com/solocoder/session147/internal/eventlistener/adapter"
	eventsvc "github.com/solocoder/session147/internal/eventlistener/service"

	chainadapterdomain "github.com/solocoder/session147/internal/chainadapter/domain"
	chainsvc "github.com/solocoder/session147/internal/chainadapter/service"

	bridgedomain "github.com/solocoder/session147/internal/bridge/domain"
	bridgeadapter "github.com/solocoder/session147/internal/bridge/adapter"
	bridgesvc "github.com/solocoder/session147/internal/bridge/service"
)

func main() {
	cfg, err := config.Load("configs/config.yaml")
	if err != nil {
		log.Printf("Warning: Failed to load config: %v, using defaults", err)
		cfg = &config.AppConfig{
			Env: "development",
			Server: config.ServerConfig{
				Host: "0.0.0.0",
				Port: 8080,
			},
		}
	}

	loggerLevel := "info"
	if level, ok := cfg.Logger["level"].(string); ok {
		loggerLevel = level
	}
	logger.Init(logger.Config{
		Level:   loggerLevel,
		DevMode: cfg.Env != "production",
	})
	defer logger.Sync()

	gin.SetMode(gin.ReleaseMode)
	if cfg.Env == "development" {
		gin.SetMode(gin.DebugMode)
	}

	var db *gorm.DB
	if cfg.Database.Host != "" {
		db, err = gorm.Open(postgres.Open(cfg.DSN()), &gorm.Config{})
		if err != nil {
			logger.Fatal("Failed to connect to database", zap.Error(err))
		}

		_ = db.AutoMigrate(
			&multisigdomain.Wallet{},
			&multisigdomain.Proposal{},
			&gasdomain.GasPriceData{},
			&txdomain.Transaction{},
			&indexerdomain.BlockIndex{},
			&indexerdomain.TransactionIndex{},
			&indexerdomain.LogIndex{},
			&zkpdomain.ZKPProof{},
			&zkpdomain.Circuit{},
			&hdwalldomain.HDWallet{},
			&hdwalldomain.DerivedAddress{},
			&hdwalldomain.AddressBookEntry{},
			&storagedomain.StoredContent{},
			&storagedomain.PinOperation{},
			&eventdomain.EventSubscription{},
			&eventdomain.EventLogEntry{},
			&bridgedomain.BridgeTransaction{},
		)
	}

	chainAdapter := chainsvc.NewChainAdapterService()
	for _, chainCfg := range cfg.Chains {
		chainConfig := &chainadapterdomain.ChainConfig{
			ChainID:  chainCfg.ChainID,
			Name:     chainCfg.Name,
			RPCURL:   chainCfg.RPCURL,
			WSURL:    chainCfg.WSURL,
			Explorer: chainCfg.Explorer,
		}
		_ = chainAdapter.RegisterChain(chainConfig)
	}

	eventBus := eventbus.NewEventBus()

	pluginManager := plugin.NewPluginManager()

	readWriteRouter := routing.NewReadWriteRouter(routing.RouterConfig{
		PrimaryNode: routing.DatabaseNode{
			ID:     "primary",
			Role:   string(routing.RolePrimary),
			Weight: 10,
		},
	})

	walletRepo := multisigadapter.NewGormWalletRepository(db)
	proposalRepo := multisigadapter.NewGormProposalRepository(db)
	multisigService := multisigsvc.NewMultisigService(walletRepo, proposalRepo, chainAdapter, readWriteRouter)

	gasRepo := gasadapter.NewGormGasDataRepository(db)
	gasService := gassvc.NewGasEstimatorService(gasRepo, chainAdapter, eventBus)

	txRepo := txadapter.NewGormTxRepository(db)
	txService := txsvc.NewTxBuilderService(txRepo, chainAdapter, gasService, pluginManager)

	indexerRepo := indexeradapter.NewGormIndexRepository(db)
	indexerService := indexersvc.NewIndexerService(indexerRepo, chainAdapter)

	zkpRepo := zkpadapter.NewGormZKPRepository(db)
	zkpService := zkpsvc.NewZKPService(zkpRepo, nil)

	hdRepo := hdadapter.NewGormHDWalletRepository(db)
	hdService := hdsvc.NewHDWalletService(hdRepo, nil)

	storageRepo := storageadapter.NewGormStorageRepository(db)
	storageService := storagesvc.NewStorageService(storageRepo)

	eventRepo := eventadapter.NewGormEventRepository(db)
	eventService := eventsvc.NewEventListenerService(eventRepo, chainAdapter, nil)

	bridgeRepo := bridgeadapter.NewGormBridgeRepository(db)
	bridgeService := bridgesvc.NewBridgeService(bridgeRepo, nil, nil)

	handler := handlers.NewHandler(
		multisigService,
		gasService,
		txService,
		indexerService,
		zkpService,
		hdService,
		storageService,
		eventService,
		chainAdapter,
		bridgeService,
		eventBus,
		pluginManager,
		readWriteRouter,
	)

	r := router.SetupRouter(handler)

	addr := fmt.Sprintf("%s:%d", cfg.Server.Host, cfg.Server.Port)
	logger.Info("Server starting", zap.String("addr", addr))
	if err := r.Run(addr); err != nil {
		logger.Fatal("Failed to start server", zap.Error(err))
	}
}
