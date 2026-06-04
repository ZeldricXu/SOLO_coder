<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">部署历史</h2>
      <div class="filter-bar">
        <el-select v-model="filters.status" placeholder="状态" clearable style="width: 120px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="运行中" value="RUNNING" />
          <el-option label="回滚" value="ROLLBACK" />
        </el-select>
        <el-select v-model="filters.environment" placeholder="环境" clearable style="width: 150px">
          <el-option label="开发" value="dev" />
          <el-option label="预发布" value="staging" />
          <el-option label="生产" value="prod" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="loadDeployments">查询</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="deployments" v-loading="loading">
        <el-table-column label="ID" width="80">
          <template #default="{ row }">
            #{{ row.id }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">
              {{ getStatusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="serviceName" label="服务名" width="150" />
        <el-table-column prop="version" label="版本" width="150">
          <template #default="{ row }">
            <span class="version-tag">{{ row.version || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="环境" width="100">
          <template #default="{ row }">
            <el-tag :type="getEnvTagType(row.environmentName)">
              {{ row.environmentName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="部署策略" width="120">
          <template #default="{ row }">
            {{ getStrategyText(row.deploymentStrategy) }}
          </template>
        </el-table-column>
        <el-table-column prop="deployedBy" label="执行人" width="120" />
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">
            {{ formatDuration(row.durationSeconds) }}
          </template>
        </el-table-column>
        <el-table-column label="部署时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.deployedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="viewDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'SUCCESS'"
              type="danger"
              link
              @click="rollback(row)"
            >回滚</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="pagination.page"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          layout="total, prev, pager, next, jumper"
          @current-change="loadDeployments"
        />
      </div>
    </div>

    <el-dialog v-model="showDetailDialog" title="部署详情" width="700px">
      <el-descriptions v-if="currentDeployment" :column="2" border size="small">
        <el-descriptions-item label="服务名">{{ currentDeployment.serviceName }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ currentDeployment.version }}</el-descriptions-item>
        <el-descriptions-item label="环境">{{ currentDeployment.environmentName }}</el-descriptions-item>
        <el-descriptions-item label="策略">{{ getStrategyText(currentDeployment.deploymentStrategy) }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <span :class="['status-tag', 'status-' + currentDeployment.status?.toLowerCase()]">
            {{ getStatusText(currentDeployment.status) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="执行人">{{ currentDeployment.deployedBy }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ formatTime(currentDeployment.deployedAt) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(currentDeployment.durationSeconds) }}</el-descriptions-item>
        <el-descriptions-item label="流水线执行ID" :span="2">
          {{ currentDeployment.pipelineExecutionId || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ currentDeployment.description || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="currentDeployment?.output" class="output-section">
        <h4>部署输出</h4>
        <pre class="output-content">{{ currentDeployment.output }}</pre>
      </div>
      <template #footer>
        <el-button @click="showDetailDialog = false">关闭</el-button>
        <el-button
          v-if="currentDeployment?.status === 'SUCCESS'"
          type="danger"
          @click="rollback(currentDeployment)"
        >回滚</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { deploymentAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const userStore = useUserStore()

const loading = ref(false)
const deployments = ref([])
const showDetailDialog = ref(false)
const currentDeployment = ref(null)

const filters = reactive({
  status: '',
  environment: ''
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0
})

const loadDeployments = async () => {
  loading.value = true
  try {
    const data = await deploymentAPI.list(userStore.currentProject?.id, {
      page: pagination.page - 1,
      size: pagination.size,
      ...filters
    })
    deployments.value = data.content || data || []
    pagination.total = data.totalElements || data.length || 0
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const viewDetail = (row) => {
  currentDeployment.value = row
  showDetailDialog.value = true
}

const rollback = async (row) => {
  ElMessageBox.confirm(
    `确定要回滚服务"${row.serviceName}"到版本"${row.version}"吗？`,
    '回滚确认',
    { type: 'warning' }
  ).then(async () => {
    try {
      await deploymentAPI.rollback(row.id)
      ElMessage.success('回滚已触发')
      loadDeployments()
    } catch (e) {
      console.error(e)
    }
  }).catch(() => {})
}

const getStatusText = (status) => {
  const map = {
    'SUCCESS': '成功',
    'FAILED': '失败',
    'RUNNING': '运行中',
    'PENDING': '等待中',
    'ROLLBACK': '回滚',
    'CANCELLED': '已取消'
  }
  return map[status] || status || '-'
}

const getStrategyText = (strategy) => {
  const map = {
    'ROLLING': '滚动更新',
    'BLUE_GREEN': '蓝绿部署',
    'CANARY': '金丝雀发布'
  }
  return map[strategy] || strategy || '-'
}

const getEnvTagType = (env) => {
  const map = {
    'dev': 'success',
    'staging': 'warning',
    'prod': 'danger'
  }
  return map[env] || 'info'
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
  loadDeployments()
})
</script>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.version-tag {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  background: #ecf5ff;
  color: #409eff;
  padding: 2px 8px;
  border-radius: 4px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.output-section {
  margin-top: 16px;

  h4 {
    margin: 0 0 12px 0;
    font-size: 14px;
    font-weight: 500;
  }
}

.output-content {
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  max-height: 300px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
