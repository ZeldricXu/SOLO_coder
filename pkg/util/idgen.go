package util

import (
	"fmt"
	"sync"
	"time"

	"github.com/bwmarrin/snowflake"
)

var (
	idGen     *snowflake.Node
	idGenOnce sync.Once
	idGenErr  error
)

func InitIDGenerator(nodeID int64) error {
	idGenOnce.Do(func() {
		idGen, idGenErr = snowflake.NewNode(nodeID)
	})
	return idGenErr
}

func GenerateID() int64 {
	if idGen == nil {
		if err := InitIDGenerator(1); err != nil {
			panic(fmt.Sprintf("failed to initialize ID generator: %v", err))
		}
	}
	return idGen.Generate().Int64()
}

func GenerateIDString() string {
	return fmt.Sprintf("%d", GenerateID())
}

func ExtractTime(id int64) time.Time {
	ts := (id >> 22) + 1288834974657
	return time.Unix(ts/1000, (ts%1000)*1e6)
}

func ExtractNodeID(id int64) int64 {
	return (id & 0x3FF000) >> 12
}

func ExtractStep(id int64) int64 {
	return id & 0xFFF
}
