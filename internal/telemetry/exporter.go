package telemetry

import (
	"net/http"
	"net/http/pprof"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type MetricsExporter struct {
	registry *prometheus.Registry
	mux      *http.ServeMux
}

func NewMetricsExporter() *MetricsExporter {
	registry := GetPrometheusRegistry()

	mux := http.NewServeMux()

	e := &MetricsExporter{
		registry: registry,
		mux:      mux,
	}

	mux.Handle("/metrics", promhttp.HandlerFor(registry, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", e.handleHealthz)
	mux.HandleFunc("/debug/pprof/", pprof.Index)
	mux.HandleFunc("/debug/pprof/cmdline", pprof.Cmdline)
	mux.HandleFunc("/debug/pprof/profile", pprof.Profile)
	mux.HandleFunc("/debug/pprof/symbol", pprof.Symbol)
	mux.HandleFunc("/debug/pprof/trace", pprof.Trace)

	return e
}

func (e *MetricsExporter) handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func (e *MetricsExporter) Handler() http.Handler {
	return e.mux
}

func (e *MetricsExporter) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	e.mux.ServeHTTP(w, r)
}

func (e *MetricsExporter) Start(addr string) (*http.Server, error) {
	server := &http.Server{
		Addr:    addr,
		Handler: e.mux,
	}

	go func() {
		_ = server.ListenAndServe()
	}()

	return server, nil
}
