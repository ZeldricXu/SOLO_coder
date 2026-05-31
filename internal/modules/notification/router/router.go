package router

import (
	"context"
	"fmt"
	"math/rand"
	"reflect"
	"regexp"
	"sort"
	"sync"
	"time"

	"notificationplatform/internal/common/database"
	"notificationplatform/internal/common/errors"
	"notificationplatform/internal/common/logger"
	"notificationplatform/internal/common/models"
	"notificationplatform/pkg/utils"

	"go.uber.org/zap"
	"gorm.io/gorm"
)

type Manager struct {
	db           *gorm.DB
	routes       map[string]*models.NotificationRoute
	routesMu     sync.RWMutex
	loadBalancer map[string]int
	lbMu         sync.Mutex
}

var (
	instance *Manager
	once     sync.Once
)

func NewManager() *Manager {
	once.Do(func() {
		instance = &Manager{
			db:           database.GetDB(),
			routes:       make(map[string]*models.NotificationRoute),
			loadBalancer: make(map[string]int),
		}
		instance.loadRoutes()
		instance.initDefaultRoutes()
	})
	return instance
}

func (m *Manager) loadRoutes() {
	if m.db == nil {
		return
	}

	var routes []models.NotificationRoute
	if err := m.db.Where("enabled = ?", true).Find(&routes).Error; err != nil {
		logger.Get().Warn("failed to load routes from DB", zap.Error(err))
		return
	}

	m.routesMu.Lock()
	defer m.routesMu.Unlock()

	m.routes = make(map[string]*models.NotificationRoute)
	for i := range routes {
		m.routes[routes[i].ID] = &routes[i]
	}
}

func (m *Manager) initDefaultRoutes() {
	m.routesMu.Lock()
	defer m.routesMu.Unlock()

	if len(m.routes) > 0 {
		return
	}

	defaultRoutes := []*models.NotificationRoute{
		{
			ID:               "route_alert_critical",
			Name:             "Critical Alert Routing",
			Description:      "Route critical alerts to DingTalk and SMS",
			NotificationType: "alert",
			Conditions: []models.RoutingCondition{
				{Field: "Priority", Operator: "gte", Value: 4},
			},
			ConditionLogic: "AND",
			Strategy:       string(models.StrategyMultiAll),
			Targets: []models.RouteTarget{
				{Channel: string(models.ChannelDingtalk), Priority: 1, Weight: 50, Enabled: true},
				{Channel: string(models.ChannelSMS), Priority: 2, Weight: 50, Enabled: true},
			},
			DefaultChannel: string(models.ChannelDingtalk),
			Enabled:        true,
			Priority:       100,
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
		},
		{
			ID:               "route_alert_high",
			Name:             "High Alert Routing",
			Description:      "Route high priority alerts to DingTalk",
			NotificationType: "alert",
			Conditions: []models.RoutingCondition{
				{Field: "Priority", Operator: "eq", Value: 3},
			},
			ConditionLogic: "AND",
			Strategy:       string(models.StrategySingle),
			Targets: []models.RouteTarget{
				{Channel: string(models.ChannelDingtalk), Priority: 1, Weight: 100, Enabled: true},
			},
			DefaultChannel: string(models.ChannelDingtalk),
			Enabled:        true,
			Priority:       90,
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
		},
		{
			ID:               "route_info_email",
			Name:             "Info Email Routing",
			Description:      "Route info notifications to email",
			NotificationType: "info",
			Conditions:       []models.RoutingCondition{},
			ConditionLogic:   "AND",
			Strategy:         string(models.StrategySingle),
			Targets: []models.RouteTarget{
				{Channel: string(models.ChannelEmail), Priority: 1, Weight: 100, Enabled: true},
			},
			DefaultChannel: string(models.ChannelEmail),
			Enabled:        true,
			Priority:       50,
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
		},
		{
			ID:               "route_system_webhook",
			Name:             "System Webhook Routing",
			Description:      "Route system notifications to webhook with failover",
			NotificationType: "system",
			Conditions: []models.RoutingCondition{
				{Field: "Type", Operator: "eq", Value: "system"},
			},
			ConditionLogic: "AND",
			Strategy:       string(models.StrategyFailover),
			Targets: []models.RouteTarget{
				{Channel: string(models.ChannelWebhook), Priority: 1, Weight: 70, Enabled: true},
				{Channel: string(models.ChannelEmail), Priority: 2, Weight: 30, Enabled: true},
			},
			DefaultChannel: string(models.ChannelWebhook),
			Enabled:        true,
			Priority:       80,
			CreatedAt:      time.Now(),
			UpdatedAt:      time.Now(),
		},
	}

	for _, route := range defaultRoutes {
		m.routes[route.ID] = route
	}
}

func (m *Manager) Evaluate(ctx context.Context, notification *models.NotificationRecord) *models.RoutingResult {
	log := logger.FromContext(ctx)

	m.routesMu.RLock()
	routes := make([]*models.NotificationRoute, 0, len(m.routes))
	for _, route := range m.routes {
		if route.Enabled {
			routes = append(routes, route)
		}
	}
	m.routesMu.RUnlock()

	sort.Slice(routes, func(i, j int) bool {
		return routes[i].Priority > routes[j].Priority
	})

	for _, route := range routes {
		if route.NotificationType != notification.Type && route.NotificationType != ".*" {
			typeMatch, _ := regexp.MatchString(route.NotificationType, notification.Type)
			if !typeMatch {
				continue
			}
		}

		matched := m.evaluateConditions(ctx, route, notification)
		if matched {
			log.Debug("route matched",
				zap.String("route_id", route.ID),
				zap.String("route_name", route.Name),
				zap.String("strategy", route.Strategy),
			)

			targets := m.applyDistributionStrategy(ctx, route, notification)

			return &models.RoutingResult{
				Matched:        true,
				RouteID:        route.ID,
				RouteName:      route.Name,
				Strategy:       route.Strategy,
				Targets:        targets,
				DefaultChannel: route.DefaultChannel,
			}
		}
	}

	log.Debug("no route matched, using default channel")
	return &models.RoutingResult{
		Matched:        false,
		DefaultChannel: notification.Channel,
	}
}

func (m *Manager) evaluateConditions(ctx context.Context, route *models.NotificationRoute, notification *models.NotificationRecord) bool {
	if len(route.Conditions) == 0 {
		return true
	}

	log := logger.FromContext(ctx)
	results := make([]bool, 0, len(route.Conditions))

	for _, condition := range route.Conditions {
		result := m.evaluateSingleCondition(ctx, condition, notification)
		results = append(results, result)

		log.Debug("condition evaluated",
			zap.String("field", condition.Field),
			zap.String("operator", condition.Operator),
			zap.Any("value", condition.Value),
			zap.Bool("result", result),
		)
	}

	if route.ConditionLogic == "OR" {
		for _, r := range results {
			if r {
				return true
			}
		}
		return false
	}

	for _, r := range results {
		if !r {
			return false
		}
	}
	return true
}

func (m *Manager) evaluateSingleCondition(ctx context.Context, condition models.RoutingCondition, notification *models.NotificationRecord) bool {
	fieldValue := m.getFieldValue(notification, condition.Field)
	if fieldValue == nil {
		return false
	}

	return m.compareValues(fieldValue, condition.Operator, condition.Value)
}

func (m *Manager) getFieldValue(notification *models.NotificationRecord, field string) interface{} {
	v := reflect.ValueOf(notification).Elem()
	for i := 0; i < v.NumField(); i++ {
		fieldName := v.Type().Field(i).Name
		if fieldName == field {
			return v.Field(i).Interface()
		}
	}

	if notification.Metadata != nil {
		if val, ok := notification.Metadata[field]; ok {
			return val
		}
	}

	return nil
}

func (m *Manager) compareValues(a interface{}, operator string, b interface{}) bool {
	switch operator {
	case "eq", "==":
		return reflect.DeepEqual(a, b)
	case "neq", "!=":
		return !reflect.DeepEqual(a, b)
	case "gt", ">":
		return m.compareNumeric(a, b) > 0
	case "gte", ">=":
		return m.compareNumeric(a, b) >= 0
	case "lt", "<":
		return m.compareNumeric(a, b) < 0
	case "lte", "<=":
		return m.compareNumeric(a, b) <= 0
	case "contains":
		return m.contains(a, b)
	case "not_contains":
		return !m.contains(a, b)
	case "regex":
		return m.regexMatch(a, b)
	case "in":
		return m.inArray(a, b)
	case "not_in":
		return !m.inArray(a, b)
	case "startswith":
		return m.startsWith(a, b)
	case "endswith":
		return m.endsWith(a, b)
	default:
		return false
	}
}

func (m *Manager) compareNumeric(a, b interface{}) int {
	aFloat, aOk := m.toFloat64(a)
	bFloat, bOk := m.toFloat64(b)
	if !aOk || !bOk {
		return 0
	}
	if aFloat > bFloat {
		return 1
	} else if aFloat < bFloat {
		return -1
	}
	return 0
}

func (m *Manager) toFloat64(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case int:
		return float64(val), true
	case int32:
		return float64(val), true
	case int64:
		return float64(val), true
	case float32:
		return float64(val), true
	case float64:
		return val, true
	case string:
		var f float64
		if _, err := fmt.Sscanf(val, "%f", &f); err == nil {
			return f, true
		}
	}
	return 0, false
}

func (m *Manager) contains(a, b interface{}) bool {
	aStr, aOk := a.(string)
	bStr, bOk := b.(string)
	if !aOk || !bOk {
		return false
	}
	return regexp.MustCompile(regexp.QuoteMeta(bStr)).MatchString(aStr)
}

func (m *Manager) regexMatch(a, b interface{}) bool {
	aStr, aOk := a.(string)
	bStr, bOk := b.(string)
	if !aOk || !bOk {
		return false
	}
	matched, _ := regexp.MatchString(bStr, aStr)
	return matched
}

func (m *Manager) inArray(a, b interface{}) bool {
	bSlice, ok := b.([]interface{})
	if !ok {
		return false
	}
	for _, item := range bSlice {
		if reflect.DeepEqual(a, item) {
			return true
		}
	}
	return false
}

func (m *Manager) startsWith(a, b interface{}) bool {
	aStr, aOk := a.(string)
	bStr, bOk := b.(string)
	if !aOk || !bOk {
		return false
	}
	return len(aStr) >= len(bStr) && aStr[:len(bStr)] == bStr
}

func (m *Manager) endsWith(a, b interface{}) bool {
	aStr, aOk := a.(string)
	bStr, bOk := b.(string)
	if !aOk || !bOk {
		return false
	}
	return len(aStr) >= len(bStr) && aStr[len(aStr)-len(bStr):] == bStr
}

func (m *Manager) applyDistributionStrategy(ctx context.Context, route *models.NotificationRoute, notification *models.NotificationRecord) []models.RouteTarget {
	log := logger.FromContext(ctx)
	enabledTargets := make([]models.RouteTarget, 0)
	for _, t := range route.Targets {
		if t.Enabled {
			enabledTargets = append(enabledTargets, t)
		}
	}

	if len(enabledTargets) == 0 {
		log.Warn("no enabled targets for route", zap.String("route_id", route.ID))
		return []models.RouteTarget{{Channel: route.DefaultChannel, Priority: 1, Weight: 100, Enabled: true}}
	}

	switch models.DistributionStrategy(route.Strategy) {
	case models.StrategySingle:
		sort.Slice(enabledTargets, func(i, j int) bool {
			return enabledTargets[i].Priority < enabledTargets[j].Priority
		})
		return []models.RouteTarget{enabledTargets[0]}

	case models.StrategyMultiAll:
		return enabledTargets

	case models.StrategyMultiAny:
		rand.Seed(time.Now().UnixNano())
		idx := rand.Intn(len(enabledTargets))
		return []models.RouteTarget{enabledTargets[idx]}

	case models.StrategyFailover:
		sort.Slice(enabledTargets, func(i, j int) bool {
			return enabledTargets[i].Priority < enabledTargets[j].Priority
		})
		return enabledTargets

	case models.StrategyLoadBalance:
		m.lbMu.Lock()
		defer m.lbMu.Unlock()
		counter := m.loadBalancer[route.ID]
		idx := counter % len(enabledTargets)
		m.loadBalancer[route.ID] = counter + 1
		return []models.RouteTarget{enabledTargets[idx]}

	case models.StrategyWeighted:
		return m.selectWeightedTarget(enabledTargets)

	default:
		sort.Slice(enabledTargets, func(i, j int) bool {
			return enabledTargets[i].Priority < enabledTargets[j].Priority
		})
		return []models.RouteTarget{enabledTargets[0]}
	}
}

func (m *Manager) selectWeightedTarget(targets []models.RouteTarget) []models.RouteTarget {
	totalWeight := 0
	for _, t := range targets {
		if t.Weight <= 0 {
			t.Weight = 1
		}
		totalWeight += t.Weight
	}

	if totalWeight <= 0 {
		return []models.RouteTarget{targets[0]}
	}

	rand.Seed(time.Now().UnixNano())
	random := rand.Intn(totalWeight)

	currentWeight := 0
	for _, t := range targets {
		currentWeight += t.Weight
		if random < currentWeight {
			return []models.RouteTarget{t}
		}
	}

	return []models.RouteTarget{targets[0]}
}

func (m *Manager) AddRoute(ctx context.Context, route *models.NotificationRoute) error {
	m.routesMu.Lock()
	defer m.routesMu.Unlock()

	route.ID = utils.NewID("route")
	route.CreatedAt = time.Now()
	route.UpdatedAt = time.Now()

	if m.db != nil {
		if err := m.db.Create(route).Error; err != nil {
			return errors.NewInternal("failed to create route", err.Error())
		}
	}

	m.routes[route.ID] = route
	logger.FromContext(ctx).Info("route added",
		zap.String("route_id", route.ID),
		zap.String("route_name", route.Name),
	)
	return nil
}

func (m *Manager) GetRoute(ctx context.Context, id string) (*models.NotificationRoute, error) {
	m.routesMu.RLock()
	defer m.routesMu.RUnlock()

	route, exists := m.routes[id]
	if !exists {
		return nil, errors.NewNotFound("route not found", id)
	}
	return route, nil
}

func (m *Manager) GetRoutes(ctx context.Context) []*models.NotificationRoute {
	m.routesMu.RLock()
	defer m.routesMu.RUnlock()

	routes := make([]*models.NotificationRoute, 0, len(m.routes))
	for _, route := range m.routes {
		routes = append(routes, route)
	}

	sort.Slice(routes, func(i, j int) bool {
		return routes[i].Priority > routes[j].Priority
	})
	return routes
}

func (m *Manager) UpdateRoute(ctx context.Context, id string, route *models.NotificationRoute) error {
	m.routesMu.Lock()
	defer m.routesMu.Unlock()

	existing, exists := m.routes[id]
	if !exists {
		return errors.NewNotFound("route not found", id)
	}

	route.ID = id
	route.CreatedAt = existing.CreatedAt
	route.UpdatedAt = time.Now()

	if m.db != nil {
		if err := m.db.Save(route).Error; err != nil {
			return errors.NewInternal("failed to update route", err.Error())
		}
	}

	m.routes[id] = route
	logger.FromContext(ctx).Info("route updated", zap.String("route_id", id))
	return nil
}

func (m *Manager) DeleteRoute(ctx context.Context, id string) error {
	m.routesMu.Lock()
	defer m.routesMu.Unlock()

	if _, exists := m.routes[id]; !exists {
		return errors.NewNotFound("route not found", id)
	}

	if m.db != nil {
		if err := m.db.Delete(&models.NotificationRoute{}, "id = ?", id).Error; err != nil {
			return errors.NewInternal("failed to delete route", err.Error())
		}
	}

	delete(m.routes, id)
	logger.FromContext(ctx).Info("route deleted", zap.String("route_id", id))
	return nil
}

func (m *Manager) TestRoute(ctx context.Context, id string, notification *models.NotificationRecord) (*models.RoutingResult, error) {
	m.routesMu.RLock()
	route, exists := m.routes[id]
	m.routesMu.RUnlock()

	if !exists {
		return nil, errors.NewNotFound("route not found", id)
	}

	matched := m.evaluateConditions(ctx, route, notification)
	targets := m.applyDistributionStrategy(ctx, route, notification)

	return &models.RoutingResult{
		Matched:        matched,
		RouteID:        route.ID,
		RouteName:      route.Name,
		Strategy:       route.Strategy,
		Targets:        targets,
		DefaultChannel: route.DefaultChannel,
	}, nil
}

func (m *Manager) ReloadRoutes() {
	m.loadRoutes()
	logger.Get().Info("routes reloaded")
}
