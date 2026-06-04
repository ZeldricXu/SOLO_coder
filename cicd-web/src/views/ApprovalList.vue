<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">审批中心</h2>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane label="待我审批" name="pending">
        <div class="card">
          <el-empty v-if="pendingApprovals.length === 0 && !loading" description="暂无待审批事项" />
          <el-table v-else :data="pendingApprovals" v-loading="loading">
            <el-table-column label="ID" width="80">
              <template #default="{ row }">
                #{{ row.id }}
              </template>
            </el-table-column>
            <el-table-column prop="pipelineExecution.pipeline.name" label="流水线" width="200" />
            <el-table-column label="执行 #号" width="100">
              <template #default="{ row }">
                #{{ row.pipelineExecution?.executionNumber }}
              </template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag :type="getEnvTagType(row.environmentName)">
                  {{ row.environmentName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="审批模式" width="120">
              <template #default="{ row }">
                {{ getModeText(row.approvalMode) }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">
                  {{ getStatusText(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="审批进度" width="150">
              <template #default="{ row }">
                <div class="approval-progress">
                  <span class="progress-text">
                    {{ row.approvedCount || 0 }} / {{ row.approvers?.length || 0 }}
                  </span>
                  <el-progress
                    :percentage="getApprovalProgress(row)"
                    :stroke-width="6"
                    :show-text="false"
                  />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="过期时间" width="180">
              <template #default="{ row }">
                <span :class="{ 'text-danger': isExpired(row) }">
                  {{ formatTime(row.expiresAt) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="180">
              <template #default="{ row }">
                {{ formatTime(row.createdAt) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewDetail(row)">查看</el-button>
                <el-button type="success" link @click="showApproveDialog(row)">通过</el-button>
                <el-button type="danger" link @click="showRejectDialog(row)">拒绝</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="已审批" name="history">
        <div class="card">
          <el-table :data="approvalHistory" v-loading="historyLoading">
            <el-table-column label="ID" width="80">
              <template #default="{ row }">
                #{{ row.id }}
              </template>
            </el-table-column>
            <el-table-column prop="pipelineExecution.pipeline.name" label="流水线" width="200" />
            <el-table-column label="执行 #号" width="100">
              <template #default="{ row }">
                #{{ row.pipelineExecution?.executionNumber }}
              </template>
            </el-table-column>
            <el-table-column label="环境" width="100">
              <template #default="{ row }">
                <el-tag :type="getEnvTagType(row.environmentName)">
                  {{ row.environmentName }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <span :class="['status-tag', 'status-' + row.status?.toLowerCase()]">
                  {{ getStatusText(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="我的决策" width="100">
              <template #default="{ row }">
                <el-tag v-if="getMyDecision(row)" :type="getMyDecision(row) === 'APPROVED' ? 'success' : 'danger'">
                  {{ getMyDecision(row) === 'APPROVED' ? '通过' : '拒绝' }}
                </el-tag>
                <span v-else class="text-placeholder">-</span>
              </template>
            </el-table-column>
            <el-table-column label="完成时间" width="180">
              <template #default="{ row }">
                {{ formatTime(row.decidedAt) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="viewDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showDetailDialog" title="审批详情" width="700px">
      <el-descriptions v-if="currentApproval" :column="2" border size="small">
        <el-descriptions-item label="流水线">
          {{ currentApproval.pipelineExecution?.pipeline?.name }}
        </el-descriptions-item>
        <el-descriptions-item label="执行 #号">
          #{{ currentApproval.pipelineExecution?.executionNumber }}
        </el-descriptions-item>
        <el-descriptions-item label="环境">
          <el-tag :type="getEnvTagType(currentApproval.environmentName)">
            {{ currentApproval.environmentName }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="审批模式">
          {{ getModeText(currentApproval.approvalMode) }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <span :class="['status-tag', 'status-' + currentApproval.status?.toLowerCase()]">
            {{ getStatusText(currentApproval.status) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="过期时间">
          {{ formatTime(currentApproval.expiresAt) }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="section-title">审批人列表</div>
      <div class="approver-list">
        <div
          v-for="(approver, index) in currentApproval?.approvers"
          :key="index"
          class="approver-item"
        >
          <div class="approver-avatar">{{ approver.charAt(0).toUpperCase() }}</div>
          <div class="approver-info">
            <span class="approver-name">{{ approver }}</span>
            <span class="approver-status" :class="getDecisionClass(currentApproval, approver)">
              {{ getDecisionText(currentApproval, approver) }}
            </span>
          </div>
        </div>
      </div>

      <div v-if="currentApproval?.decisions?.length > 0" class="section-title">审批意见</div>
      <div v-if="currentApproval?.decisions?.length > 0" class="decisions-list">
        <div v-for="decision in currentApproval.decisions" :key="decision.id" class="decision-item">
          <div class="decision-header">
            <span class="decision-user">{{ decision.approver }}</span>
            <el-tag :type="decision.decision === 'APPROVED' ? 'success' : 'danger'" size="small">
              {{ decision.decision === 'APPROVED' ? '通过' : '拒绝' }}
            </el-tag>
            <span class="decision-time">{{ formatTime(decision.decidedAt) }}</span>
          </div>
          <div v-if="decision.comment" class="decision-comment">
            {{ decision.comment }}
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="showDetailDialog = false">关闭</el-button>
        <template v-if="currentApproval?.status === 'PENDING'">
          <el-button type="success" @click="showApproveDialog(currentApproval)">通过</el-button>
          <el-button type="danger" @click="showRejectDialog(currentApproval)">拒绝</el-button>
        </template>
      </template>
    </el-dialog>

    <el-dialog v-model="showApproveDialog" title="审批通过" width="500px">
      <el-form :model="approveForm" label-width="100px">
        <el-form-item label="审批意见">
          <el-input
            v-model="approveForm.comment"
            type="textarea"
            :rows="3"
            placeholder="请输入审批意见（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showApproveDialog = false">取消</el-button>
        <el-button type="success" @click="confirmApprove">确认通过</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showRejectDialog" title="审批拒绝" width="500px">
      <el-form :model="rejectForm" label-width="100px">
        <el-form-item label="拒绝原因" required>
          <el-input
            v-model="rejectForm.comment"
            type="textarea"
            :rows="3"
            placeholder="请输入拒绝原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showRejectDialog = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { approvalAPI } from '@/api'
import { useUserStore } from '@/store/user'
import dayjs from 'dayjs'

const userStore = useUserStore()

const activeTab = ref('pending')
const loading = ref(false)
const historyLoading = ref(false)
const pendingApprovals = ref([])
const approvalHistory = ref([])
const showDetailDialog = ref(false)
const showApproveDialog = ref(false)
const showRejectDialog = ref(false)
const currentApproval = ref(null)

const approveForm = reactive({
  comment: ''
})

const rejectForm = reactive({
  comment: ''
})

const loadPendingApprovals = async () => {
  loading.value = true
  try {
    pendingApprovals.value = await approvalAPI.pending()
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const loadApprovalHistory = async () => {
  historyLoading.value = true
  try {
    const data = await approvalAPI.history(userStore.currentProject?.id, { page: 0, size: 50 })
    approvalHistory.value = data.content || data || []
  } catch (e) {
    console.error(e)
  } finally {
    historyLoading.value = false
  }
}

const viewDetail = (row) => {
  currentApproval.value = row
  showDetailDialog.value = true
}

const showApproveDialog = (row) => {
  currentApproval.value = row
  approveForm.comment = ''
  showApproveDialog.value = true
}

const showRejectDialog = (row) => {
  currentApproval.value = row
  rejectForm.comment = ''
  showRejectDialog.value = true
}

const confirmApprove = async () => {
  try {
    await approvalAPI.approve(currentApproval.value.id, {
      approver: userStore.userInfo?.username,
      comment: approveForm.comment
    })
    ElMessage.success('已通过')
    showApproveDialog.value = false
    showDetailDialog.value = false
    loadPendingApprovals()
  } catch (e) {
    console.error(e)
  }
}

const confirmReject = async () => {
  if (!rejectForm.comment) {
    ElMessage.warning('请输入拒绝原因')
    return
  }
  try {
    await approvalAPI.reject(currentApproval.value.id, {
      approver: userStore.userInfo?.username,
      comment: rejectForm.comment
    })
    ElMessage.success('已拒绝')
    showRejectDialog.value = false
    showDetailDialog.value = false
    loadPendingApprovals()
  } catch (e) {
    console.error(e)
  }
}

const getApprovalProgress = (row) => {
  const total = row.approvers?.length || 0
  if (total === 0) return 0
  return Math.round(((row.approvedCount || 0) / total) * 100)
}

const isExpired = (row) => {
  return row.expiresAt && dayjs(row.expiresAt).isBefore(dayjs())
}

const getMyDecision = (row) => {
  const username = userStore.userInfo?.username
  const decision = row.decisions?.find(d => d.approver === username)
  return decision?.decision
}

const getDecisionText = (approval, approver) => {
  const decision = approval.decisions?.find(d => d.approver === approver)
  if (!decision) return '待审批'
  return decision.decision === 'APPROVED' ? '已通过' : '已拒绝'
}

const getDecisionClass = (approval, approver) => {
  const decision = approval.decisions?.find(d => d.approver === approver)
  if (!decision) return 'pending'
  return decision.decision === 'APPROVED' ? 'approved' : 'rejected'
}

const getStatusText = (status) => {
  const map = {
    'PENDING': '待审批',
    'APPROVED': '已通过',
    'REJECTED': '已拒绝',
    'EXPIRED': '已过期'
  }
  return map[status] || status || '-'
}

const getModeText = (mode) => {
  const map = {
    'ALL': '全部通过',
    'ANY': '任一通过'
  }
  return map[mode] || mode || '-'
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

onMounted(() => {
  loadPendingApprovals()
  loadApprovalHistory()
})
</script>

<style scoped lang="scss">
.approval-progress {
  display: flex;
  align-items: center;
  gap: 8px;

  .progress-text {
    font-size: 13px;
    color: #606266;
    white-space: nowrap;
  }

  :deep(.el-progress) {
    flex: 1;
  }
}

.text-danger {
  color: #f56c6c;
}

.text-placeholder {
  color: #909399;
}

.section-title {
  margin: 20px 0 12px 0;
  font-weight: 500;
  font-size: 14px;
}

.approver-list {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.approver-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.approver-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 500;
}

.approver-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.approver-name {
  font-weight: 500;
}

.approver-status {
  font-size: 12px;

  &.pending { color: #909399; }
  &.approved { color: #67c23a; }
  &.rejected { color: #f56c6c; }
}

.decisions-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.decision-item {
  padding: 12px 16px;
  background: #f5f7fa;
  border-radius: 8px;
}

.decision-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.decision-user {
  font-weight: 500;
}

.decision-time {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.decision-comment {
  color: #606266;
  font-size: 13px;
  padding-top: 8px;
  border-top: 1px solid #e4e7ed;
}
</style>
