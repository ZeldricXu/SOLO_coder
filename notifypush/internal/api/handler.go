package api

import (
	"encoding/json"
	"io/ioutil"
	"net/http"
	"notifypush/internal/models"
	"notifypush/internal/services"
)

type Handler struct {
	notificationService *services.NotificationService
	batchService        *services.BatchService
	templateService     *services.TemplateService
	statisticsService   *services.StatisticsService
}

func NewHandler(
	notificationService *services.NotificationService,
	batchService *services.BatchService,
	templateService *services.TemplateService,
	statisticsService *services.StatisticsService,
) *Handler {
	return &Handler{
		notificationService: notificationService,
		batchService:        batchService,
		templateService:     templateService,
		statisticsService:   statisticsService,
	}
}

func (h *Handler) SendNotification(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid request body")
		return
	}
	defer r.Body.Close()

	var req models.SendRequest
	err = json.Unmarshal(body, &req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid json format")
		return
	}

	response, err := h.notificationService.SendNotification(&req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, err.Error())
		return
	}

	writeSuccessResponse(w, response)
}

func (h *Handler) BatchSend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid request body")
		return
	}
	defer r.Body.Close()

	var req models.BatchSendRequest
	err = json.Unmarshal(body, &req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid json format")
		return
	}

	response, err := h.batchService.CreateBatchTask(&req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, err.Error())
		return
	}

	writeSuccessResponse(w, response)
}

func (h *Handler) GetStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	notifyID := r.URL.Query().Get("notify_id")
	if notifyID == "" {
		writeErrorResponse(w, http.StatusBadRequest, "notify_id is required")
		return
	}

	status, err := h.notificationService.GetNotificationStatus(notifyID)
	if err != nil {
		writeErrorResponse(w, http.StatusNotFound, err.Error())
		return
	}

	writeSuccessResponse(w, status)
}

func (h *Handler) GetBatchStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	batchID := r.URL.Query().Get("batch_id")
	if batchID == "" {
		writeErrorResponse(w, http.StatusBadRequest, "batch_id is required")
		return
	}

	stats, err := h.batchService.GetBatchStatus(batchID)
	if err != nil {
		writeErrorResponse(w, http.StatusNotFound, err.Error())
		return
	}

	writeSuccessResponse(w, stats)
}

func (h *Handler) CreateTemplate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	body, err := ioutil.ReadAll(r.Body)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid request body")
		return
	}
	defer r.Body.Close()

	var req models.TemplateCreateRequest
	err = json.Unmarshal(body, &req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, "invalid json format")
		return
	}

	template, err := h.templateService.CreateTemplate(&req)
	if err != nil {
		writeErrorResponse(w, http.StatusBadRequest, err.Error())
		return
	}

	writeSuccessResponse(w, template)
}

func (h *Handler) GetTemplate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	templateID := r.URL.Query().Get("template_id")
	if templateID == "" {
		writeErrorResponse(w, http.StatusBadRequest, "template_id is required")
		return
	}

	template, err := h.templateService.GetTemplate(templateID)
	if err != nil {
		writeErrorResponse(w, http.StatusNotFound, err.Error())
		return
	}

	writeSuccessResponse(w, template)
}

func (h *Handler) GetStatistics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	date := r.URL.Query().Get("date")
	channel := r.URL.Query().Get("channel")

	if date == "" || channel == "" {
		writeErrorResponse(w, http.StatusBadRequest, "date and channel are required")
		return
	}

	stats, err := h.statisticsService.GetStatistics(date, channel)
	if err != nil {
		writeErrorResponse(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeSuccessResponse(w, stats)
}

func (h *Handler) GetTodayStatistics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeErrorResponse(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	channel := r.URL.Query().Get("channel")
	if channel == "" {
		writeErrorResponse(w, http.StatusBadRequest, "channel is required")
		return
	}

	stats, err := h.statisticsService.GetTodayStatistics(channel)
	if err != nil {
		writeErrorResponse(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeSuccessResponse(w, stats)
}

func (h *Handler) HealthCheck(w http.ResponseWriter, r *http.Request) {
	writeSuccessResponse(w, map[string]string{"status": "healthy", "service": "NotifyPush"})
}

func writeSuccessResponse(w http.ResponseWriter, data interface{}) {
	response := models.ApiResponse{
		Code: http.StatusOK,
		Data: data,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(response)
}

func writeErrorResponse(w http.ResponseWriter, code int, message string) {
	response := models.ApiResponse{
		Code: code,
		Msg:  message,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(response)
}
