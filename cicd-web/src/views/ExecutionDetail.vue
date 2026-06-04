<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" link @click="goBack" style="margin-right: 12px">返回</el-button>
        <h2 class="page-title">
          执行 #{{ execution?.executionNumber }}
          <span :class="['status-tag', 'status-' + execution?.status?.toLowerCase()]" style="margin-left: 12px">
            {{ getStatusText(execution?.status) }}
          </span>
        </h2>
      </div>
      <div>
        <el-button v-if="execution?.status === 'RUNNING'" type="danger" @click="cancelExecution">
          取消执行
        </el-button>
        <el-button type="primary" @click="loadExecution">刷新</el-button>
      </div>
    </div>

    <div class="card" v-if="execution">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="流水线">{{ pipelineName }}</el-descriptions-item>
        <el-descriptions-item label="分支">{{ execution.branchName }}</el-descriptions-item>
        <el-descriptions-item label="触发方式">{{ getTriggerTypeText(execution.triggerType) }}</el-descriptions-item>
        <el-descriptions-item label="触发人">{{ execution.triggeredBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatTime(execution.startTime) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(execution.durationSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="Git Commit" :span="3">
          <span v-if="execution.gitCommitSha" class="commit-sha">{{ execution.gitCommitSha }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="execution-layout">
      <div class="stages-panel">
        <div class="card">
          <div class="card-header">
            <h3>阶段进度</h3>
          </div>
          <div class="stages-list">
            <div
              v-for="(stage, sIndex) in execution?.stageExecutions"
              :key="stage.id"
              class="stage-item"
              :class="{ active: activeStage === sIndex }"
              @click="selectStage(sIndex)"
            >
              <div class="stage-header">
                <el-icon :class="['stage-icon', 'status-' + stage.status?.toLowerCase()]">
                  <CheckCircleFilled v-if="stage.status === 'SUCCESS'" />
                  <CircleCloseFilled v-else-if="stage.status === 'FAILED'" />
                  <Loading v-else-if="stage.status === 'RUNNING'" class="spin" />
                  <Clock v-else />
                </el-icon>
                <span class="stage-name">{{ stage.name }}</span>
                <span class="stage-duration">{{ formatDuration(stage.durationSeconds) }}</span>
              </div>
              <div v-if="activeStage === sIndex" class="jobs-list">
                <div
                  v-for="(job, jIndex) in stage.jobExecutions"
                  :key="job.id"
                  class="job-item"
                  :class="{ active: activeJob === jIndex }"
                  @click.stop="selectJob(sIndex, jIndex)"
                >
                  <el-icon :class="['job-icon', 'status-' + job.status?.toLowerCase()]">
                    <Check v-if="job.status === 'SUCCESS'" />
                    <Close v-else-if="job.status === 'FAILED'" />
                    <Loading v-else-if="job.status === 'RUNNING'" class="spin" />
                    <MoreFilled v-else />
                  </el-icon>
                  <span class="job-name">{{ job.name }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="logs-panel">
        <div class="card">
          <div class="card-header">
            <h3>
              执行日志
              <span v-if="currentStep" class="current-step"> - {{ currentStep.name }}</span>
            </h3>
            <div class="log-controls">
              <el-button size="small" :icon="RefreshRight" @click="clearLogs">清空</el-button>
              <el-button size="small" :type="autoScroll ? 'primary' : 'default'" @click="autoScroll = !autoScroll">
                {{ autoScroll ? '自动滚动' : '暂停滚动' }}
              </el-button>
            </div>
          </div>
          <div class="steps-tabs">
            <div
              v-for="(step, index) in currentJob?.stepExecutions"
              :key="step.id"
              class="step-tab"
              :class="{ active: activeStep === index, 'status-' + step.status?.toLowerCase() }"
              @click="selectStep(index)"
            >
              <span class="step-number">{{ index + 1 }}</span>
              <span class="step-name">{{ step.name || step.type }}</span>
            </div>
          </div>
          <div class="log-container" ref="logContainer">
            <pre class="log-content" v-html="formattedLogs"></pre>
            <div v-if="loadingLogs" class="log-loading">
              <el-icon class="spin"><Loading /></el-icon>
              加载中...
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft, CheckCircleFilled, CircleCloseFilled, Clock, Loading,
  Check, Close, MoreFilled, RefreshRight
} from '@element-plus/icons-vue'
import { pipelineAPI, logsAPI } from '@/api'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()

const pipelineId = route.params.pipelineId
const executionId = route.params.executionId

const execution = ref(null)
const pipelineName = ref('')
const activeStage = ref(0)
const activeJob = ref(0)
const activeStep = ref(0)
const logs = ref({})
const loadingLogs = ref(false)
const autoScroll = ref(true)
const logContainer = ref(null)
const ws = ref(null)

const currentStage = computed(() => {
  return execution.value?.stageExecutions?.[activeStage.value]
})

const currentJob = computed(() => {
  return currentStage.value?.jobExecutions?.[activeJob.value]
})

const currentStep = computed(() => {
  return currentJob.value?.stepExecutions?.[activeStep.value]
})

const formattedLogs = computed(() => {
  if (!currentStep.value) return ''
  const stepId = currentStep.value.id
  const logContent = logs.value[stepId] || ''
  return logContent.split('\n').map(line => {
    let colorClass = 'log-default'
    if (line.includes('[ERROR]') || line.includes('ERROR') || line.includes('✗')) {
      colorClass = 'log-error'
    } else if (line.includes('[INFO]') || line.includes('INFO')) {
      colorClass = 'log-info'
    } else if (line.includes('[WARN]') || line.includes('WARN') || line.includes('!')) {
      colorClass = 'log-warn'
    } else if (line.startsWith('+') || line.includes('✓') || line.includes('SUCCESS')) {
      colorClass = 'log-success'
    }
    return `<span class="${colorClass}">${escapeHtml(line)}</span>`
  }).join('\n')
})

const escapeHtml = (text) => {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

const loadExecution = async () => {
  try {
    const [execData, pipelineData] = await Promise.all([
      pipelineAPI.getExecution(pipelineId, executionId),
      pipelineAPI.get(pipelineId)
    ])
    execution.value = execData
    pipelineName.value = pipelineData.name
    if (execData.status === 'RUNNING') {
      connectWebSocket()
    } else {
      loadAllLogs()
    }
  } catch (e) {
    console.error(e)
  }
}

const loadAllLogs = async () => {
  if (!execution.value?.stageExecutions) return
  for (const stage of execution.value.stageExecutions) {
    for (const job of stage.jobExecutions) {
      for (const step of job.stepExecutions) {
        if (step.status && step.status !== 'PENDING') {
          loadStepLogs(step.id)
        }
      }
    }
  }
}

const loadStepLogs = async (stepId) => {
  try {
    const data = await logsAPI.getLogs(stepId, { follow: false })
    logs.value[stepId] = (data.content || data || '')
  } catch (e) {
    console.error(e)
  }
}

const connectWebSocket = () => {
  const wsUrl = logsAPI.streamLogs(executionId).replace('/api', '')
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const fullUrl = `${protocol}//${host}${wsUrl}`

  ws.value = new WebSocket(fullUrl)

  ws.value.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      if (data.type === 'log') {
        if (!logs.value[data.stepId]) {
          logs.value[data.stepId] = ''
        }
        logs.value[data.stepId] += data.content
        nextTick(scrollToBottom)
      } else if (data.type === 'status') {
        loadExecution()
      }
    } catch (e) {
      console.error('Parse log error:', e)
    }
  }

  ws.value.onerror = (e) => {
    console.error('WebSocket error:', e)
  }

  ws.value.onclose = () => {
    console.log('WebSocket closed')
  }
}

const scrollToBottom = () => {
  if (autoScroll.value && logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

const selectStage = (index) => {
  activeStage.value = index
  activeJob.value = 0
  activeStep.value = 0
  const job = currentStage.value?.jobExecutions?.[0]
  if (job?.stepExecutions?.[0]) {
    loadStepLogs(job.stepExecutions[0].id)
  }
}

const selectJob = (sIndex, jIndex) => {
  activeStage.value = sIndex
  activeJob.value = jIndex
  activeStep.value = 0
  const step = currentJob.value?.stepExecutions?.[0]
  if (step) {
    loadStepLogs(step.id)
  }
}

const selectStep = (index) => {
  activeStep.value = index
  const step = currentJob.value?.stepExecutions?.[index]
  if (step) {
    loadStepLogs(step.id)
  }
}

const clearLogs = () => {
  if (currentStep.value) {
    logs.value[currentStep.value.id] = ''
  }
}

const cancelExecution = async () => {
  try {
    await ElMessageBox.confirm('确定要取消此次执行吗？', '提示', { type: 'warning' })
    await pipelineAPI.cancelExecution(pipelineId, executionId)
    ElMessage.success('已取消')
    loadExecution()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const goBack = () => {
  router.push(`/pipelines/${pipelineId}`)
}

const getStatusText = (status) => {
  const map = {
    'SUCCESS': '成功',
    'FAILED': '失败',
    'RUNNING': '运行中',
    'PENDING': '等待中',
    'CANCELLED': '已取消'
  }
  return map[status] || status || '-'
}

const getTriggerTypeText = (type) => {
  const map = {
    'MANUAL': '手动',
    'WEBHOOK': 'Webhook',
    'SCHEDULED': '定时',
    'APPROVAL': '审批'
  }
  return map[type] || type || '-'
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatDuration = (seconds) => {
  if (!seconds) return '-'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`
}

watch(currentStep, (newStep) => {
  if (newStep && !logs.value[newStep.id]) {
    loadStepLogs(newStep.id)
  }
})

onMounted(() => {
  loadExecution()
})

onUnmounted(() => {
  if (ws.value) {
    ws.value.close()
  }
})
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
}

.commit-sha {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.execution-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.stages-panel {
  width: 320px;
  flex-shrink: 0;
}

.logs-panel {
  flex: 1;
  min-width: 0;
}

.stages-list {
  max-height: 600px;
  overflow-y: auto;
}

.stage-item {
  margin-bottom: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.2s;

  &.active {
    border-color: #409eff;
    box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
  }

  &:hover {
    border-color: #c0c4cc;
  }
}

.stage-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #fafafa;

  &:hover {
    background: #f5f7fa;
  }
}

.stage-icon {
  margin-right: 12px;
  font-size: 18px;

  &.status-success { color: #67c23a; }
  &.status-failed { color: #f56c6c; }
  &.status-running { color: #409eff; }
  &.status-pending { color: #909399; }
}

.stage-name {
  flex: 1;
  font-weight: 500;
}

.stage-duration {
  color: #909399;
  font-size: 13px;
}

.jobs-list {
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.job-item {
  display: flex;
  align-items: center;
  padding: 10px 16px 10px 44px;
  border-bottom: 1px solid #f0f2f5;
  cursor: pointer;

  &.active {
    background: #ecf5ff;
  }

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: #f5f7fa;
  }

  &.active:hover {
    background: #ecf5ff;
  }
}

.job-icon {
  margin-right: 10px;
  font-size: 14px;

  &.status-success { color: #67c23a; }
  &.status-failed { color: #f56c6c; }
  &.status-running { color: #409eff; }
  &.status-pending { color: #909399; }
}

.job-name {
  font-size: 13px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h3 {
    margin: 0;
    font-size: 16px;
  }
}

.current-step {
  color: #909399;
  font-weight: normal;
  font-size: 14px;
}

.log-controls {
  display: flex;
  gap: 8px;
}

.steps-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.step-tab {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s;

  &.active {
    background: #ecf5ff;
    border-color: #409eff;
    color: #409eff;
  }

  &.status-success { border-left: 3px solid #67c23a; }
  &.status-failed { border-left: 3px solid #f56c6c; }
  &.status-running { border-left: 3px solid #409eff; }
  &.status-pending { border-left: 3px solid #909399; }

  &:hover {
    background: #ebeef5;
  }

  &.active:hover {
    background: #ecf5ff;
  }
}

.step-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: #dcdfe6;
  color: #fff;
  border-radius: 50%;
  font-size: 12px;
  margin-right: 8px;

  .active & {
    background: #409eff;
  }
}

.log-container {
  height: 500px;
  overflow-y: auto;
  background: #1e1e1e;
  border-radius: 6px;
  padding: 16px;
  position: relative;
}

.log-content {
  margin: 0;
  font-family: 'Monaco', 'Menlo', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  color: #d4d4d4;

  :deep(.log-default) { color: #d4d4d4; }
  :deep(.log-info) { color: #569cd6; }
  :deep(.log-success) { color: #6a9955; }
  :deep(.log-warn) { color: #dcdcaa; }
  :deep(.log-error) { color: #f48771; }
}

.log-loading {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(255, 255, 255, 0.1);
  padding: 8px 16px;
  border-radius: 20px;
  color: #d4d4d4;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
