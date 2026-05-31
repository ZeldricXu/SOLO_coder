package v1

import (
	"net/http"
	"time"

	"github.com/enterprise/config-platform/internal/certmanager"
	"github.com/enterprise/config-platform/internal/config"
	"github.com/enterprise/config-platform/internal/dns"
	"github.com/enterprise/config-platform/internal/engine"
	"github.com/enterprise/config-platform/internal/faultinjection"
	"github.com/enterprise/config-platform/internal/gateway"
	"github.com/enterprise/config-platform/internal/logging"
	"github.com/enterprise/config-platform/internal/monitoring"
	"github.com/enterprise/config-platform/internal/schduler"
	"github.com/enterprise/config-platform/internal/sidecar"
	"github.com/enterprise/config-platform/pkg/types"
	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type CreateResourceRequest struct {
	Type   string                 `json:"type"`
	Config map[string]interface{} `json:"config"`
	Labels map[string]string      `json:"labels"`
}

type BatchOperationRequest struct {
	Operations []BatchOperation `json:"operations"`
}

type BatchOperation struct {
	Action string `json:"action"`
	ID     string `json:"id"`
}

func CreateResource(c *gin.Context) {
	var req CreateResourceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{
		Code: 201,
		Data: gin.H{
			"id":     "rsc_" + time.Now().Format("20060102150405"),
			"status": "provisioning",
		},
	})
}

func GetResourceStatus(c *gin.Context) {
	id := c.Param("id")

	c.JSON(http.StatusOK, types.APIResponse{
		Code: 200,
		Data: gin.H{
			"id":       id,
			"status":   "running",
			"progress": 0.8,
		},
	})
}

func BatchOperations(c *gin.Context) {
	var req BatchOperationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	results := make([]gin.H, 0, len(req.Operations))
	for _, op := range req.Operations {
		results = append(results, gin.H{
			"id":      op.ID,
			"action":  op.Action,
			"status":  "success",
			"message": "Operation completed",
		})
	}

	c.JSON(http.StatusOK, types.APIResponse{
		Code: 200,
		Data: gin.H{
			"batch_id": "batch_" + time.Now().Format("20060102150405"),
			"results":  results,
		},
	})
}

func ExecuteHandler(c *gin.Context) {
	var req engine.ExecuteRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{
			Code:    400,
			Message: "Invalid request: " + err.Error(),
		})
		return
	}

	eng := engine.GetEngine()
	result, err := eng.Execute(req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{
			Code:    500,
			Message: err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{
		Code: 200,
		Data: result,
	})
}

func ListConfigs(c *gin.Context) {
	mgr := config.GetManager()
	configs := mgr.ListConfigs()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: configs})
}

func CreateConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	var params map[string]interface{}
	if err := c.ShouldBindJSON(&params); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := config.GetManager()
	cfg, err := mgr.SaveConfig(namespace, params)
	if err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: cfg})
}

func GetConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	mgr := config.GetManager()
	cfg, err := mgr.LoadConfig(namespace)
	if err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: cfg})
}

func DeleteConfig(c *gin.Context) {
	namespace := c.Param("namespace")
	mgr := config.GetManager()
	if err := mgr.DeleteConfig(namespace); err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Config deleted"})
}

func ListCertificates(c *gin.Context) {
	mgr := certmanager.GetManager()
	certs := mgr.ListCertificates()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: certs})
}

func IssueCertificate(c *gin.Context) {
	var req struct {
		CommonName string   `json:"common_name"`
		DNSNames   []string `json:"dns_names"`
		AutoRotate bool     `json:"auto_rotate"`
		RotateDays int      `json:"rotate_days"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := certmanager.GetManager()
	cert, err := mgr.IssueCertificate(req.CommonName, req.DNSNames, req.AutoRotate, req.RotateDays)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: cert})
}

func RevokeCertificate(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		Reason string `json:"reason"`
	}
	c.ShouldBindJSON(&req)

	mgr := certmanager.GetManager()
	if err := mgr.RevokeCertificate(id, req.Reason); err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Certificate revoked"})
}

func GetRotationPolicy(c *gin.Context) {
	mgr := certmanager.GetManager()
	policy := mgr.GetRotationPolicy()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: policy})
}

func SetRotationPolicy(c *gin.Context) {
	var policy certmanager.RotationPolicy
	if err := c.ShouldBindJSON(&policy); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := certmanager.GetManager()
	mgr.SetRotationPolicy(policy)
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: policy})
}

func GetRootCA(c *gin.Context) {
	mgr := certmanager.GetManager()
	c.String(http.StatusOK, mgr.GetRootCAPEM())
}

func ListSidecarConfigs(c *gin.Context) {
	mgr := sidecar.GetManager()
	configs := mgr.ListConfigs()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: configs})
}

func CreateSidecarConfig(c *gin.Context) {
	var cfg sidecar.SidecarConfig
	if err := c.ShouldBindJSON(&cfg); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := sidecar.GetManager()
	result, err := mgr.CreateConfig(&cfg)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: result})
}

func InjectSidecar(c *gin.Context) {
	var req struct {
		PodName   string            `json:"pod_name"`
		Namespace string            `json:"namespace"`
		ConfigID  string            `json:"config_id"`
		Labels    map[string]string `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := sidecar.GetManager()
	inst, err := mgr.InjectSidecar(req.PodName, req.Namespace, req.ConfigID, req.Labels)
	if err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: inst})
}

func ListSidecarInstances(c *gin.Context) {
	mgr := sidecar.GetManager()
	instances := mgr.ListInstances()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: instances})
}

func HotUpdateSidecar(c *gin.Context) {
	configID := c.Param("id")
	var req sidecar.HotUpdateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := sidecar.GetManager()
	if err := mgr.HotUpdate(configID, req.Updates); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Hot update initiated"})
}

func ListRoutes(c *gin.Context) {
	mgr := gateway.GetManager()
	routes := mgr.ListRoutes()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: routes})
}

func CreateRoute(c *gin.Context) {
	var route gateway.Route
	if err := c.ShouldBindJSON(&route); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := gateway.GetManager()
	result, err := mgr.CreateRoute(&route)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: result})
}

func CreateAPIKey(c *gin.Context) {
	var req struct {
		UserID string        `json:"user_id"`
		Roles  []string      `json:"roles"`
		TTL    time.Duration `json:"ttl_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := gateway.GetManager()
	apiKey := mgr.CreateAPIKey(req.UserID, req.Roles, time.Duration(req.TTL)*time.Second)
	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: apiKey})
}

func ListFaultScenarios(c *gin.Context) {
	mgr := faultinjection.GetManager()
	scenarios := mgr.ListScenarios()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: scenarios})
}

func CreateFaultScenario(c *gin.Context) {
	var scenario faultinjection.FaultScenario
	if err := c.ShouldBindJSON(&scenario); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := faultinjection.GetManager()
	result, err := mgr.CreateScenario(&scenario)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: result})
}

func ActivateFaultScenario(c *gin.Context) {
	id := c.Param("id")
	mgr := faultinjection.GetManager()
	scenario, err := mgr.ActivateScenario(id, nil)
	if err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: scenario})
}

func DeactivateFaultScenario(c *gin.Context) {
	id := c.Param("id")
	mgr := faultinjection.GetManager()
	scenario, err := mgr.DeactivateScenario(id)
	if err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: scenario})
}

func ListDNSUpstreams(c *gin.Context) {
	mgr := dns.GetManager()
	upstreams := mgr.ListUpstreams()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: upstreams})
}

func AddDNSUpstream(c *gin.Context) {
	var req struct {
		Address  string `json:"address"`
		Port     int    `json:"port"`
		Priority int    `json:"priority"`
		Weight   int    `json:"weight"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := dns.GetManager()
	upstream, err := mgr.AddUpstream(req.Address, req.Port, req.Priority, req.Weight)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: upstream})
}

func SetDNSStrategy(c *gin.Context) {
	var req struct {
		Strategy dns.ResolverStrategy `json:"strategy"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := dns.GetManager()
	mgr.SetStrategy(req.Strategy)
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Strategy updated"})
}

func ResolveDNS(c *gin.Context) {
	domain := c.Query("domain")
	mgr := dns.GetManager()
	resp, err := mgr.Resolve(domain, 1)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: resp})
}

func ListTasks(c *gin.Context) {
	mgr := schduler.GetManager()
	tasks := mgr.ListTasks()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: tasks})
}

func CreateTask(c *gin.Context) {
	var task schduler.Task
	if err := c.ShouldBindJSON(&task); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := schduler.GetManager()
	task.Handler = func(payload map[string]interface{}) error {
		return nil
	}

	result, err := mgr.CreateTask(&task)
	if err != nil {
		c.JSON(http.StatusInternalServerError, types.APIResponse{Code: 500, Message: err.Error()})
		return
	}

	c.JSON(http.StatusCreated, types.APIResponse{Code: 201, Data: result})
}

func TriggerTask(c *gin.Context) {
	id := c.Param("id")
	mgr := schduler.GetManager()
	if err := mgr.TriggerTask(id); err != nil {
		c.JSON(http.StatusNotFound, types.APIResponse{Code: 404, Message: err.Error()})
		return
	}

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Task triggered"})
}

func GetMetrics(c *gin.Context) {
	mgr := monitoring.GetManager()
	metrics := mgr.GetMetrics()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: metrics})
}

func GetSnapshots(c *gin.Context) {
	mgr := monitoring.GetManager()
	startTime := time.Now().Add(-24 * time.Hour)
	endTime := time.Now()

	snapshots := mgr.GetSnapshots(monitoring.SnapshotQuery{
		StartTime:  startTime,
		EndTime:    endTime,
		Dimensions: map[string]string{},
	})

	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: snapshots})
}

func PrometheusHandler() gin.HandlerFunc {
	h := promhttp.Handler()
	return func(c *gin.Context) {
		h.ServeHTTP(c.Writer, c.Request)
	}
}

func GetEngineStats(c *gin.Context) {
	eng := engine.GetEngine()
	available, total := eng.GetPoolStats()
	c.JSON(http.StatusOK, types.APIResponse{
		Code: 200,
		Data: gin.H{
			"available_resources": available,
			"total_resources":     total,
		},
	})
}

func GetConfigCacheStats(c *gin.Context) {
	mgr := config.GetManager()
	stats := mgr.GetCacheStats()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: stats})
}

func GetConfigCachedItems(c *gin.Context) {
	mgr := config.GetManager()
	items := mgr.GetCachedItems()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: items})
}

func InvalidateConfigCache(c *gin.Context) {
	var req config.CacheInvalidationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := config.GetManager()
	mgr.InvalidateCache(req)
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Cache invalidated"})
}

func SetConfigCacheConfig(c *gin.Context) {
	var cfg config.CacheConfig
	if err := c.ShouldBindJSON(&cfg); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	mgr := config.GetManager()
	mgr.SetCacheConfig(cfg)
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: cfg})
}

func GetConfigCacheConfig(c *gin.Context) {
	mgr := config.GetManager()
	cfg := mgr.GetCacheConfig()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: cfg})
}

func BatchLogHandler(c *gin.Context) {
	var req logging.BatchLogRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, types.APIResponse{Code: 400, Message: err.Error()})
		return
	}

	result := logging.BatchLog(req.Entries)
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: result})
}

func FlushLogsHandler(c *gin.Context) {
	logging.FlushLogs()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Message: "Logs flushed"})
}

func GetLogBatcherStats(c *gin.Context) {
	stats := logging.GetBatcherMetrics()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: stats})
}

func GetCertManagerStats(c *gin.Context) {
	mgr := certmanager.GetManager()
	stats := mgr.GetStats()
	c.JSON(http.StatusOK, types.APIResponse{Code: 200, Data: stats})
}
