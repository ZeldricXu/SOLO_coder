<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">Runner管理</h2>
      <div>
        <el-button type="primary" :icon="Plus" @click="showCreateDialog = true">
          注册Runner
        </el-button>
      </div>
    </div>

    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-icon online">
          <el-icon><CircleCheck /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.online }}</div>
          <div class="stat-label">在线</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon offline">
          <el-icon><CircleClose /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.offline }}</div>
          <div class="stat-label">离线</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon busy">
          <el-icon><Loading /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.busy }}</div>
          <div class="stat-label">忙碌</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon total">
          <el-icon><Cpu /></el-icon>
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">总计</div>
        </div>
      </div>
    </div>

    <div class="card">
      <el-table :data="runners" v-loading="loading">
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tooltip :content="getStatusTooltip(row)">
              <span class="status-indicator" :class="getRunnerStatus(row)">
                <span class="status-dot"></span>
                {{ getRunnerStatusText(row) }}
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column prop="hostName" label="主机名" width="150" />
        <el-table-column prop="ipAddress" label="IP地址" width="140" />
        <el-table-column label="标签" width="200">
          <template #default="{ row }">
            <el-tag
              v-for="tag in row.tags"
              :key="tag"
              size="small"
              style="margin-right: 4px; margin-bottom: 4px"
            >
              {{ tag }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="系统信息" width="180">
          <template #default="{ row }">
            <div class="system-info">
              <span>{{ row.os }} / {{ row.architecture }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="资源使用">
          <template #default="{ row }">
            <div class="resource-usage">
              <div class="usage-item">
                <span class="usage-label">CPU</span>
                <el-progress
                  :percentage="row.cpuUsage || 0"
                  :stroke-width="8"
                  :show-text="false"
                  :color="getUsageColor(row.cpuUsage)"
                />
                <span class="usage-value">{{ row.cpuUsage || 0 }}%</span>
              </div>
              <div class="usage-item">
                <span class="usage-label">内存</span>
                <el-progress
                  :percentage="row.memoryUsage || 0"
                  :stroke-width="8"
                  :show-text="false"
                  :color="getUsageColor(row.memoryUsage)"
                />
                <span class="usage-value">{{ row.memoryUsage || 0 }}%</span>
              </div>
              <div class="usage-item">
                <span class="usage-label">磁盘</span>
                <el-progress
                  :percentage="row.diskUsage || 0"
                  :stroke-width="8"
                  :show-text="false"
                  :color="getUsageColor(row.diskUsage)"
                />
                <span class="usage-value">{{ row.diskUsage || 0 }}%</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="当前任务" width="150">
          <template #default="{ row }">
            <span v-if="row.currentJobId" class="current-job">
              Job #{{ row.currentJobId }}
            </span>
            <span v-else class="text-placeholder">空闲</span>
          </template>
        </el-table-column>
        <el-table-column label="最后心跳" width="180">
          <template #default="{ row }">
            {{ formatTime(row.lastHeartbeat) }}
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.registeredAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link @click="viewRunner(row)">详情</el-button>
            <el-button type="danger" link @click="deleteRunner(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showCreateDialog" title="注册Runner" width="500px">
      <el-form :model="runnerForm" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="runnerForm.name" placeholder="Runner名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="runnerForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="runnerForm.tags"
            multiple
            filterable
            allow-create
            placeholder="添加标签，如: docker, gpu, java"
          >
            <el-option label="docker" value="docker" />
            <el-option label="gpu" value="gpu" />
            <el-option label="java" value="java" />
            <el-option label="go" value="go" />
            <el-option label="node" value="node" />
            <el-option label="python" value="python" />
            <el-option label="k8s" value="k8s" />
          </el-select>
          <div class="form-tip">标签用于匹配任务，带有特定标签的Runner只会执行对应标签的Job</div>
        </el-form-item>
        <el-form-item label="最大并发任务">
          <el-input-number v-model="runnerForm.maxConcurrentJobs" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="Token">
          <div class="token-display">
            <code>{{ generatedToken }}</code>
            <el-button size="small" @click="copyToken">
              <el-icon><CopyDocument /></el-icon>
            </el-button>
          </div>
          <div class="form-tip">请妥善保存此Token，Runner启动时需要使用</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" @click="createRunner">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showDetailDialog" title="Runner详情" width="600px">
      <el-descriptions v-if="currentRunner" :column="2" border size="small">
        <el-descriptions-item label="名称">{{ currentRunner.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <span class="status-indicator" :class="getRunnerStatus(currentRunner)">
            <span class="status-dot"></span>
            {{ getRunnerStatusText(currentRunner) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="主机名">{{ currentRunner.hostName }}</el-descriptions-item>
        <el-descriptions-item label="IP地址">{{ currentRunner.ipAddress }}</el-descriptions-item>
        <el-descriptions-item label="操作系统">{{ currentRunner.os }}</el-descriptions-item>
        <el-descriptions-item label="架构">{{ currentRunner.architecture }}</el-descriptions-item>
        <el-descriptions-item label="CPU核心">{{ currentRunner.cpuCores || '-' }}</el-descriptions-item>
        <el-descriptions-item label="总内存">
          {{ currentRunner.totalMemory ? formatBytes(currentRunner.totalMemory) : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="标签" :span="2">
          <el-tag
            v-for="tag in currentRunner.tags"
            :key="tag"
            size="small"
            style="margin-right: 4px"
          >
            {{ tag }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="版本">{{ currentRunner.version || '-' }}</el-descriptions-item>
        <el-descriptions-item label="最大并发">{{ currentRunner.maxConcurrentJobs }}</el-descriptions-item>
        <el-descriptions-item label="已执行任务">{{ currentRunner.executedJobs || 0 }}</el-descriptions-item>
        <el-descriptions-item label="最后心跳">{{ formatTime(currentRunner.lastHeartbeat) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="showDetailDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, CircleCheck, CircleClose, Loading, Cpu, CopyDocument } from '@element-plus/icons-vue'
import { runnerAPI } from '@/api'
import dayjs from 'dayjs'

const loading = ref(false)
const runners = ref([])
const showCreateDialog = ref(false)
const showDetailDialog = ref(false)
const currentRunner = ref(null)

const generatedToken = ref(generateToken())

function generateToken() {
  return 'cicd-runner-' + Array.from({ length: 32 }, () =>
    Math.floor(Math.random() * 16).toString(16)
  ).join('')
}

const runnerForm = reactive({
  name: '',
  description: '',
  tags: [],
  maxConcurrentJobs: 2
})

const stats = computed(() => {
  const result = { online: 0, offline: 0, busy: 0, total: runners.value.length }
  runners.value.forEach(runner => {
    const status = getRunnerStatus(runner)
    if (status === 'online') result.online++
    else if (status === 'offline') result.offline++
    if (runner.currentJobId) result.busy++
  })
  return result
})

const loadRunners = async () => {
  loading.value = true
  try {
    const data = await runnerAPI.list()
    runners.value = data.content || data || []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const getRunnerStatus = (runner) => {
  if (!runner.lastHeartbeat) return 'offline'
  const diff = dayjs().diff(dayjs(runner.lastHeartbeat), 'second')
  if (diff > 60) return 'offline'
  if (runner.currentJobId) return 'busy'
  return 'online'
}

const getRunnerStatusText = (runner) => {
  const status = getRunnerStatus(runner)
  const map = {
    'online': '在线',
    'offline': '离线',
    'busy': '忙碌'
  }
  return map[status]
}

const getStatusTooltip = (runner) => {
  const status = getRunnerStatus(runner)
  if (status === 'offline') {
    return runner.lastHeartbeat ? `最后心跳: ${formatTime(runner.lastHeartbeat)}` : '未连接'
  }
  return runner.currentJobId ? `正在执行Job #${runner.currentJobId}` : '空闲可用'
}

const getUsageColor = (usage) => {
  if (usage >= 80) return '#f56c6c'
  if (usage >= 60) return '#e6a23c'
  return '#67c23a'
}

const viewRunner = (row) => {
  currentRunner.value = row
  showDetailDialog.value = true
}

const copyToken = () => {
  navigator.clipboard.writeText(generatedToken.value)
  ElMessage.success('Token已复制')
}

const createRunner = async () => {
  try {
    await runnerAPI.create({
      ...runnerForm,
      token: generatedToken.value
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    generatedToken.value = generateToken()
    runnerForm.name = ''
    runnerForm.description = ''
    runnerForm.tags = []
    runnerForm.maxConcurrentJobs = 2
    loadRunners()
  } catch (e) {
    console.error(e)
  }
}

const deleteRunner = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除Runner"${row.name}"吗？`,
      '提示',
      { type: 'warning' }
    )
    await runnerAPI.delete(row.id)
    ElMessage.success('删除成功')
    loadRunners()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatBytes = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`
}

onMounted(() => {
  loadRunners()
})
</script>

<style scoped lang="scss">
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px;
  background: #fff;
  border-radius: 12px;
  border: 1px solid #e4e7ed;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: #fff;

  &.online { background: #67c23a; }
  &.offline { background: #f56c6c; }
  &.busy { background: #e6a23c; }
  &.total { background: #409eff; }
}

.stat-info {
  .stat-value {
    font-size: 24px;
    font-weight: 600;
    color: #303133;
  }

  .stat-label {
    font-size: 13px;
    color: #909399;
  }
}

.status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;

  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #909399;
  }

  &.online {
    color: #67c23a;

    .status-dot {
      background: #67c23a;
      box-shadow: 0 0 0 3px rgba(103, 194, 58, 0.2);
    }
  }

  &.offline {
    color: #909399;

    .status-dot {
      background: #909399;
    }
  }

  &.busy {
    color: #e6a23c;

    .status-dot {
      background: #e6a23c;
      animation: pulse 1.5s infinite;
    }
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.system-info {
  font-size: 13px;
  color: #606266;
}

.resource-usage {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.usage-item {
  display: flex;
  align-items: center;
  gap: 8px;

  .usage-label {
    width: 36px;
    font-size: 12px;
    color: #909399;
  }

  .usage-value {
    width: 40px;
    font-size: 12px;
    text-align: right;
    color: #606266;
  }

  :deep(.el-progress) {
    flex: 1;
  }
}

.current-job {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  color: #409eff;
}

.text-placeholder {
  color: #909399;
}

.token-display {
  display: flex;
  align-items: center;
  gap: 8px;

  code {
    flex: 1;
    padding: 8px 12px;
    background: #f5f7fa;
    border-radius: 4px;
    font-family: 'Monaco', 'Menlo', monospace;
    font-size: 13px;
    word-break: break-all;
  }
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
