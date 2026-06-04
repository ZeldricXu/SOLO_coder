<template>
  <div class="page-container">
    <div class="page-header">
      <div class="header-left">
        <el-button :icon="ArrowLeft" link @click="goBack" style="margin-right: 12px">返回</el-button>
        <h2 class="page-title">{{ pipeline?.name || '加载中...' }}</h2>
      </div>
      <div>
        <el-button :icon="Edit" @click="editPipeline">编辑</el-button>
        <el-button type="primary" :icon="VideoPlay" @click="showTriggerDialog = true">运行</el-button>
      </div>
    </div>

    <div class="card" v-if="pipeline">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="名称">{{ pipeline.name }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(pipeline.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ pipeline.description || '-' }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="card" v-if="pipeline">
      <div class="card-header">
        <h3>流水线定义</h3>
        <div>
          <el-button :type="editMode ? 'primary' : 'default'" @click="editMode = !editMode">
            {{ editMode ? '保存' : '编辑YAML' }}
          </el-button>
        </div>
      </div>
      <div class="yaml-editor">
        <textarea
          v-model="yamlContent"
          class="yaml-textarea"
          :disabled="!editMode"
          spellcheck="false"
        ></textarea>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <h3>执行历史</h3>
      </div>
      <el-table :data="executions" v-loading="loading">
        <el-table-column prop="executionNumber" label="#号" width="80" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">
              {{ getStatusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="branchName" label="分支" width="150" />
        <el-table-column label="触发方式" width="120">
          <template #default="{ row }">
            {{ getTriggerTypeText(row.triggerType) }}
          </template>
        </el-table-column>
        <el-table-column prop="triggeredBy" label="触发人" width="120" />
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">
            {{ formatDuration(row.durationSeconds) }}
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.startTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="viewExecution(row)">查看</el-button>
            <el-button
              v-if="row.status === 'RUNNING'"
              type="danger"
              link
              @click="cancelExecution(row)"
            >取消</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="showTriggerDialog" title="手动触发流水线" width="500px">
      <el-form :model="triggerForm" label-width="100px">
        <el-form-item label="分支">
          <el-input v-model="triggerForm.branchName" placeholder="如: main, release/v1.0" />
        </el-form-item>
        <el-form-item label="参数">
          <div v-for="(value, key) in triggerForm.params" :key="key" class="param-item">
            <span style="width: 100px; display: inline-block">{{ key }}:</span>
            <el-input v-model="triggerForm.params[key]" style="width: 300px" />
          </div>
          <el-button type="text" size="small" @click="addParam">+ 添加参数</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showTriggerDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmTrigger">确认运行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Edit, VideoPlay } from '@element-plus/icons-vue'
import { pipelineAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const pipelineId = route.params.id
const pipeline = ref(null)
const executions = ref([])
const loading = ref(false)
const editMode = ref(false)
const yamlContent = ref('')
const showTriggerDialog = ref(false)

const triggerForm = reactive({
  branchName: 'main',
  params: {}
})

const loadPipeline = async () => {
  try {
    pipeline.value = await pipelineAPI.get(pipelineId)
    yamlContent.value = pipeline.value.yamlDefinition
  } catch (e) {
    console.error(e)
  }
}

const loadExecutions = async () => {
  loading.value = true
  try {
    const data = await pipelineAPI.listExecutions(pipelineId, { page: 0, size: 20 })
    executions.value = data.content || data || []
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const editPipeline = () => {
  editMode.value = true
}

const savePipeline = async () => {
  try {
    await pipelineAPI.update(pipelineId, {
      yamlDefinition: yamlContent.value
    })
    ElMessage.success('保存成功')
    editMode.value = false
    loadPipeline()
  } catch (e) {
    console.error(e)
  }
}

const addParam = () => {
  const key = 'param' + (Object.keys(triggerForm.params).length + 1)
  triggerForm.params[key] = ''
}

const confirmTrigger = async () => {
  try {
    const result = await pipelineAPI.trigger(pipelineId, {
      branchName: triggerForm.branchName,
      params: triggerForm.params,
      triggeredBy: userStore.userInfo?.username
    })
    ElMessage.success('流水线已触发')
    showTriggerDialog.value = false
    router.push(`/pipelines/${pipelineId}/executions/${result.id}`)
  } catch (e) {
    console.error(e)
  }
}

const viewExecution = (row) => {
  router.push(`/pipelines/${pipelineId}/executions/${row.id}`)
}

const cancelExecution = async (row) => {
  try {
    await ElMessageBox.confirm('确定要取消此次执行吗？', '提示', { type: 'warning' })
    await pipelineAPI.cancelExecution(pipelineId, row.id)
    ElMessage.success('已取消')
    loadExecutions()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const goBack = () => {
  router.push('/pipelines')
}

const getStatusText = (status) => {
  const map = {
    'SUCCESS': '成功',
    'FAILED': '失败',
    'RUNNING': '运行中',
    'PENDING': '等待中',
    'CANCELLED': '已取消'
  }
  return map[status] || status
}

const getTriggerTypeText = (type) => {
  const map = {
    'MANUAL': '手动',
    'WEBHOOK': 'Webhook',
    'SCHEDULED': '定时',
    'APPROVAL': '审批'
  }
  return map[type] || type
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

onMounted(() => {
  loadPipeline()
  loadExecutions()
})
</script>

<style scoped lang="scss">
.header-left {
  display: flex;
  align-items: center;
}

.yaml-editor {
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  overflow: hidden;
}

.yaml-textarea {
  width: 100%;
  min-height: 500px;
  padding: 12px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  line-height: 1.6;
  border: none;
  outline: none;
  resize: vertical;
  background: #fff;

  &:disabled {
    background: #f5f7fa;
    color: #303133;
  }
}

.param-item {
  margin-bottom: 8px;
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
</style>
