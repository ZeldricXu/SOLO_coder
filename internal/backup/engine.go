package backup

import (
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync"
	"syscall"
	"time"

	"backupmanager/internal/logger"
	"backupmanager/internal/storage"
	"backupmanager/internal/verify"
	"backupmanager/internal/version"
	"backupmanager/pkg/models"
)

const (
	DefaultHashWorkers    = 0
	SmallFileThreshold    = 10 * 1024 * 1024
	LargeFileThreshold    = 100 * 1024 * 1024
	MemoryReserveRatio    = 0.25
	MinConcurrentWorkers  = 1
	MaxConcurrentWorkers  = 64
	BufferSizePerFile     = 64 * 1024
)

type Engine struct {
	storage         *storage.Storage
	manager         *version.Manager
	verifier        *verify.Verifier
	logger          *logger.Logger
	hashWorkers     int
	dynamicConcurrency bool
}

func NewEngine(storage *storage.Storage, manager *version.Manager, verifier *verify.Verifier, log *logger.Logger) *Engine {
	return NewEngineWithOptions(storage, manager, verifier, log, 0, true)
}

func NewEngineWithOptions(storage *storage.Storage, manager *version.Manager, verifier *verify.Verifier, log *logger.Logger, hashWorkers int, dynamicConcurrency bool) *Engine {
	if hashWorkers <= 0 {
		hashWorkers = runtime.NumCPU()
	}
	return &Engine{
		storage:            storage,
		manager:            manager,
		verifier:           verifier,
		logger:             log,
		hashWorkers:        hashWorkers,
		dynamicConcurrency: dynamicConcurrency,
	}
}

func (e *Engine) SetHashWorkers(count int) {
	if count <= 0 {
		count = runtime.NumCPU()
	}
	e.hashWorkers = count
	e.logger.Info("Hash worker count set to: %d", e.hashWorkers)
}

func (e *Engine) GetHashWorkers() int {
	return e.hashWorkers
}

func (e *Engine) SetDynamicConcurrency(enabled bool) {
	e.dynamicConcurrency = enabled
	if enabled {
		e.logger.Info("Dynamic concurrency enabled")
	} else {
		e.logger.Info("Dynamic concurrency disabled, using fixed worker count: %d", e.hashWorkers)
	}
}

func (e *Engine) CalculateOptimalWorkers(files []*models.FileInfo) int {
	if !e.dynamicConcurrency {
		return e.hashWorkers
	}

	if len(files) == 0 {
		return MinConcurrentWorkers
	}

	totalMemory := getTotalMemory()
	availableMemory := getAvailableMemory()
	safeMemory := uint64(float64(availableMemory) * (1 - MemoryReserveRatio))

	var totalSize int64
	var smallFileCount, mediumFileCount, largeFileCount int

	for _, f := range files {
		totalSize += f.Size
		switch {
		case f.Size < SmallFileThreshold:
			smallFileCount++
		case f.Size < LargeFileThreshold:
			mediumFileCount++
		default:
			largeFileCount++
		}
	}

	avgFileSize := float64(totalSize) / float64(len(files))

	cpuCount := runtime.NumCPU()

	var sizeFactor float64
	switch {
	case avgFileSize < float64(SmallFileThreshold):
		sizeFactor = 1.5
	case avgFileSize < float64(LargeFileThreshold):
		sizeFactor = 1.0
	default:
		sizeFactor = 0.5
	}

	memoryPerWorker := estimateMemoryPerWorker(files)
	memoryBasedWorkers := int(safeMemory / memoryPerWorker)
	if memoryBasedWorkers < MinConcurrentWorkers {
		memoryBasedWorkers = MinConcurrentWorkers
	}

	cpuBasedWorkers := int(float64(cpuCount) * sizeFactor)

	optimalWorkers := memoryBasedWorkers
	if cpuBasedWorkers < optimalWorkers {
		optimalWorkers = cpuBasedWorkers
	}

	if optimalWorkers < MinConcurrentWorkers {
		optimalWorkers = MinConcurrentWorkers
	}
	if optimalWorkers > MaxConcurrentWorkers {
		optimalWorkers = MaxConcurrentWorkers
	}
	if optimalWorkers > len(files) {
		optimalWorkers = len(files)
	}

	e.logger.Debug("Concurrency calculation: files=%d, avg_size=%.2fMB, memory_available=%dMB, cpu=%d, optimal_workers=%d",
		len(files), avgFileSize/1024/1024, availableMemory/1024/1024, cpuCount, optimalWorkers)

	return optimalWorkers
}

func getTotalMemory() uint64 {
	in := &syscall.RLIMIT{}
	err := syscall.Getrlimit(syscall.RLIMIT_AS, in)
	if err != nil || in.Cur == syscall.RLIM_INFINITY {
		return 4 * 1024 * 1024 * 1024
	}
	return uint64(in.Cur)
}

func getAvailableMemory() uint64 {
	in := &syscall.RLIMIT{}
	err := syscall.Getrlimit(syscall.RLIMIT_AS, in)
	if err != nil || in.Cur == syscall.RLIM_INFINITY {
		return 2 * 1024 * 1024 * 1024
	}
	return uint64(in.Cur) / 2
}

func estimateMemoryPerWorker(files []*models.FileInfo) uint64 {
	if len(files) == 0 {
		return BufferSizePerFile * 10
	}

	var maxSize int64
	for _, f := range files {
		if f.Size > maxSize {
			maxSize = f.Size
		}
	}

	estimated := uint64(BufferSizePerFile)
	if maxSize > int64(SmallFileThreshold) {
		estimated += uint64(maxSize) / 100
	}

	if estimated < 10*1024*1024 {
		estimated = 10 * 1024 * 1024
	}

	return estimated
}

type fileTask struct {
	path string
	size int64
}

func (e *Engine) Backup(sourcePath string) (*models.BackupResult, error) {
	startTime := time.Now()
	result := &models.BackupResult{
		Success: false,
		Errors:  make([]string, 0),
	}

	if err := verify.IsSourceDirectoryValid(sourcePath); err != nil {
		result.Errors = append(result.Errors, err.Error())
		return result, err
	}

	e.logger.Info("Starting backup from: %s (dynamic concurrency: %v)", sourcePath, e.dynamicConcurrency)

	lastVersion, err := e.manager.GetLatestVersion(sourcePath)
	if err != nil {
		e.logger.Warn("Failed to get latest version: %v", err)
	}

	backupType := "incremental"
	if lastVersion == nil {
		backupType = "full"
		e.logger.Info("No previous version found, performing full backup")
	}

	versionInfo, err := e.manager.CreateVersion(sourcePath, backupType)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to create version: %v", err)
		return result, err
	}
	result.VersionID = versionInfo.VersionID

	versionPath, err := e.storage.CreateVersionDirectory(versionInfo.VersionID)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to create version directory: %v", err)
		return result, err
	}

	currentFiles, err := e.scanSourceDirectory(sourcePath)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to scan source directory: %v", err)
		return result, err
	}
	result.FileCount = len(currentFiles)

	var previousFiles []*models.FileInfo
	if lastVersion != nil {
		previousFiles, err = e.storage.LoadFileList(lastVersion.VersionID)
		if err != nil {
			e.logger.Warn("Failed to load previous file list: %v", err)
			previousFiles = []*models.FileInfo{}
		}
	}

	changes, err := verify.CompareFileInfo(previousFiles, currentFiles)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to compare files: %v", err)
		return result, err
	}

	for _, c := range changes {
		c.VersionID = versionInfo.VersionID
		switch c.ChangeType {
		case "added":
			result.AddedCount++
		case "modified":
			result.ModifiedCount++
		case "deleted":
			result.DeletedCount++
		}
	}
	result.ChangedCount = len(changes)

	filesToCopy := make([]*models.FileChangeRecord, 0)
	for _, c := range changes {
		if c.ChangeType == "added" || c.ChangeType == "modified" {
			filesToCopy = append(filesToCopy, c)
		}
	}

	backupSize, copyErrors := e.copyChangedFiles(sourcePath, versionPath, filesToCopy, currentFiles)
	if len(copyErrors) > 0 {
		result.Errors = append(result.Errors, copyErrors...)
	}
	result.BackupSize = backupSize

	if err := e.storage.SaveFileList(versionInfo.VersionID, currentFiles); err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to save file list: %v", err)
	}

	if err := e.storage.SaveChangeRecords(versionInfo.VersionID, changes); err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to save change records: %v", err)
	}

	checksum, err := e.manager.ComputeVersionChecksum(versionInfo.VersionID)
	if err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Warn("Failed to compute version checksum: %v", err)
	}

	e.manager.UpdateVersionStats(versionInfo, len(currentFiles), len(changes), backupSize, checksum)
	if err := e.manager.SaveVersion(versionInfo); err != nil {
		result.Errors = append(result.Errors, err.Error())
		e.logger.Error("Failed to save version: %v", err)
	}

	result.Duration = time.Since(startTime)
	result.Success = len(result.Errors) == 0

	status := "success"
	if !result.Success {
		status = "partial"
	}

	e.logger.Log("backup", versionInfo.VersionID, status, result.Duration, result.Errors)
	e.logger.Info("Backup completed: version=%s, files=%d, changes=%d, size=%d bytes, duration=%v",
		versionInfo.VersionID, result.FileCount, result.ChangedCount, result.BackupSize, result.Duration)

	return result, nil
}

func (e *Engine) scanSourceDirectory(sourcePath string) ([]*models.FileInfo, error) {
	var files []*models.FileInfo
	var mu sync.Mutex

	fileTasks, err := e.collectFileTasks(sourcePath)
	if err != nil {
		return nil, err
	}

	if len(fileTasks) == 0 {
		return []*models.FileInfo{}, nil
	}

	tempFiles := make([]*models.FileInfo, 0, len(fileTasks))
	for _, ft := range fileTasks {
		tempFiles = append(tempFiles, &models.FileInfo{
			FullPath: ft.path,
			Size:     ft.size,
		})
	}

	workerCount := e.CalculateOptimalWorkers(tempFiles)
	e.logger.Info("Scanning directory with %d hash workers (dynamic=%v)", workerCount, e.dynamicConcurrency)

	sort.Slice(fileTasks, func(i, j int) bool {
		return fileTasks[i].size > fileTasks[j].size
	})

	smallTasks := make([]fileTask, 0)
	largeTasks := make([]fileTask, 0)
	for _, ft := range fileTasks {
		if ft.size >= LargeFileThreshold {
			largeTasks = append(largeTasks, ft)
		} else {
			smallTasks = append(smallTasks, ft)
		}
	}

	var wg sync.WaitGroup
	errors := make(chan error, workerCount*2)
	results := make(chan *models.FileInfo, len(fileTasks))

	largeWorkerCount := 1
	if len(largeTasks) > 1 && workerCount > 2 {
		largeWorkerCount = 2
	}
	smallWorkerCount := workerCount - largeWorkerCount
	if smallWorkerCount < 1 {
		smallWorkerCount = 1
	}

	smallChan := make(chan fileTask, len(smallTasks))
	largeChan := make(chan fileTask, len(largeTasks))

	for _, t := range smallTasks {
		smallChan <- t
	}
	close(smallChan)

	for _, t := range largeTasks {
		largeChan <- t
	}
	close(largeChan)

	worker := func(id int, tasks <-chan fileTask, isLarge bool) {
		defer wg.Done()
		for task := range tasks {
			info, err := e.processFile(task.path, sourcePath)
			if err != nil {
				errors <- err
				continue
			}
			results <- info
		}
	}

	for i := 0; i < smallWorkerCount; i++ {
		wg.Add(1)
		go worker(i, smallChan, false)
	}

	for i := 0; i < largeWorkerCount; i++ {
		wg.Add(1)
		go worker(smallWorkerCount+i, largeChan, true)
	}

	go func() {
		wg.Wait()
		close(results)
		close(errors)
	}()

	var errList []error
	done := make(chan struct{})

	go func() {
		defer close(done)
		for info := range results {
			mu.Lock()
			files = append(files, info)
			mu.Unlock()
		}
	}()

	for err := range errors {
		errList = append(errList, err)
	}

	<-done

	if len(errList) > 0 {
		return files, errList[0]
	}

	e.logger.Debug("Scanned %d files", len(files))
	return files, nil
}

func (e *Engine) collectFileTasks(sourcePath string) ([]fileTask, error) {
	var tasks []fileTask

	err := filepath.Walk(sourcePath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if !info.IsDir() {
			tasks = append(tasks, fileTask{
				path: path,
				size: info.Size(),
			})
		}
		return nil
	})

	if err != nil {
		return nil, err
	}

	return tasks, nil
}

func (e *Engine) processFile(fullPath, sourcePath string) (*models.FileInfo, error) {
	info, err := os.Stat(fullPath)
	if err != nil {
		return nil, err
	}

	relativePath, err := filepath.Rel(sourcePath, fullPath)
	if err != nil {
		return nil, err
	}

	hash, err := verify.ComputeFileHash(fullPath)
	if err != nil {
		e.logger.Warn("Failed to compute hash for %s: %v", fullPath, err)
	}

	return &models.FileInfo{
		RelativePath: filepath.ToSlash(relativePath),
		FullPath:     fullPath,
		Size:         info.Size(),
		ModTime:      info.ModTime(),
		Hash:         hash,
	}, nil
}

func (e *Engine) copyChangedFiles(sourcePath, versionPath string, changes []*models.FileChangeRecord, currentFiles []*models.FileInfo) (int64, []string) {
	var totalSize int64
	var errors []string
	filesDir := filepath.Join(versionPath, "files")

	fileMap := make(map[string]*models.FileInfo)
	for _, f := range currentFiles {
		fileMap[f.RelativePath] = f
	}

	for _, change := range changes {
		srcPath := filepath.Join(sourcePath, filepath.FromSlash(change.FilePath))
		destPath := filepath.Join(filesDir, filepath.FromSlash(change.FilePath))

		e.logger.Debug("Copying: %s -> %s", srcPath, destPath)

		if err := e.storage.CopyFile(srcPath, destPath); err != nil {
			e.logger.Error("Failed to copy file %s: %v", srcPath, err)
			errors = append(errors, err.Error())
			continue
		}

		if fileInfo, ok := fileMap[change.FilePath]; ok {
			totalSize += fileInfo.Size
		}
	}

	return totalSize, errors
}
