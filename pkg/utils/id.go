package utils

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
)

var (
	counter uint64
)

func GenerateID(prefix string) string {
	return fmt.Sprintf("%s_%s", prefix, GenerateShortID())
}

func GenerateShortID() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func GenerateUUID() string {
	return uuid.New().String()
}

func GenerateTraceID() string {
	now := time.Now().UnixNano()
	cnt := atomic.AddUint64(&counter, 1)
	return fmt.Sprintf("%x-%d", now, cnt)
}

func GenerateRunID() string {
	return GenerateID("run")
}

func GenerateEntityID() string {
	return GenerateID("ent")
}

func GenerateConfigID() string {
	return GenerateID("cfg")
}

func GenerateSnapshotID() string {
	return GenerateID("snap")
}

func GenerateResourceID() string {
	return GenerateID("rsc")
}

func GenerateBatchID() string {
	return GenerateID("batch")
}

func GenerateTimestamp() int64 {
	return time.Now().UnixNano() / int64(time.Millisecond)
}
