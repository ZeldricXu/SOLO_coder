package handlers

import (
	"net/http"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/solocoder/session147/internal/common/errors"
	"github.com/solocoder/session147/internal/common/eventbus"
	"github.com/solocoder/session147/internal/common/model"
	"github.com/solocoder/session147/internal/common/plugin"
	"github.com/solocoder/session147/internal/common/routing"
	"github.com/solocoder/session147/internal/multisig"
	"github.com/solocoder/session147/internal/gasestimator"
	"github.com/solocoder/session147/internal/txbuilder"
	"github.com/solocoder/session147/internal/indexer"
	"github.com/solocoder/session147/internal/zkp"
	"github.com/solocoder/session147/internal/hdwallet"
	"github.com/solocoder/session147/internal/storage"
	"github.com/solocoder/session147/internal/eventlistener"
	"github.com/solocoder/session147/internal/chainadapter"
	"github.com/solocoder/session147/internal/bridge"
)

type Handler struct {
	multisigSvc    multisig.MultisigService
	gasSvc          gasestimator.GasEstimatorService
	txSvc            txbuilder.TxBuilderService
	indexerSvc         indexer.IndexerService
	zkpSvc             zkp.ZKPService
	hdSvc               hdwallet.HDWalletService
	storageSvc           storage.StorageService
	eventSvc             eventlistener.EventListenerService
	chainSvc               chainadapter.ChainAdapterService
	bridgeSvc              bridge.BridgeService
	eventBus               *eventbus.EventBus
	pluginManager          *plugin.PluginManager
	readWriteRouter        *routing.ReadWriteRouter
}

func NewHandler(
	multisigSvc multisig.MultisigService,
	gasSvc gasestimator.GasEstimatorService,
	txSvc txbuilder.TxBuilderService,
	indexerSvc indexer.IndexerService,
	zkpSvc zkp.ZKPService,
	hdSvc hdwallet.HDWalletService,
	storageSvc storage.StorageService,
	eventSvc eventlistener.EventListenerService,
	chainSvc chainadapter.ChainAdapterService,
	bridgeSvc bridge.BridgeService,
	eventBus *eventbus.EventBus,
	pluginManager *plugin.PluginManager,
	readWriteRouter *routing.ReadWriteRouter,
) *Handler {
	return &Handler{
		multisigSvc:    multisigSvc,
		gasSvc:          gasSvc,
		txSvc:            txSvc,
		indexerSvc:         indexerSvc,
		zkpSvc:             zkpSvc,
		hdSvc:               hdSvc,
		storageSvc:           storageSvc,
		eventSvc:             eventSvc,
		chainSvc:               chainSvc,
		bridgeSvc:              bridgeSvc,
		eventBus:               eventBus,
		pluginManager:          pluginManager,
		readWriteRouter:        readWriteRouter,
	}
}

func (h *Handler) handleError(c *gin.Context, err error) {
	if appErr, ok := err.(*errors.AppError); ok {
		c.JSON(appErr.Code, model.ApiResponse{
			Code:    appErr.Code,
			Message: appErr.Message,
		})
		return
	}
	c.JSON(http.StatusInternalServerError, model.ApiResponse{
		Code:    http.StatusInternalServerError,
		Message: err.Error(),
	})
}

func (h *Handler) success(c *gin.Context, data interface{}) {
	c.JSON(http.StatusOK, model.ApiResponse{
		Code: http.StatusOK,
		Data: data,
	})
}

func (h *Handler) created(c *gin.Context, data interface{}) {
	c.JSON(http.StatusCreated, model.ApiResponse{
		Code: http.StatusCreated,
		Data: data,
	})
}

func (h *Handler) paginated(c *gin.Context, data interface{}, page, pageSize int, total int64) {
	c.JSON(http.StatusOK, model.ApiResponse{
		Code: http.StatusOK,
		Data: data,
		Paging: &model.Paging{
			Page:     page,
			PageSize: pageSize,
			Total:    total,
		},
	})
}

func getPagination(c *gin.Context) (int, int) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return page, pageSize
}

func getFilter(c *gin.Context) map[string]interface{} {
	filter := make(map[string]interface{})
	for k, v := range c.Request.URL.Query() {
		if strings.HasPrefix(k, "filter_") {
			key := strings.TrimPrefix(k, "filter_")
			if len(v) > 0 {
				filter[key] = v[0]
			}
		}
	}
	return filter
}
