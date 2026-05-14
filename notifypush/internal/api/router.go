package api

import (
	"net/http"
)

func NewRouter(handler *Handler) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("/api/v1/notify/send", handler.SendNotification)
	mux.HandleFunc("/api/v1/notify/batch", handler.BatchSend)
	mux.HandleFunc("/api/v1/notify/status", handler.GetStatus)
	mux.HandleFunc("/api/v1/batch/status", handler.GetBatchStatus)
	mux.HandleFunc("/api/v1/template", handler.CreateTemplate)
	mux.HandleFunc("/api/v1/template/get", handler.GetTemplate)
	mux.HandleFunc("/api/v1/statistics", handler.GetStatistics)
	mux.HandleFunc("/api/v1/statistics/today", handler.GetTodayStatistics)
	mux.HandleFunc("/health", handler.HealthCheck)

	return mux
}
