package windowing

import (
	"time"
)

type TriggerPolicy interface {
	ShouldTrigger(window *SlidingWindow, now time.Time) bool
	Name() string
}

type EventTimeTrigger struct {
	windowSize time.Duration
}

func NewEventTimeTrigger(windowSize time.Duration) *EventTimeTrigger {
	return &EventTimeTrigger{windowSize: windowSize}
}

func (t *EventTimeTrigger) ShouldTrigger(window *SlidingWindow, now time.Time) bool {
	return window.End.Before(now)
}

func (t *EventTimeTrigger) Name() string {
	return "event_time"
}

type ProcessingTimeTrigger struct {
	threshold time.Duration
}

func NewProcessingTimeTrigger(threshold time.Duration) *ProcessingTimeTrigger {
	return &ProcessingTimeTrigger{threshold: threshold}
}

func (t *ProcessingTimeTrigger) ShouldTrigger(window *SlidingWindow, now time.Time) bool {
	return now.Sub(window.Start) >= t.threshold
}

func (t *ProcessingTimeTrigger) Name() string {
	return "processing_time"
}

type WatermarkTrigger struct {
	watermark   time.Time
	allowedLateness time.Duration
}

func NewWatermarkTrigger(allowedLateness time.Duration) *WatermarkTrigger {
	return &WatermarkTrigger{
		allowedLateness: allowedLateness,
	}
}

func (t *WatermarkTrigger) ShouldTrigger(window *SlidingWindow, now time.Time) bool {
	effectiveWatermark := now.Add(-t.allowedLateness)
	return window.End.Before(effectiveWatermark)
}

func (t *WatermarkTrigger) Name() string {
	return "watermark"
}

func (t *WatermarkTrigger) UpdateWatermark(timestamp time.Time) {
	if timestamp.After(t.watermark) {
		t.watermark = timestamp
	}
}

func (t *WatermarkTrigger) Watermark() time.Time {
	return t.watermark
}

type CompositeTrigger struct {
	policies []TriggerPolicy
}

func NewCompositeTrigger(policies ...TriggerPolicy) *CompositeTrigger {
	return &CompositeTrigger{policies: policies}
}

func (t *CompositeTrigger) ShouldTrigger(window *SlidingWindow, now time.Time) bool {
	for _, p := range t.policies {
		if p.ShouldTrigger(window, now) {
			return true
		}
	}
	return false
}

func (t *CompositeTrigger) Name() string {
	return "composite"
}

func DefaultTriggerPolicy(strategy WindowStrategy) TriggerPolicy {
	return NewEventTimeTrigger(strategy.WindowSize())
}
