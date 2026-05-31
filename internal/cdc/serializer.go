package cdc

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
)

type EventSerializer interface {
	Serialize(event *ChangeEvent) ([]byte, error)
	Deserialize(data []byte) (*ChangeEvent, error)
	Format() string
}

type JSONSerializer struct{}

func NewJSONSerializer() *JSONSerializer {
	return &JSONSerializer{}
}

func (s *JSONSerializer) Format() string {
	return "json"
}

func (s *JSONSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(event)
}

func (s *JSONSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var event ChangeEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

type AvroSerializer struct {
	Schema string
}

func NewAvroSerializer(schema string) *AvroSerializer {
	return &AvroSerializer{Schema: schema}
}

func (s *AvroSerializer) Format() string {
	return "avro"
}

func (s *AvroSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(map[string]interface{}{
		"schema": s.Schema,
		"payload": event,
	})
}

func (s *AvroSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var wrapper struct {
		Payload ChangeEvent `json:"payload"`
	}
	if err := json.Unmarshal(data, &wrapper); err != nil {
		return nil, err
	}
	return &wrapper.Payload, nil
}

type XMLSerializer struct{}

func NewXMLSerializer() *XMLSerializer {
	return &XMLSerializer{}
}

func (s *XMLSerializer) Format() string {
	return "xml"
}

func (s *XMLSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return xml.Marshal(event)
}

func (s *XMLSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var event ChangeEvent
	if err := xml.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

type CSVSerializer struct{}

func NewCSVSerializer() *CSVSerializer {
	return &CSVSerializer{}
}

func (s *CSVSerializer) Format() string {
	return "csv"
}

func (s *CSVSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	beforeJSON, _ := json.Marshal(event.Before)
	afterJSON, _ := json.Marshal(event.After)
	pkJSON, _ := json.Marshal(event.PrimaryKey)

	line := fmt.Sprintf("%s,%s,%s,%s,%s,%s,%s,%d\n",
		event.ID,
		event.EventType,
		event.Database,
		event.TableName,
		string(pkJSON),
		string(beforeJSON),
		string(afterJSON),
		event.Timestamp.Unix(),
	)
	return []byte(line), nil
}

func (s *CSVSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	return nil, fmt.Errorf("csv deserialization not implemented")
}

type ProtobufSerializer struct{}

func NewProtobufSerializer() *ProtobufSerializer {
	return &ProtobufSerializer{}
}

func (s *ProtobufSerializer) Format() string {
	return "protobuf"
}

func (s *ProtobufSerializer) Serialize(event *ChangeEvent) ([]byte, error) {
	return json.Marshal(event)
}

func (s *ProtobufSerializer) Deserialize(data []byte) (*ChangeEvent, error) {
	var event ChangeEvent
	if err := json.Unmarshal(data, &event); err != nil {
		return nil, err
	}
	return &event, nil
}

type OutputAdapter interface {
	Write(event *ChangeEvent) error
	WriteBatch(events []ChangeEvent) error
	Close() error
	Name() string
}

type ConsoleOutput struct{}

func NewConsoleOutput() *ConsoleOutput {
	return &ConsoleOutput{}
}

func (o *ConsoleOutput) Name() string {
	return "console"
}

func (o *ConsoleOutput) Write(event *ChangeEvent) error {
	data, _ := json.MarshalIndent(event, "", "  ")
	fmt.Println(string(data))
	return nil
}

func (o *ConsoleOutput) WriteBatch(events []ChangeEvent) error {
	for _, e := range events {
		_ = o.Write(&e)
	}
	return nil
}

func (o *ConsoleOutput) Close() error {
	return nil
}

type MemoryOutput struct {
	events []ChangeEvent
}

func NewMemoryOutput() *MemoryOutput {
	return &MemoryOutput{
		events: make([]ChangeEvent, 0),
	}
}

func (o *MemoryOutput) Name() string {
	return "memory"
}

func (o *MemoryOutput) Write(event *ChangeEvent) error {
	o.events = append(o.events, *event)
	return nil
}

func (o *MemoryOutput) WriteBatch(events []ChangeEvent) error {
	o.events = append(o.events, events...)
	return nil
}

func (o *MemoryOutput) Close() error {
	return nil
}

func (o *MemoryOutput) GetEvents() []ChangeEvent {
	return o.events
}

func (o *MemoryOutput) Clear() {
	o.events = make([]ChangeEvent, 0)
}

type KafkaOutput struct {
	Broker string
	Topic  string
}

func NewKafkaOutput(broker, topic string) *KafkaOutput {
	return &KafkaOutput{
		Broker: broker,
		Topic:  topic,
	}
}

func (o *KafkaOutput) Name() string {
	return "kafka"
}

func (o *KafkaOutput) Write(event *ChangeEvent) error {
	return nil
}

func (o *KafkaOutput) WriteBatch(events []ChangeEvent) error {
	return nil
}

func (o *KafkaOutput) Close() error {
	return nil
}

type WebhookOutput struct {
	URL     string
	Headers map[string]string
}

func NewWebhookOutput(url string) *WebhookOutput {
	return &WebhookOutput{
		URL:     url,
		Headers: make(map[string]string),
	}
}

func (o *WebhookOutput) Name() string {
	return "webhook"
}

func (o *WebhookOutput) Write(event *ChangeEvent) error {
	return nil
}

func (o *WebhookOutput) WriteBatch(events []ChangeEvent) error {
	return nil
}

func (o *WebhookOutput) Close() error {
	return nil
}
