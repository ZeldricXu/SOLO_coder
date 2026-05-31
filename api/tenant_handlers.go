package api

import (
	"encoding/json"
	"net/http"

	"session130/internal/tenant"
)

type CreateTenantRequest struct {
	Name       string `json:"name"`
	AdminEmail string `json:"admin_email"`
	Plan       string `json:"plan"`
}

type UpdateTenantConfigRequest struct {
	Config map[string]interface{} `json:"config"`
}

type UpdateBillingPlanRequest struct {
	Plan string `json:"plan"`
}

type RecordUsageRequest struct {
	Usage tenant.ResourceUsage `json:"usage"`
}

func CreateTenantHandler(w http.ResponseWriter, r *http.Request) {
	var req CreateTenantRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	plan := tenant.BillingPlan(req.Plan)
	if plan == "" {
		plan = tenant.PlanFree
	}

	tenant, err := tenant.GetManager().CreateTenant(req.Name, req.AdminEmail, plan)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusCreated, map[string]interface{}{
		"code": 201,
		"data": tenant,
	})
}

func GetTenantHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	t, err := tenant.GetManager().GetTenant(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func UpdateTenantConfigHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	var req UpdateTenantConfigRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	t, err := tenant.GetManager().UpdateTenantConfig(tenantID, req.Config)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func UpdateBillingPlanHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	var req UpdateBillingPlanRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	plan := tenant.BillingPlan(req.Plan)
	t, err := tenant.GetManager().UpdateBillingPlan(tenantID, plan)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func SuspendTenantHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	t, err := tenant.GetManager().SuspendTenant(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func ActivateTenantHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	t, err := tenant.GetManager().ActivateTenant(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func DeleteTenantHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	err := tenant.GetManager().DeleteTenant(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "tenant deleted",
	})
}

func CheckRateLimitHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	allowed, err := tenant.GetManager().CheckRateLimit(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"allowed": allowed,
		},
	})
}

func CheckResourceQuotaHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	withinQuota, err := tenant.GetManager().CheckResourceQuota(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": map[string]interface{}{
			"within_quota": withinQuota,
		},
	})
}

func RecordUsageHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	var req RecordUsageRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	t, err := tenant.GetManager().RecordUsage(tenantID, req.Usage)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": t,
	})
}

func GetUsageStatsHandler(w http.ResponseWriter, r *http.Request) {
	tenantID := r.PathValue("tenant_id")

	stats, err := tenant.GetManager().GetUsageStats(tenantID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func ListTenantsHandler(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	tenants := tenant.GetManager().ListTenants(tenant.TenantStatus(status))

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": tenants,
	})
}

func GetTenantMonitorSummaryHandler(w http.ResponseWriter, r *http.Request) {
	summary := tenant.GetMonitor().GetSummary()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": summary,
	})
}

func GetTenantOperationStatsHandler(w http.ResponseWriter, r *http.Request) {
	opStr := r.PathValue("operation")
	op := tenant.OperationType(opStr)

	stats, err := tenant.GetMonitor().GetOperationStats(op)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetAllTenantOperationStatsHandler(w http.ResponseWriter, r *http.Request) {
	stats := tenant.GetMonitor().GetAllOperationStats()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code": 200,
		"data": stats,
	})
}

func GetTenantPrometheusMetricsHandler(w http.ResponseWriter, r *http.Request) {
	metrics := tenant.GetMonitor().GetPrometheusMetrics()
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(metrics))
}

func ResetTenantMonitorHandler(w http.ResponseWriter, r *http.Request) {
	tenant.GetMonitor().ResetAll()
	writeJSON(w, http.StatusOK, map[string]interface{}{
		"code":    200,
		"message": "monitor reset successful",
	})
}
