package eventstore

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/chaoslab/platform/internal/abstraction"
	"github.com/chaoslab/platform/internal/common"
	"go.uber.org/zap"
)

type EventStoreService struct {
	events     map[string][]*common.DomainEvent
	snapshots  map[string]*common.Snapshot
	projections map[string]interface{}
	mu         sync.RWMutex
	eventCount int64
}

func NewEventStoreService() abstraction.EventStore {
	return &EventStoreService{
		events:      make(map[string][]*common.DomainEvent),
		snapshots:   make(map[string]*common.Snapshot),
		projections: make(map[string]interface{}),
	}
}

func (s *EventStoreService) AppendEvent(ctx context.Context, event *common.DomainEvent) error {
	if event == nil {
		return common.NewBadRequestError("event cannot be nil")
	}
	if event.EntityID == "" {
		return common.NewValidationError("entity_id is required", "entity_id")
	}
	if event.EventType == "" {
		return common.NewValidationError("event_type is required", "event_type")
	}

	event.EventID = fmt.Sprintf("evt_%d", time.Now().UnixNano())
	event.Timestamp = time.Now()

	s.mu.Lock()
	defer s.mu.Unlock()

	entityEvents := s.events[event.EntityID]
	event.Version = int64(len(entityEvents) + 1)
	entityEvents = append(entityEvents, event)
	s.events[event.EntityID] = entityEvents
	s.eventCount++

	common.Debug("event appended",
		zap.String("event_id", event.EventID),
		zap.String("entity_id", event.EntityID),
		zap.String("event_type", event.EventType),
		zap.Int64("version", event.Version),
	)

	return nil
}

func (s *EventStoreService) GetEvents(ctx context.Context, entityID string, fromVersion int64) ([]*common.DomainEvent, error) {
	if entityID == "" {
		return nil, common.NewValidationError("entity_id is required", "entity_id")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	events, exists := s.events[entityID]
	if !exists {
		return []*common.DomainEvent{}, nil
	}

	result := make([]*common.DomainEvent, 0)
	for _, e := range events {
		if e.Version >= fromVersion {
			result = append(result, e)
		}
	}

	return result, nil
}

func (s *EventStoreService) CreateSnapshot(ctx context.Context, entityID string, version int64, state interface{}) error {
	if entityID == "" {
		return common.NewValidationError("entity_id is required", "entity_id")
	}

	stateMap, ok := state.(map[string]interface{})
	if !ok {
		stateBytes, err := json.Marshal(state)
		if err != nil {
			return common.NewInternalError("failed to serialize state", err)
		}
		json.Unmarshal(stateBytes, &stateMap)
	}

	snapshot := &common.Snapshot{
		SnapshotID: fmt.Sprintf("snap_%d", time.Now().UnixNano()),
		Timestamp:  time.Now(),
		EntityID:   entityID,
		Version:    version,
		State:      stateMap,
		Metrics:    make(map[string]float64),
		Dimensions: make(map[string]string),
	}

	s.mu.Lock()
	s.snapshots[entityID] = snapshot
	s.mu.Unlock()

	common.Info("snapshot created",
		zap.String("snapshot_id", snapshot.SnapshotID),
		zap.String("entity_id", entityID),
		zap.Int64("version", version),
	)

	return nil
}

func (s *EventStoreService) GetSnapshot(ctx context.Context, entityID string) (*common.Snapshot, error) {
	if entityID == "" {
		return nil, common.NewValidationError("entity_id is required", "entity_id")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	snapshot, exists := s.snapshots[entityID]
	if !exists {
		return nil, common.NewNotFoundError(fmt.Sprintf("no snapshot for entity %s", entityID))
	}
	return snapshot, nil
}

func (s *EventStoreService) RebuildProjection(ctx context.Context, projectionType string, until time.Time) error {
	if projectionType == "" {
		return common.NewValidationError("projection_type is required", "projection_type")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	projection := make(map[string]interface{})
	eventCount := 0

	for _, events := range s.events {
		for _, event := range events {
			if !until.IsZero() && event.Timestamp.After(until) {
				continue
			}
			projection[event.EventID] = event.Payload
			eventCount++
		}
	}

	s.projections[projectionType] = projection

	common.Info("projection rebuilt",
		zap.String("projection_type", projectionType),
		zap.Int("event_count", eventCount),
		zap.Time("until", until),
	)

	return nil
}

func (s *EventStoreService) TimeTravelQuery(ctx context.Context, entityID string, targetTime time.Time) (interface{}, error) {
	if entityID == "" {
		return nil, common.NewValidationError("entity_id is required", "entity_id")
	}
	if targetTime.IsZero() {
		return nil, common.NewValidationError("target_time is required", "target_time")
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	snapshot, hasSnapshot := s.snapshots[entityID]
	var startVersion int64 = 1
	var state = make(map[string]interface{})

	if hasSnapshot && snapshot.Timestamp.Before(targetTime) {
		startVersion = snapshot.Version + 1
		state = snapshot.State
		common.Debug("using snapshot for time travel",
			zap.String("snapshot_id", snapshot.SnapshotID),
			zap.Int64("from_version", startVersion),
		)
	}

	events, exists := s.events[entityID]
	if !exists {
		return state, nil
	}

	appliedCount := 0
	for _, event := range events {
		if event.Version < startVersion {
			continue
		}
		if event.Timestamp.After(targetTime) {
			break
		}
		for k, v := range event.Payload {
			state[k] = v
		}
		appliedCount++
	}

	common.Info("time travel query completed",
		zap.String("entity_id", entityID),
		zap.Time("target_time", targetTime),
		zap.Int("events_applied", appliedCount),
	)

	return state, nil
}

func (s *EventStoreService) GetEventStats(ctx context.Context) (*common.EventStats, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	stats := &common.EventStats{
		TotalEvents:   s.eventCount,
		EventsPerType: make(map[string]int64),
		SnapshotCount: int64(len(s.snapshots)),
	}

	var lastEventTime time.Time
	for _, events := range s.events {
		for _, e := range events {
			stats.EventsPerType[e.EventType]++
			if e.Timestamp.After(lastEventTime) {
				lastEventTime = e.Timestamp
			}
		}
	}
	stats.LastEventTime = lastEventTime

	return stats, nil
}

func (s *EventStoreService) ListEntities(ctx context.Context) []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	entities := make([]string, 0, len(s.events))
	for id := range s.events {
		entities = append(entities, id)
	}
	sort.Strings(entities)
	return entities
}
