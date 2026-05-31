package providers

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type LocalStorageProvider struct {
	basePath string
}

func NewLocalStorageProvider(basePath string) *LocalStorageProvider {
	_ = os.MkdirAll(basePath, 0755)
	return &LocalStorageProvider{basePath: basePath}
}

func (p *LocalStorageProvider) Name() string {
	return "local"
}

func (p *LocalStorageProvider) Backup(ctx context.Context, source, destination string, options map[string]interface{}) (int64, int, error) {
	destPath := filepath.Join(p.basePath, destination)
	destDir := filepath.Dir(destPath)
	if err := os.MkdirAll(destDir, 0755); err != nil {
		return 0, 0, err
	}

	compress := true
	if optsCompress, ok := options["compress"].(bool); ok {
		compress = optsCompress
	}

	if compress {
		return p.createZipBackup(source, destPath)
	}

	return p.copyBackup(source, destPath)
}

func (p *LocalStorageProvider) createZipBackup(source, destPath string) (int64, int, error) {
	zipFile, err := os.Create(destPath + ".zip")
	if err != nil {
		return 0, 0, err
	}
	defer zipFile.Close()

	zipWriter := zip.NewWriter(zipFile)
	defer zipWriter.Close()

	var totalSize int64
	var fileCount int

	err = filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		relPath, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}

		header, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		header.Name = relPath
		header.Method = zip.Deflate

		writer, err := zipWriter.CreateHeader(header)
		if err != nil {
			return err
		}

		file, err := os.Open(path)
		if err != nil {
			return err
		}
		defer file.Close()

		size, err := io.Copy(writer, file)
		if err != nil {
			return err
		}

		totalSize += size
		fileCount++
		return nil
	})

	return totalSize, fileCount, err
}

func (p *LocalStorageProvider) copyBackup(source, destPath string) (int64, int, error) {
	var totalSize int64
	var fileCount int

	err := filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		relPath, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}

		destFile := filepath.Join(destPath, relPath)
		destDir := filepath.Dir(destFile)
		if err := os.MkdirAll(destDir, 0755); err != nil {
			return err
		}

		srcFile, err := os.Open(path)
		if err != nil {
			return err
		}
		defer srcFile.Close()

		outFile, err := os.Create(destFile)
		if err != nil {
			return err
		}
		defer outFile.Close()

		size, err := io.Copy(outFile, srcFile)
		if err != nil {
			return err
		}

		totalSize += size
		fileCount++
		return nil
	})

	return totalSize, fileCount, err
}

func (p *LocalStorageProvider) Restore(ctx context.Context, backupPath, destination string, options map[string]interface{}) (int, int64, error) {
	backupFullPath := filepath.Join(p.basePath, backupPath) + ".zip"

	if _, err := os.Stat(backupFullPath); errors.Is(err, os.ErrNotExist) {
		backupFullPath = filepath.Join(p.basePath, backupPath)
	}

	if _, err := os.Stat(backupFullPath); errors.Is(err, os.ErrNotExist) {
		return 0, 0, fmt.Errorf("backup not found: %s", backupPath)
	}

	if err := os.MkdirAll(destination, 0755); err != nil {
		return 0, 0, err
	}

	zipReader, err := zip.OpenReader(backupFullPath)
	if err != nil {
		return p.restoreCopy(backupFullPath, destination)
	}
	defer zipReader.Close()

	var fileCount int
	var totalSize int64

	for _, file := range zipReader.File {
		filePath := filepath.Join(destination, file.Name)

		if file.FileInfo().IsDir() {
			_ = os.MkdirAll(filePath, os.ModePerm)
			continue
		}

		if err := os.MkdirAll(filepath.Dir(filePath), os.ModePerm); err != nil {
			return fileCount, totalSize, err
		}

		rc, err := file.Open()
		if err != nil {
			return fileCount, totalSize, err
		}

		outFile, err := os.Create(filePath)
		if err != nil {
			rc.Close()
			return fileCount, totalSize, err
		}

		size, err := io.Copy(outFile, rc)
		rc.Close()
		outFile.Close()

		if err != nil {
			return fileCount, totalSize, err
		}

		totalSize += size
		fileCount++
	}

	return fileCount, totalSize, nil
}

func (p *LocalStorageProvider) restoreCopy(source, destination string) (int, int64, error) {
	var fileCount int
	var totalSize int64

	err := filepath.Walk(source, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}

		relPath, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}

		destFile := filepath.Join(destination, relPath)
		destDir := filepath.Dir(destFile)
		if err := os.MkdirAll(destDir, 0755); err != nil {
			return err
		}

		srcFile, err := os.Open(path)
		if err != nil {
			return err
		}
		defer srcFile.Close()

		outFile, err := os.Create(destFile)
		if err != nil {
			return err
		}
		defer outFile.Close()

		size, err := io.Copy(outFile, srcFile)
		if err != nil {
			return err
		}

		totalSize += size
		fileCount++
		return nil
	})

	return fileCount, totalSize, err
}

func (p *LocalStorageProvider) Delete(ctx context.Context, path string) error {
	fullPath := filepath.Join(p.basePath, path)
	return os.RemoveAll(fullPath)
}

func (p *LocalStorageProvider) List(ctx context.Context, prefix string) ([]string, error) {
	fullPath := filepath.Join(p.basePath, prefix)

	var files []string
	err := filepath.Walk(fullPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() {
			relPath, _ := filepath.Rel(p.basePath, path)
			files = append(files, relPath)
		}
		return nil
	})

	return files, err
}

func (p *LocalStorageProvider) Exists(ctx context.Context, path string) (bool, error) {
	fullPath := filepath.Join(p.basePath, path)
	_, err := os.Stat(fullPath)
	if err == nil {
		return true, nil
	}
	if os.IsNotExist(err) {
		return false, nil
	}
	return false, err
}

func (p *LocalStorageProvider) GetMetadata(ctx context.Context, path string) (map[string]interface{}, error) {
	fullPath := filepath.Join(p.basePath, path)
	info, err := os.Stat(fullPath)
	if err != nil {
		return nil, err
	}

	return map[string]interface{}{
		"name":    info.Name(),
		"size":    info.Size(),
		"mode":    info.Mode(),
		"modTime": info.ModTime(),
		"isDir":   info.IsDir(),
	}, nil
}
