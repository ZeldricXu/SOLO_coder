package storage

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"github.com/segmentio/kafka-go"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
)

type KafkaBuffer struct {
	writer *kafka.Writer
	reader *kafka.Reader
	cfg    config.KafkaConfig
	input  chan *models.LogEvent
	output chan *models.LogEvent
	wg     sync.WaitGroup
	stopCh chan struct{}
}

func NewKafkaBuffer(cfg config.KafkaConfig) (*KafkaBuffer, error) {
	if cfg.Topic == "" {
		cfg.Topic = "log-analyzer-buffer"
	}

	writer := &kafka.Writer{
		Addr:         kafka.TCP(cfg.Brokers...),
		Topic:        cfg.Topic,
		Balancer:     &kafka.LeastBytes{},
		BatchSize:    100,
		BatchTimeout: 10 * time.Millisecond,
		Async:        false,
	}

	reader := kafka.NewReader(kafka.ReaderConfig{
		Brokers:   cfg.Brokers,
		Topic:     cfg.Topic,
		GroupID:   "log-analyzer-buffer-consumer",
		Partition: cfg.Partition,
		MinBytes:  10e3,
		MaxBytes:  10e6,
		MaxWait:   1 * time.Second,
	})

	return &KafkaBuffer{
		writer: writer,
		reader: reader,
		cfg:    cfg,
		input:  make(chan *models.LogEvent, 10000),
		output: make(chan *models.LogEvent, 10000),
		stopCh: make(chan struct{}),
	}, nil
}

func (k *KafkaBuffer) Start(ctx context.Context) error {
	k.wg.Add(2)
	go k.writeLoop(ctx)
	go k.readLoop(ctx)
	log.Printf("Kafka buffer started, topic: %s", k.cfg.Topic)
	return nil
}

func (k *KafkaBuffer) writeLoop(ctx context.Context) {
	defer k.wg.Done()

	batch := make([]*models.LogEvent, 0, 100)
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-k.stopCh:
			return
		case event := <-k.input:
			batch = append(batch, event)
			if len(batch) >= 100 {
				k.flushBatch(ctx, batch)
				batch = batch[:0]
			}
		case <-ticker.C:
			if len(batch) > 0 {
				k.flushBatch(ctx, batch)
				batch = batch[:0]
			}
		}
	}
}

func (k *KafkaBuffer) flushBatch(ctx context.Context, batch []*models.LogEvent) {
	msgs := make([]kafka.Message, 0, len(batch))
	for _, event := range batch {
		data, err := json.Marshal(event)
		if err != nil {
			log.Printf("Failed to marshal log event: %v", err)
			continue
		}

		key := event.ServiceName
		if event.TraceID != "" {
			key = event.TraceID
		}

		msgs = append(msgs, kafka.Message{
			Key:   []byte(key),
			Value: data,
			Time:  event.Timestamp,
			Headers: []kafka.Header{
				{Key: "service", Value: []byte(event.ServiceName)},
				{Key: "level", Value: []byte(event.Level)},
				{Key: "source", Value: []byte(event.Source)},
			},
		})
	}

	if len(msgs) > 0 {
		if err := k.writer.WriteMessages(ctx, msgs...); err != nil {
			log.Printf("Failed to write to kafka buffer: %v", err)
		}
	}
}

func (k *KafkaBuffer) readLoop(ctx context.Context) {
	defer k.wg.Done()
	defer k.reader.Close()

	for {
		select {
		case <-ctx.Done():
			return
		case <-k.stopCh:
			return
		default:
		}

		msg, err := k.reader.ReadMessage(ctx)
		if err != nil {
			if err == context.Canceled {
				return
			}
			log.Printf("Kafka buffer read error: %v", err)
			time.Sleep(1 * time.Second)
			continue
		}

		event := &models.LogEvent{}
		if err := json.Unmarshal(msg.Value, event); err != nil {
			log.Printf("Failed to unmarshal kafka message: %v", err)
			continue
		}

		select {
		case k.output <- event:
		case <-ctx.Done():
			return
		case <-k.stopCh:
			return
		}
	}
}

func (k *KafkaBuffer) Write(ctx context.Context, event *models.LogEvent) error {
	select {
	case k.input <- event:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (k *KafkaBuffer) Output() <-chan *models.LogEvent {
	return k.output
}

func (k *KafkaBuffer) Stop() error {
	close(k.stopCh)
	k.wg.Wait()

	if err := k.writer.Close(); err != nil {
		return fmt.Errorf("failed to close kafka writer: %w", err)
	}

	close(k.input)
	close(k.output)
	return nil
}
