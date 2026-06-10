package game

import (
	"fmt"
	"sync"

	"github.com/studio/gameroom/pkg/common"
)

var (
	ruleRegistryMu sync.RWMutex
	ruleRegistry   = make(map[common.GameType]GameRule)
)

func RegisterRule(rule GameRule) {
	ruleRegistryMu.Lock()
	defer ruleRegistryMu.Unlock()
	ruleRegistry[rule.GameType()] = rule
	common.LogInfo("game rule registered: %s (%s)", rule.GameType(), rule.Name())
}

func GetRule(gameType common.GameType) (GameRule, error) {
	ruleRegistryMu.RLock()
	defer ruleRegistryMu.RUnlock()
	rule, ok := ruleRegistry[gameType]
	if !ok {
		return nil, fmt.Errorf("game rule not found for type: %s", gameType)
	}
	return rule, nil
}

func ListRules() []common.GameType {
	ruleRegistryMu.RLock()
	defer ruleRegistryMu.RUnlock()
	types := make([]common.GameType, 0, len(ruleRegistry))
	for t := range ruleRegistry {
		types = append(types, t)
	}
	return types
}
