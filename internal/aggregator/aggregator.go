package aggregator

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sort"
	"sync"
	"time"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
)

type Aggregator struct {
	cfg           config.AggregationConfig
	redis         *storage.RedisClient
	input         <-chan *models.Alert
	eventChains   <-chan *models.EventChain
	incidentCh    chan *models.Incident
	incidents     map[string]*models.Incident
	incidentsMu   sync.RWMutex
	wg            sync.WaitGroup
	stopCh        chan struct{}
	dedupKeyTmpl  string
}

func NewAggregator(cfg config.AggregationConfig, redis *storage.RedisClient, input <-chan *models.Alert, eventChains <-chan *models.EventChain) (*Aggregator, error) {
	if cfg.TimeWindow == 0 {
		cfg.TimeWindow = 15 * time.Minute
	}
	if cfg.MaxIncidentSize == 0 {
		cfg.MaxIncidentSize = 100
	}
	if cfg.DedupKeyTemplate == "" {
		cfg.DedupKeyTemplate = "{{.AlertType}}:{{.ServiceName}}"
	}

	return &Aggregator{
		cfg:          cfg,
		redis:        redis,
		input:        input,
		eventChains:  eventChains,
		incidentCh:   make(chan *models.Incident, 100),
		incidents:    make(map[string]*models.Incident),
		stopCh:       make(chan struct{}),
		dedupKeyTmpl: cfg.DedupKeyTemplate,
	}, nil
}

func (a *Aggregator) Start(ctx context.Context) error {
	if !a.cfg.Enabled {
		log.Printf("Aggregator disabled")
		go a.passthrough(ctx)
		return nil
	}

	a.wg.Add(2)
	go a.processAlerts(ctx)
	go a.processEventChains(ctx)

	log.Printf("Aggregator started, time window: %s, suppress lower priority: %v", a.cfg.TimeWindow, a.cfg.SuppressLowerPriority)
	return nil
}

func (a *Aggregator) passthrough(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-a.stopCh:
			return
		case alert := <-a.input:
			if alert == nil {
				continue
			}
			incident := models.NewIncident(alert)
			select {
			case a.incidentCh <- incident:
			default:
				log.Printf("Incident channel full, dropping: %s", incident.ID)
			}
		}
	}
}

func (a *Aggregator) processAlerts(ctx context.Context) {
	defer a.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-a.stopCh:
			return
		case alert := <-a.input:
			if alert == nil {
				continue
			}
			a.processAlert(ctx, alert)
		}
	}
}

func (a *Aggregator) processAlert(ctx context.Context, alert *models.Alert) {
	dedupKey := a.generateDedupKey(alert)
	alert.DeduplicationKey = dedupKey

	isDuplicate, err := a.redis.SetDeduplication(ctx, dedupKey, alert.ID, a.cfg.TimeWindow)
	if err != nil {
		log.Printf("Failed to check deduplication: %v", err)
	}

	if !isDuplicate {
		a.incidentsMu.RLock()
		existing, ok := a.incidents[dedupKey]
		a.incidentsMu.RUnlock()

		if ok && !a.shouldCreateNewIncident(existing, alert) {
			a.addToIncident(existing, alert)
			return
		}

		if a.cfg.SuppressLowerPriority && ok {
			if a.isLowerPriority(alert.Severity, existing.Severity) {
				log.Printf("Suppressed lower priority alert: %s (existing: %s)", alert.Severity, existing.Severity)
				return
			}
		}

		incident := a.createIncident(alert, dedupKey)
		a.incidentsMu.Lock()
		a.incidents[dedupKey] = incident
		a.incidentsMu.Unlock()

		a.saveIncident(ctx, incident)

		select {
		case a.incidentCh <- incident:
		default:
			log.Printf("Incident channel full, dropping: %s", incident.ID)
		}

		return
	}

	a.incidentsMu.RLock()
	existing, ok := a.incidents[dedupKey]
	a.incidentsMu.RUnlock()

	if ok {
		a.addToIncident(existing, alert)
	}
}

func (a *Aggregator) processEventChains(ctx context.Context) {
	defer a.wg.Done()

	if a.eventChains == nil {
		return
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-a.stopCh:
			return
		case chain := <-a.eventChains:
			if chain == nil {
				continue
			}
			a.correlateWithIncidents(chain)
		}
	}
}

func (a *Aggregator) correlateWithIncidents(chain *models.EventChain) {
	if !chain.HasError {
		return
	}

	a.incidentsMu.RLock()
	defer a.incidentsMu.RUnlock()

	for _, incident := range a.incidents {
		if incident.Status != "ACTIVE" {
			continue
		}

		for _, service := range chain.Services {
			for _, incidentService := range incident.ServiceNames {
				if service == incidentService {
					a.updateIncidentWithChain(incident, chain)
					return
				}
			}
		}

		if chain.ErrorCode != "" && contains(incident.RelatedErrorCodes, chain.ErrorCode) {
			a.updateIncidentWithChain(incident, chain)
			return
		}

		for _, traceID := range incident.RelatedTraceIDs {
			if traceID == chain.TraceID {
				a.updateIncidentWithChain(incident, chain)
				return
			}
		}
	}
}

func (a *Aggregator) updateIncidentWithChain(incident *models.Incident, chain *models.EventChain) {
	if !contains(incident.RelatedTraceIDs, chain.TraceID) {
		incident.RelatedTraceIDs = append(incident.RelatedTraceIDs, chain.TraceID)
	}

	for _, service := range chain.Services {
		if !contains(incident.ServiceNames, service) {
			incident.ServiceNames = append(incident.ServiceNames, service)
		}
	}

	if chain.ErrorCode != "" && !contains(incident.RelatedErrorCodes, chain.ErrorCode) {
		incident.RelatedErrorCodes = append(incident.RelatedErrorCodes, chain.ErrorCode)
	}

	if chain.Duration > incident.EndTime.Sub(incident.StartTime).Milliseconds() {
		endTime := chain.EndTime
		incident.EndTime = &endTime
	}

	incident.Description = fmt.Sprintf("%s\n\nRelated trace %s (%d events across %d services)",
		incident.Description, chain.TraceID, len(chain.Events), len(chain.Services))
}

func (a *Aggregator) generateDedupKey(alert *models.Alert) string {
	key := fmt.Sprintf("%s:%s", alert.AlertType, alert.ServiceName)

	if alert.ErrorCode != "" {
		key += ":" + alert.ErrorCode
	}

	for _, field := range a.cfg.GroupByFields {
		switch field {
		case "error_code":
			if alert.ErrorCode != "" {
				key += ":" + alert.ErrorCode
			}
		case "alert_type":
			key += ":" + string(alert.AlertType)
		}
	}

	return key
}

func (a *Aggregator) shouldCreateNewIncident(existing *models.Incident, alert *models.Alert) bool {
	if existing.Status != "ACTIVE" {
		return true
	}

	if time.Since(existing.StartTime) > a.cfg.TimeWindow*2 {
		return true
	}

	if len(existing.Alerts) >= a.cfg.MaxIncidentSize {
		return true
	}

	if a.isHigherPriority(alert.Severity, existing.Severity) {
		return false
	}

	return false
}

func (a *Aggregator) addToIncident(incident *models.Incident, alert *models.Alert) {
	incident.Alerts = append(incident.Alerts, alert)

	if !contains(incident.ServiceNames, alert.ServiceName) {
		incident.ServiceNames = append(incident.ServiceNames, alert.ServiceName)
	}

	for _, traceID := range alert.TraceIDs {
		if !contains(incident.RelatedTraceIDs, traceID) {
			incident.RelatedTraceIDs = append(incident.RelatedTraceIDs, traceID)
		}
	}

	if alert.ErrorCode != "" && !contains(incident.RelatedErrorCodes, alert.ErrorCode) {
		incident.RelatedErrorCodes = append(incident.RelatedErrorCodes, alert.ErrorCode)
	}

	if a.isHigherPriority(alert.Severity, incident.Severity) {
		incident.Severity = alert.Severity
		incident.Title = alert.Title
		incident.Description = fmt.Sprintf("Escalated to %s: %s\n\nOriginal: %s",
			alert.Severity, alert.Description, incident.Description)
	}

	endTime := alert.Timestamp
	incident.EndTime = &endTime

	incident.Alerts = a.sortAlerts(incident.Alerts)
}

func (a *Aggregator) createIncident(alert *models.Alert, dedupKey string) *models.Incident {
	incident := models.NewIncident(alert)
	incident.DeduplicationKey = dedupKey

	if len(alert.TraceIDs) > 0 {
		incident.RelatedTraceIDs = append(incident.RelatedTraceIDs, alert.TraceIDs...)
	}

	if alert.ErrorCode != "" {
		incident.RelatedErrorCodes = append(incident.RelatedErrorCodes, alert.ErrorCode)
	}

	return incident
}

func (a *Aggregator) saveIncident(ctx context.Context, incident *models.Incident) {
	data, err := json.Marshal(incident)
	if err != nil {
		log.Printf("Failed to marshal incident: %v", err)
		return
	}

	if err := a.redis.SetIncident(ctx, incident.DeduplicationKey, string(data), 24*time.Hour); err != nil {
		log.Printf("Failed to save incident: %v", err)
	}
}

func (a *Aggregator) sortAlerts(alerts []*models.Alert) []*models.Alert {
	sort.Slice(alerts, func(i, j int) bool {
		return alerts[i].Timestamp.After(alerts[j].Timestamp)
	})
	return alerts
}

func (a *Aggregator) isHigherPriority(a1, a2 models.Severity) bool {
	priority := map[models.Severity]int{
		models.SeverityCritical: 4,
		models.SeverityHigh:     3,
		models.SeverityMedium:   2,
		models.SeverityLow:      1,
	}
	return priority[a1] > priority[a2]
}

func (a *Aggregator) isLowerPriority(a1, a2 models.Severity) bool {
	return !a.isHigherPriority(a1, a2) && a1 != a2
}

func (a *Aggregator) AcknowledgeIncident(ctx context.Context, incidentID, user string) error {
	a.incidentsMu.Lock()
	defer a.incidentsMu.Unlock()

	for _, incident := range a.incidents {
		if incident.ID == incidentID {
			incident.Acknowledged = true
			incident.AcknowledgedBy = user
			now := time.Now()
			incident.AcknowledgedAt = &now
			a.saveIncident(ctx, incident)
			return nil
		}
	}
	return fmt.Errorf("incident not found: %s", incidentID)
}

func (a *Aggregator) ResolveIncident(ctx context.Context, incidentID string) error {
	a.incidentsMu.Lock()
	defer a.incidentsMu.Unlock()

	for key, incident := range a.incidents {
		if incident.ID == incidentID {
			incident.Status = "RESOLVED"
			now := time.Now()
			incident.EndTime = &now
			a.saveIncident(ctx, incident)
			delete(a.incidents, key)
			return nil
		}
	}
	return fmt.Errorf("incident not found: %s", incidentID)
}

func (a *Aggregator) GetActiveIncidents() []*models.Incident {
	a.incidentsMu.RLock()
	defer a.incidentsMu.RUnlock()

	incidents := make([]*models.Incident, 0, len(a.incidents))
	for _, inc := range a.incidents {
		if inc.Status == "ACTIVE" {
			incidents = append(incidents, inc)
		}
	}

	sort.Slice(incidents, func(i, j int) bool {
		if incidents[i].Severity != incidents[j].Severity {
			return a.isHigherPriority(incidents[i].Severity, incidents[j].Severity)
		}
		return incidents[i].StartTime.After(incidents[j].StartTime)
	})

	return incidents
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func (a *Aggregator) Incidents() <-chan *models.Incident {
	return a.incidentCh
}

func (a *Aggregator) Stop() {
	close(a.stopCh)
	a.wg.Wait()
	close(a.incidentCh)
}
