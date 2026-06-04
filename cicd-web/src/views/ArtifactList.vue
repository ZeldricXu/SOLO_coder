<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">制品管理</h2>
      <div>
        <el-button type="danger" :icon="Delete" @click="triggerCleanup">清理过期制品</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="制品列表" name="list">
        <div class="card">
          <div class="filter-bar">
            <el-select v-model="filters.type" placeholder="制品类型" clearable style="width: 150px">
              <el-option label="JAR" value="JAR" />
              <el-option label="DOCKER" value="DOCKER" />
              <el-option label="NPM" value="NPM" />
              <el-option label="WAR" value="WAR" />
              <el-option label="ZIP" value="ZIP" />
            </el-select>
            <el-input
              v-model="filters.name"
              placeholder="制品名称"
              clearable
              style="width: 200px"
              @keyup.enter="loadArtifacts"
            />
            <el-button type="primary" :icon="Search" @click="loadArtifacts">查询</el-button>
          </div>
          <el-table :data="artifacts" v-loading="loading">
            <el-table-column label="ID" width="80">
              <template #default="{ row }">
                #{{ row.id }}
              </template>
            </el-table-column>
            <el-table-column prop="name" label="名称" width="200" />
            <el-table-column prop="version" label="版本" width="150">
              <template #default="{ row }">
                <span class="version-tag">{{ row.version }}</span>
              </template>
            </el-table-column>
            <el-table-column label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="repository" label="仓库" width="180" />
            <el-table-column label="Git Commit" width="180">
              <template #default="{ row }">
                <span v-if="row.gitCommitSha" class="commit-sha" :title="row.gitCommitSha">
                  {{ row.gitCommitSha.substring(0, 8) }}
                </span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100">
              <template #default="{ row }">
                {{ formatSize(row.sizeBytes) }}
              </template>
            </el-table-column>
            <el-table-column label="构建时间" width="180">
              <template #default="{ row }">
                {{ formatTime(row.buildTime) }}
              </template>
            </el-table-column>
            <el-table-column label="置顶" width="80">
              <template #default="{ row }">
                <el-tooltip :content="row.pinned ? '取消置顶' : '置顶'">
                  <el-switch
                    :model-value="row.pinned"
                    size="small"
                    @change="togglePin(row)"
                  />
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewTrace(row)">溯源</el-button>
                <el-button link @click="viewHistory(row)">历史</el-button>
                <el-button type="danger" link @click="deleteArtifact(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="pagination">
            <el-pagination
              v-model:current-page="pagination.page"
              v-model:page-size="pagination.size"
              :total="pagination.total"
              layout="total, prev, pager, next, jumper"
              @current-change="loadArtifacts"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="保留策略" name="policy">
        <div class="card">
          <h3>制品保留策略</h3>
          <div class="policy-rules">
            <div class="policy-item">
              <div class="policy-icon green">
                <el-icon><Check /></el-icon>
              </div>
              <div class="policy-content">
                <h4>最近30天</h4>
                <p>全部保留</p>
              </div>
            </div>
            <div class="policy-item">
              <div class="policy-icon yellow">
                <el-icon><Warning /></el-icon>
              </div>
              <div class="policy-content">
                <h4>30-90天</h4>
                <p>只保留最新的3个版本</p>
              </div>
            </div>
            <div class="policy-item">
              <div class="policy-icon red">
                <el-icon><Delete /></el-icon>
              </div>
              <div class="policy-content">
                <h4>90天以上</h4>
                <p>自动清理</p>
              </div>
            </div>
          </div>
          <el-alert
            title="置顶的制品不会被自动清理"
            type="info"
            :closable="false"
            style="margin-top: 16px"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showTraceDialog" title="制品溯源" width="600px">
      <el-descriptions v-if="traceData" :column="1" border size="small">
        <el-descriptions-item label="制品名称">{{ traceData.artifact?.name }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ traceData.artifact?.version }}</el-descriptions-item>
        <el-descriptions-item label="Git Commit">
          <span class="commit-sha">{{ traceData.artifact?.gitCommitSha }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="构建ID">
          <el-link type="primary" @click="goToExecution(traceData.build)">
            #{{ traceData.build?.id }}
          </el-link>
        </el-descriptions-item>
        <el-descriptions-item label="构建时间">{{ formatTime(traceData.build?.startTime) }}</el-descriptions-item>
        <el-descriptions-item label="触发人">{{ traceData.build?.triggeredBy || '-' }}</el-descriptions-item>
        <el-descriptions-item label="构建参数">
          <pre class="params-content">{{ JSON.stringify(traceData.build?.params || {}, null, 2) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <el-dialog v-model="showHistoryDialog" title="版本历史" width="700px">
      <el-table :data="artifactHistory" v-loading="historyLoading">
        <el-table-column prop="version" label="版本" width="180">
          <template #default="{ row }">
            <span class="version-tag">{{ row.version }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Git Commit" width="150">
          <template #default="{ row }">
            <span v-if="row.gitCommitSha" class="commit-sha">
              {{ row.gitCommitSha.substring(0, 8) }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">
            {{ formatSize(row.sizeBytes) }}
          </template>
        </el-table-column>
        <el-table-column label="构建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.buildTime) }}
          </template>
        </el-table-column>
        <el-table-column label="置顶" width="80">
          <template #default="{ row }">
            <el-icon v-if="row.pinned" color="#409eff"><Star /></el-icon>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Delete, Check, Warning, Star } from '@element-plus/icons-vue'
import { artifactAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('list')
const loading = ref(false)
const historyLoading = ref(false)
const artifacts = ref([])
const traceData = ref(null)
const artifactHistory = ref([])
const showTraceDialog = ref(false)
const showHistoryDialog = ref(false)
const currentArtifact = ref(null)

const filters = reactive({
  type: '',
  name: ''
})

const pagination = reactive({
  page: 1,
  size: 20,
  total: 0
})

const loadArtifacts = async () => {
  loading.value = true
  try {
    const data = await artifactAPI.list(userStore.currentProject?.id, {
      page: pagination.page - 1,
      size: pagination.size,
      ...filters
    })
    artifacts.value = data.content || data || []
    pagination.total = data.totalElements || data.length || 0
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const togglePin = async (row) => {
  try {
    if (row.pinned) {
      await artifactAPI.unpin(row.id)
    } else {
      await artifactAPI.pin(row.id)
    }
    ElMessage.success(row.pinned ? '已取消置顶' : '已置顶')
    loadArtifacts()
  } catch (e) {
    console.error(e)
  }
}

const viewTrace = async (row) => {
  try {
    traceData.value = await artifactAPI.trace({
      name: row.name,
      version: row.version
    })
    showTraceDialog.value = true
  } catch (e) {
    console.error(e)
  }
}

const viewHistory = async (row) => {
  currentArtifact.value = row
  historyLoading.value = true
  try {
    artifactHistory.value = await artifactAPI.listHistory(userStore.currentProject?.id, row.name)
    showHistoryDialog.value = true
  } catch (e) {
    console.error(e)
  } finally {
    historyLoading.value = false
  }
}

const deleteArtifact = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除制品"${row.name}:${row.version}"吗？`,
      '提示',
      { type: 'warning' }
    )
    await artifactAPI.delete(row.id)
    ElMessage.success('删除成功')
    loadArtifacts()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const triggerCleanup = async () => {
  try {
    await ElMessageBox.confirm(
      '确定要执行过期制品清理吗？将按照保留策略清理过期制品。',
      '清理确认',
      { type: 'warning' }
    )
    await artifactAPI.triggerCleanup()
    ElMessage.success('清理任务已触发')
    loadArtifacts()
  } catch (e) {
    if (e !== 'cancel') {
      console.error(e)
    }
  }
}

const goToExecution = (build) => {
  if (build?.pipelineId && build?.id) {
    router.push(`/pipelines/${build.pipelineId}/executions/${build.id}`)
  }
}

const formatTime = (time) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

onMounted(() => {
  loadArtifacts()
})
</script>

<style scoped lang="scss">
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
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

.commit-sha {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.pagination {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

.params-content {
  margin: 0;
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  max-height: 200px;
  overflow-y: auto;
}

.policy-rules {
  display: flex;
  gap: 24px;
  margin-top: 16px;
}

.policy-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  flex: 1;
}

.policy-icon {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #fff;

  &.green { background: #67c23a; }
  &.yellow { background: #e6a23c; }
  &.red { background: #f56c6c; }
}

.policy-content {
  h4 {
    margin: 0 0 4px 0;
    font-size: 16px;
  }

  p {
    margin: 0;
    color: #606266;
    font-size: 14px;
  }
}

h3 {
  margin: 0;
  font-size: 16px;
}
</style>
