package server

import (
	"fmt"
	"sync/atomic"
	"time"
)

var (
	taskIDCounter     uint64
	streamIDCounter   uint64
	workerIDCounter   uint64
)

func generateTaskID() string {
	id := atomic.AddUint64(&taskIDCounter, 1)
	return fmt.Sprintf("task-%s-%d", time.Now().Format("20060102150405"), id)
}

func generateStreamID() string {
	id := atomic.AddUint64(&streamIDCounter, 1)
	return fmt.Sprintf("stream-%s-%d", time.Now().Format("20060102150405"), id)
}

func generateWorkerID() string {
	id := atomic.AddUint64(&workerIDCounter, 1)
	return fmt.Sprintf("worker-%s-%d", time.Now().Format("20060102150405"), id)
}
