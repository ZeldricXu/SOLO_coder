package testdata

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"techplatform/internal/dao"
)

func NewTestDAO(dbPath string) (*dao.DAO, func()) {
	if dbPath == "" {
		dbPath = filepath.Join(os.TempDir(), fmt.Sprintf("test_%d.db", time.Now().UnixNano()))
	}
	d, err := dao.NewDAO(dao.DAOConfig{
		DBPath:        dbPath,
		CacheType:     "memory",
		CacheStrategy: dao.CacheStrategyCacheAside,
		CacheTTL:      5 * time.Minute,
		MaxCacheSize:  1000,
	})
	if err != nil {
		panic(fmt.Sprintf("failed to create test DAO: %v", err))
	}
	cleanup := func() {
		d.Close()
		os.Remove(dbPath)
	}
	return d, cleanup
}

func CreateLocalDocDir(files map[string]string) (string, func()) {
	dir := filepath.Join(os.TempDir(), fmt.Sprintf("test_docs_%d", time.Now().UnixNano()))
	os.MkdirAll(dir, 0755)

	for name, content := range files {
		fp := filepath.Join(dir, name)
		os.MkdirAll(filepath.Dir(fp), 0755)
		os.WriteFile(fp, []byte(content), 0644)
	}

	cleanup := func() {
		os.RemoveAll(dir)
	}
	return dir, cleanup
}

func WriteTestConfigFile(content string) (string, func()) {
	dir := filepath.Join(os.TempDir(), fmt.Sprintf("test_config_%d", time.Now().UnixNano()))
	os.MkdirAll(dir, 0755)
	path := filepath.Join(dir, "config.yaml")
	os.WriteFile(path, []byte(content), 0644)
	cleanup := func() {
		os.RemoveAll(dir)
	}
	return path, cleanup
}
