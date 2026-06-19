package worker

import (
	"context"
	"os"
	"sync"
	"time"

	"github.com/df1-96/experiment/pkg/util"
	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"
	"github.com/shirou/gopsutil/v3/net"
	"github.com/shirou/gopsutil/v3/process"
	"go.uber.org/zap"
)

type ResourceCollector struct {
	config     CollectorConfig
	mu         sync.RWMutex
	lastInfo   ResourceInfo
	prevDiskIO disk.IOCountersStat
	prevNetIO  net.IOCountersStat
	proc       *process.Process
	cpuTimes   []cpu.TimesStat
	running    bool
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
}

func NewResourceCollector(config CollectorConfig) (*ResourceCollector, error) {
	proc, err := process.NewProcess(int32(os.Getpid()))
	if err != nil {
		util.Warn("failed to get current process", zap.Error(err))
	}

	diskIO, _ := disk.IOCounters()
	netIO, _ := net.IOCounters(false)
	cpuTimes, _ := cpu.Times(true)

	var firstDiskIO disk.IOCountersStat
	for _, v := range diskIO {
		firstDiskIO = v
		break
	}

	var firstNetIO net.IOCountersStat
	if len(netIO) > 0 {
		firstNetIO = netIO[0]
	}

	return &ResourceCollector{
		config:     config,
		proc:       proc,
		prevDiskIO: firstDiskIO,
		prevNetIO:  firstNetIO,
		cpuTimes:   cpuTimes,
	}, nil
}

func (rc *ResourceCollector) Start(ctx context.Context) error {
	rc.mu.Lock()
	defer rc.mu.Unlock()

	if rc.running {
		return nil
	}

	rc.ctx, rc.cancel = context.WithCancel(ctx)
	rc.running = true

	rc.wg.Add(1)
	go rc.collectLoop()

	util.Info("resource collector started")
	return nil
}

func (rc *ResourceCollector) Stop() error {
	rc.mu.Lock()
	defer rc.mu.Unlock()

	if !rc.running {
		return nil
	}

	rc.running = false
	rc.cancel()
	rc.wg.Wait()

	util.Info("resource collector stopped")
	return nil
}

func (rc *ResourceCollector) collectLoop() {
	defer rc.wg.Done()

	interval := rc.config.Interval
	if interval <= 0 {
		interval = 5 * time.Second
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	if _, err := rc.Collect(); err != nil {
		util.Warn("initial resource collection failed", zap.Error(err))
	}

	for {
		select {
		case <-rc.ctx.Done():
			return
		case <-ticker.C:
			if _, err := rc.Collect(); err != nil {
				util.Warn("resource collection failed", zap.Error(err))
			}
		}
	}
}

func (rc *ResourceCollector) Collect() (ResourceInfo, error) {
	info := ResourceInfo{}
	var err error

	memInfo, err := mem.VirtualMemory()
	if err == nil {
		info.TotalMemoryBytes = memInfo.Total
		info.AvailableMemoryBytes = memInfo.Available
		info.VirtualMemoryBytes = memInfo.Total
	}

	if rc.proc != nil {
		if rss, err := rc.proc.MemoryInfo(); err == nil {
			info.RSSBytes = rss.RSS
			info.VirtualMemoryBytes = rss.VMS
		}
	}

	cpuInterval := rc.config.CPUInterval
	if cpuInterval <= 0 {
		cpuInterval = 500 * time.Millisecond
	}

	cpuPercent, err := cpu.Percent(cpuInterval, false)
	if err == nil && len(cpuPercent) > 0 {
		info.CPUUsagePercent = cpuPercent[0]
	}

	perCorePercent, err := cpu.Percent(0, true)
	if err == nil {
		info.PerCoreCPUUsage = perCorePercent
		info.TotalCPUCores = int32(len(perCorePercent))
	}

	diskPartitions, err := disk.Partitions(false)
	if err == nil && len(diskPartitions) > 0 {
		diskUsage, err := disk.Usage(diskPartitions[0].Mountpoint)
		if err == nil {
			info.DiskUsageBytes = diskUsage.Used
			info.TotalDiskBytes = diskUsage.Total
		}
	}

	diskIO, err := disk.IOCounters()
	if err == nil {
		var currentDiskIO disk.IOCountersStat
		for _, v := range diskIO {
			currentDiskIO = v
			break
		}

		info.DiskIOReadBytes = currentDiskIO.ReadBytes - rc.prevDiskIO.ReadBytes
		info.DiskIOWriteBytes = currentDiskIO.WriteBytes - rc.prevDiskIO.WriteBytes
		rc.prevDiskIO = currentDiskIO
	}

	netIO, err := net.IOCounters(false)
	if err == nil && len(netIO) > 0 {
		info.NetworkIOReadBytes = netIO[0].BytesRecv - rc.prevNetIO.BytesRecv
		info.NetworkIOWriteBytes = netIO[0].BytesSent - rc.prevNetIO.BytesSent
		info.NetworkBandwidthMbps = float64(info.NetworkIOReadBytes+info.NetworkIOWriteBytes) * 8 / 1e6

		rc.prevNetIO = netIO[0]
	}

	rc.mu.Lock()
	rc.lastInfo = info
	rc.mu.Unlock()

	return info, nil
}

func (rc *ResourceCollector) GetLastInfo() ResourceInfo {
	rc.mu.RLock()
	defer rc.mu.RUnlock()
	return rc.lastInfo
}

func (rc *ResourceCollector) GetCPUUsage() (float64, []float64, error) {
	total, err := cpu.Percent(0, false)
	if err != nil {
		return 0, nil, err
	}

	perCore, err := cpu.Percent(0, true)
	if err != nil {
		return total[0], nil, err
	}

	return total[0], perCore, nil
}

func (rc *ResourceCollector) GetMemoryUsage() (uint64, uint64, uint64, uint64, error) {
	memInfo, err := mem.VirtualMemory()
	if err != nil {
		return 0, 0, 0, 0, err
	}

	var rss, vms uint64
	if rc.proc != nil {
		if memInfoProc, err := rc.proc.MemoryInfo(); err == nil {
			rss = memInfoProc.RSS
			vms = memInfoProc.VMS
		}
	}

	return memInfo.Total, memInfo.Available, rss, vms, nil
}

func (rc *ResourceCollector) GetDiskIO() (uint64, uint64, error) {
	diskIO, err := disk.IOCounters()
	if err != nil {
		return 0, 0, err
	}

	var readBytes, writeBytes uint64
	for _, v := range diskIO {
		readBytes += v.ReadBytes - rc.prevDiskIO.ReadBytes
		writeBytes += v.WriteBytes - rc.prevDiskIO.WriteBytes
		break
	}

	return readBytes, writeBytes, nil
}

func (rc *ResourceCollector) GetNetworkIO() (uint64, uint64, error) {
	netIO, err := net.IOCounters(false)
	if err != nil || len(netIO) == 0 {
		return 0, 0, err
	}

	readBytes := netIO[0].BytesRecv - rc.prevNetIO.BytesRecv
	writeBytes := netIO[0].BytesSent - rc.prevNetIO.BytesSent

	return readBytes, writeBytes, nil
}

func (rc *ResourceCollector) IsRunning() bool {
	rc.mu.RLock()
	defer rc.mu.RUnlock()
	return rc.running
}


