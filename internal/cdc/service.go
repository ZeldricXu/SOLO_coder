package cdc

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/config"
	"streamsql/internal/common/logger"
)

type RecoveryState struct {
	SourceType    string    `json:"source_type"`
	LastLogName   string    `json:"last_log_name"`
	LastPosition  int64     `json:"last_position"`
	LastProcessed time.Time `json:"last_processed"`
	Checkpoint    time.Time `json:"checkpoint"`
}

type CDCService struct {
	parsers     map[string]BinlogParser
	serializers  map[string]EventSerializer
	outputs      []OutputAdapter
	buffer       chan ChangeEvent
	recoveryState map[string]*RecoveryState
	config       config.CDCConfig
	running      bool
	mu           sync.RWMutex
	wg           sync.WaitGroup
	ctx          context.Context
	cancel       context.CancelFunc
}

func NewCDCService(cfg config.CDCConfig) *CDCService {
	ctx, cancel := context.WithCancel(context.Background())

	svc := &CDCService{
		parsers:      make(map[string]BinlogParser),
		serializers:   make(map[string]EventSerializer),
		outputs:       make([]OutputAdapter, 0),
		buffer:        make(chan ChangeEvent, cfg.BufferSize),
		recoveryState: make(map[string]*RecoveryState),
		config:        cfg,
		ctx:           ctx,
		cancel:        cancel,
	}

	svc.parsers["mysql"] = NewMySQLBinlogParser()
	svc.parsers["postgres"] = NewPostgreSQLWALParser()
	svc.parsers["mongodb"] = NewMongoDBOplogParser()

	svc.serializers["json"] = NewJSONSerializer()
	svc.serializers["avro"] = NewAvroSerializer("")
	svc.serializers["xml"] = NewXMLSerializer()
	svc.serializers["csv"] = NewCSVSerializer()
	svc.serializers["protobuf"] = NewProtobufSerializer()

	memoryOutput := NewMemoryOutput()
	consoleOutput := NewConsoleOutput()
	svc.outputs = append(svc.outputs, memoryOutput, consoleOutput)

	logger.Sugar().Info("CDC service initialized")
	return svc
}

func (s *CDCService) Start() {
	s.mu.Lock()
	if s.running {
		s.mu.Unlock()
		return
	}
	s.running = true
	s.mu.Unlock()

	s.wg.Add(1)
	go s.processEvents()

	logger.Sugar().Info("CDC service started")
}

func (s *CDCService) Stop() {
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.running = false
	s.mu.Unlock()

	s.cancel()
	close(s.buffer)
	s.wg.Wait()

	for _, output := range s.outputs {
		_ = output.Close()
	}

	logger.Sugar().Info("CDC service stopped")
}

func (s *CDCService) processEvents() {
	defer s.wg.Done()

	for event := range s.buffer {
		select {
		case <-s.ctx.Done():
			return
		default:
		}

		event.ID = uuid.New().String()

		for _, output := range s.outputs {
			if err := output.Write(&event); err != nil {
				logger.Sugar().Errorf("Failed to write event to output %s: %v", output.Name(), err)
			}
		}

		if s.config.RecoveryEnable {
			s.updateRecoveryState(&event)
		}
	}
}

func (s *CDCService) ParseEvent(sourceType string, rawData []byte) ([]ChangeEvent, error) {
	s.mu.RLock()
	parser, ok := s.parsers[sourceType]
	s.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("no parser found for source type: %s", sourceType)
	}

	events, err := parser.Parse(rawData)
	if err != nil {
		return nil, err
	}

	return events, nil
}

func (s *CDCService) ProcessEvent(sourceType string, rawData []byte) error {
	events, err := s.ParseEvent(sourceType, rawData)
	if err != nil {
		return err
	}

	for _, event := range events {
		select {
		case s.buffer <- event:
		default:
			logger.Sugar().Warn("CDC buffer is full, dropping event")
		}
	}

	return nil
}

func (s *CDCService) SerializeEvent(format string, event *ChangeEvent) ([]byte, error) {
	s.mu.RLock()
	serializer, ok := s.serializers[format]
	s.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("no serializer found for format: %s", format)
	}

	return serializer.Serialize(event)
}

func (s *CDCService) AddOutput(output OutputAdapter) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.outputs = append(s.outputs, output)
}

func (s *CDCService) AddParser(sourceType string, parser BinlogParser) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.parsers[sourceType] = parser
}

func (s *CDCService) AddSerializer(format string, serializer EventSerializer) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.serializers[format] = serializer
}

func (s *CDCService) updateRecoveryState(event *ChangeEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()

	state, ok := s.recoveryState[event.Database]
	if !ok {
		state = &RecoveryState{
			SourceType: event.Database,
		}
		s.recoveryState[event.Database] = state
	}

	state.LastLogName = event.SourceLogName
	state.LastPosition = event.LogPosition
	state.LastProcessed = time.Now().UTC()
	state.Checkpoint = time.Now().UTC()
}

func (s *CDCService) GetRecoveryState(database string) (*RecoveryState, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	state, ok := s.recoveryState[database]
	if !ok {
		return nil, fmt.Errorf("no recovery state found for database: %s", database)
	}
	return state, nil
}

func (s *CDCService) GetAllRecoveryStates() map[string]*RecoveryState {
	s.mu.RLock()
	defer s.mu.RUnlock()

	states := make(map[string]*RecoveryState)
	for k, v := range s.recoveryState {
		states[k] = v
	}
	return states
}

func (s *CDCService) GetStats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return map[string]interface{}{
		"buffer_size":   len(s.buffer),
		"buffer_cap":    cap(s.buffer),
		"running":       s.running,
		"outputs_count": len(s.outputs),
		"parsers_count": len(s.parsers),
		"recovery_states": len(s.recoveryState),
	}
}

func (s *CDCService) GetMemoryOutput() *MemoryOutput {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, output := range s.outputs {
		if mo, ok := output.(*MemoryOutput); ok {
			return mo
		}
	}
	return nil
}

func (s *CDCService) ListParsers() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	parsers := make([]string, 0, len(s.parsers))
	for name := range s.parsers {
		parsers = append(parsers, name)
	}
	return parsers
}

func (s *CDCService) ListSerializers() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()

	serializers := make([]string, 0, len(s.serializers))
	for name := range s.serializers {
		serializers = append(serializers, name)
	}
	return serializers
}
