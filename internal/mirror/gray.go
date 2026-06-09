package mirror

import (
	"fmt"
	"hash/fnv"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"

	"DF1-56/internal/models"
)

const (
	HeaderGrayVersion = "X-Gray-Version"
)

type GrayManager struct {
	mu      sync.RWMutex
	policies map[string]*models.GrayPolicy
}

func NewGrayManager() *GrayManager {
	return &GrayManager{
		policies: make(map[string]*models.GrayPolicy),
	}
}

func (gm *GrayManager) AddPolicy(policy *models.GrayPolicy) {
	if policy == nil {
		return
	}
	gm.mu.Lock()
	defer gm.mu.Unlock()
	gm.policies[policy.ID] = policy
}

func (gm *GrayManager) UpdatePolicy(policy *models.GrayPolicy) {
	if policy == nil {
		return
	}
	gm.mu.Lock()
	defer gm.mu.Unlock()
	gm.policies[policy.ID] = policy
}

func (gm *GrayManager) RemovePolicy(policyID string) {
	gm.mu.Lock()
	defer gm.mu.Unlock()
	delete(gm.policies, policyID)
}

func (gm *GrayManager) SelectCluster(ctx *models.GatewayContext, policyID string) (string, error) {
	if ctx == nil {
		return "", fmt.Errorf("gateway context is nil")
	}

	gm.mu.RLock()
	policy, exists := gm.policies[policyID]
	gm.mu.RUnlock()

	if !exists {
		return "", fmt.Errorf("gray policy %s not found", policyID)
	}

	if !policy.Enabled {
		return policy.DefaultCluster, nil
	}

	sortedRules := sortRulesByPriority(policy.Rules)

	for _, rule := range sortedRules {
		if gm.matchRule(rule, ctx) {
			ctx.Response.Header().Set(HeaderGrayVersion, rule.TargetCluster)
			ctx.Set(string(models.ContextKeyGrayVersion), rule.TargetCluster)
			return rule.TargetCluster, nil
		}
	}

	return policy.DefaultCluster, nil
}

func sortRulesByPriority(rules []models.GrayRule) []models.GrayRule {
	sorted := make([]models.GrayRule, len(rules))
	copy(sorted, rules)

	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].Priority > sorted[j].Priority
	})

	return sorted
}

func (gm *GrayManager) matchRule(rule models.GrayRule, ctx *models.GatewayContext) bool {
	if matchUserID(rule.UserIDs, ctx.UserID) {
		return true
	}

	if matchHeaders(rule.HeaderMatch, ctx.Request.Header) {
		return true
	}

	if matchCookies(rule.CookieMatch, ctx.Request.Cookies()) {
		return true
	}

	if matchQuery(rule.QueryMatch, ctx.Request.URL.Query()) {
		return true
	}

	if gm.matchPercent(&rule, ctx) {
		return true
	}

	return false
}

func matchUserID(userIDs []string, ctxUserID string) bool {
	if len(userIDs) == 0 || ctxUserID == "" {
		return false
	}
	for _, id := range userIDs {
		if id == ctxUserID {
			return true
		}
	}
	return false
}

func matchHeaders(headerMatch map[string]string, headers http.Header) bool {
	if len(headerMatch) == 0 {
		return false
	}
	for key, expectedValue := range headerMatch {
		actualValue := headers.Get(key)
		if actualValue == "" || !strings.EqualFold(actualValue, expectedValue) {
			return false
		}
	}
	return true
}

func matchCookies(cookieMatch map[string]string, cookies []*http.Cookie) bool {
	if len(cookieMatch) == 0 {
		return false
	}

	cookieMap := make(map[string]string)
	for _, cookie := range cookies {
		cookieMap[cookie.Name] = cookie.Value
	}

	for key, expectedValue := range cookieMatch {
		actualValue, exists := cookieMap[key]
		if !exists || !strings.EqualFold(actualValue, expectedValue) {
			return false
		}
	}
	return true
}

func matchQuery(queryMatch map[string]string, queryParams map[string][]string) bool {
	if len(queryMatch) == 0 {
		return false
	}
	for key, expectedValue := range queryMatch {
		values, exists := queryParams[key]
		if !exists || len(values) == 0 {
			return false
		}
		matched := false
		for _, v := range values {
			if strings.EqualFold(v, expectedValue) {
				matched = true
				break
			}
		}
		if !matched {
			return false
		}
	}
	return true
}

func (gm *GrayManager) matchPercent(rule *models.GrayRule, ctx *models.GatewayContext) bool {
	if rule.Percent <= 0 {
		return false
	}
	if rule.Percent >= 100 {
		return true
	}

	hashKey := ctx.RequestID
	if hashKey == "" {
		hashKey = ctx.TraceID
	}
	if hashKey == "" {
		hashKey = ctx.ClientIP + ctx.Request.URL.Path
	}

	hashValue := fnvHash32(hashKey)
	percent := hashValue % 100

	return percent < uint32(rule.Percent)
}

func fnvHash32(key string) uint32 {
	h := fnv.New32a()
	h.Write([]byte(key))
	return h.Sum32()
}

func (gm *GrayManager) GetPolicy(policyID string) (*models.GrayPolicy, bool) {
	gm.mu.RLock()
	defer gm.mu.RUnlock()
	policy, exists := gm.policies[policyID]
	return policy, exists
}

func (gm *GrayManager) ListPolicies() []*models.GrayPolicy {
	gm.mu.RLock()
	defer gm.mu.RUnlock()
	policies := make([]*models.GrayPolicy, 0, len(gm.policies))
	for _, policy := range gm.policies {
		policies = append(policies, policy)
	}
	return policies
}

func ParsePercentHeader(headers http.Header) (int, bool) {
	percentStr := headers.Get("X-Gray-Percent")
	if percentStr == "" {
		return 0, false
	}
	percent, err := strconv.Atoi(percentStr)
	if err != nil || percent < 0 || percent > 100 {
		return 0, false
	}
	return percent, true
}
