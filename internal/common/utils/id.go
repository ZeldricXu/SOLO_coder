package utils

import (
	"github.com/google/uuid"
	"strings"
)

func GenerateID(prefix string) string {
	id := uuid.New().String()
	if prefix != "" {
		return prefix + "_" + strings.ReplaceAll(id, "-", "")[:16]
	}
	return strings.ReplaceAll(id, "-", "")[:32]
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

func GenerateTxID() string {
	return GenerateID("tx")
}
