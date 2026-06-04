<template>
  <div class="page-container">
    <div class="page-header">
      <h2 class="page-title">系统设置</h2>
    </div>

    <el-tabs v-model="activeTab" tab-position="left">
      <el-tab-pane label="基础配置" name="basic">
        <div class="card">
          <h3>平台基础配置</h3>
          <el-form :model="basicForm" label-width="160px">
            <el-form-item label="平台名称">
              <el-input v-model="basicForm.platformName" />
            </el-form-item>
            <el-form-item label="平台Logo URL">
              <el-input v-model="basicForm.logoUrl" placeholder="Logo图片URL" />
            </el-form-item>
            <el-form-item label="访问地址">
              <el-input v-model="basicForm.baseUrl" placeholder="https://cicd.example.com" />
            </el-form-item>
            <el-form-item label="默认时区">
              <el-select v-model="basicForm.timezone">
                <el-option label="Asia/Shanghai (UTC+8)" value="Asia/Shanghai" />
                <el-option label="UTC" value="UTC" />
                <el-option label="America/New_York" value="America/New_York" />
                <el-option label="Europe/London" value="Europe/London" />
              </el-select>
            </el-form-item>
            <el-form-item label="会话超时时间(分钟)">
              <el-input-number v-model="basicForm.sessionTimeout" :min="5" :max="1440" />
            </el-form-item>
            <el-form-item label="默认制品保留策略">
              <el-alert
                title="制品保留策略"
                type="info"
                :closable="false"
                show-icon
                style="max-width: 600px"
              >
                <template #title>
                  <span>最近30天全部保留，30-90天保留最新3个版本，90天以上自动清理</span>
                </template>
              </el-alert>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveBasicConfig">保存配置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>

      <el-tab-pane label="通知配置" name="notification">
        <div class="card">
          <h3>通知通道配置</h3>
          <el-table :data="notificationChannels" border>
            <el-table-column prop="type" label="通道类型" width="150">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'">{{ getChannelName(row.type) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="配置名称" width="180" />
            <el-table-column label="配置" width="300">
              <template #default="{ row }">
                <span class="config-preview">{{ getConfigPreview(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="100" align="center">
              <template #default="{ row }">
                <el-switch v-model="row.enabled" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template #default="{ row }">
                <el-button link @click="editChannel(row)">编辑</el-button>
                <el-button type="danger" link @click="testChannel(row)">测试</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top: 16px">
            <el-button type="primary" :icon="Plus">添加通道</el-button>
          </div>
        </div>

        <div class="card" style="margin-top: 20px">
          <h3>通知规则矩阵</h3>
          <el-table :data="notificationRules" border>
            <el-table-column prop="event" label="事件类型" width="180" />
            <el-table-column label="通知通道">
              <template #default="{ row }">
                <el-checkbox-group v-model="row.channels">
                  <el-checkbox value="DINGTALK">钉钉</el-checkbox>
                  <el-checkbox value="FEISHU">飞书</el-checkbox>
                  <el-checkbox value="WECOM">企业微信</el-checkbox>
                  <el-checkbox value="EMAIL">邮件</el-checkbox>
                  <el-checkbox value="SLACK">Slack</el-checkbox>
                </el-checkbox-group>
              </template>
            </el-table-column>
            <el-table-column label="接收人" width="250">
              <template #default="{ row }">
                <el-select v-model="row.recipientType" size="small" style="width: 150px; margin-right: 8px">
                  <el-option label="提交者" value="COMMITTER" />
                  <el-option label="项目成员" value="PROJECT_MEMBERS" />
                  <el-option label="指定人员" value="SPECIFIED" />
                  <el-option label="全员" value="ALL" />
                </el-select>
                <el-input
                  v-if="row.recipientType === 'SPECIFIED'"
                  v-model="row.specifyUsers"
                  size="small"
                  placeholder="用户名，逗号分隔"
                  style="width: 200px"
                />
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top: 16px">
            <el-button type="primary" @click="saveNotificationRules">保存规则</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="存储配置" name="storage">
        <div class="card">
          <h3>制品仓库配置</h3>
          <el-form label-width="160px">
            <el-card class="config-card" v-for="repo in artifactRepos" :key="repo.type">
              <template #header>
                <div class="card-header">
                  <span>{{ getRepoName(repo.type) }}</span>
                  <el-switch v-model="repo.enabled" />
                </div>
              </template>
              <el-row :gutter="20">
                <el-col :span="12">
                  <el-form-item label="仓库地址">
                    <el-input v-model="repo.url" :disabled="!repo.enabled" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="用户名">
                    <el-input v-model="repo.username" :disabled="!repo.enabled" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="密码/Token">
                    <el-input v-model="repo.password" type="password" show-password :disabled="!repo.enabled" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="默认仓库">
                    <el-radio-group v-model="repo.isDefault" :disabled="!repo.enabled">
                      <el-radio :label="true">是</el-radio>
                      <el-radio :label="false">否</el-radio>
                    </el-radio-group>
                  </el-form-item>
                </el-col>
              </el-row>
            </el-card>
            <el-form-item style="margin-top: 20px">
              <el-button type="primary" @click="saveStorageConfig">保存配置</el-button>
              <el-button @click="testStorageConnection">测试连接</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>

      <el-tab-pane label="Kubernetes配置" name="k8s">
        <div class="card">
          <h3>Kubernetes集群配置</h3>
          <el-table :data="k8sClusters" border>
            <el-table-column prop="name" label="集群名称" width="150" />
            <el-table-column prop="apiServer" label="API Server" />
            <el-table-column label="认证方式" width="120">
              <template #default="{ row }">
                <el-tag size="small">{{ row.authType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="默认Namespace" width="150">
              <template #default="{ row }">
                {{ row.defaultNamespace || '-' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="row.connected ? 'success' : 'danger'" size="small">
                  {{ row.connected ? '已连接' : '未连接' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <el-button link @click="editK8sCluster(row)">编辑</el-button>
                <el-button link @click="testK8sConnection(row)">测试</el-button>
                <el-button type="danger" link>删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="margin-top: 16px">
            <el-button type="primary" :icon="Plus">添加集群</el-button>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="系统监控" name="monitor">
        <div class="monitor-grid">
          <div class="card monitor-card">
            <h3>数据库连接</h3>
            <div class="monitor-status">
              <div class="status-indicator online">
                <span class="status-dot"></span>
                PostgreSQL 正常
              </div>
              <div class="status-indicator online">
                <span class="status-dot"></span>
                Redis 正常
              </div>
              <div class="status-indicator online">
                <span class="status-dot"></span>
                InfluxDB 正常
              </div>
            </div>
          </div>
          <div class="card monitor-card">
            <h3>gRPC服务</h3>
            <div class="monitor-info">
              <div class="info-row">
                <span class="label">监听端口:</span>
                <span class="value">50051</span>
              </div>
              <div class="info-row">
                <span class="label">已连接Runner:</span>
                <span class="value">8</span>
              </div>
              <div class="info-row">
                <span class="label">执行中任务:</span>
                <span class="value">3</span>
              </div>
            </div>
          </div>
          <div class="card monitor-card">
            <h3>系统资源</h3>
            <div class="resource-monitor">
              <div class="resource-item">
                <div class="resource-header">
                  <span>CPU使用</span>
                  <span>45%</span>
                </div>
                <el-progress :percentage="45" :stroke-width="8" />
              </div>
              <div class="resource-item">
                <div class="resource-header">
                  <span>内存使用</span>
                  <span>62%</span>
                </div>
                <el-progress :percentage="62" :stroke-width="8" :color="'#e6a23c'" />
              </div>
              <div class="resource-item">
                <div class="resource-header">
                  <span>磁盘使用</span>
                  <span>38%</span>
                </div>
                <el-progress :percentage="38" :stroke-width="8" :color="'#67c23a'" />
              </div>
            </div>
          </div>
          <div class="card monitor-card">
            <h3>Grafana大盘</h3>
            <div class="grafana-links">
              <el-button type="primary" link style="display: block; text-align: left; margin-bottom: 8px">
                <el-icon><TrendCharts /></el-icon>
                构建统计大盘
              </el-button>
              <el-button type="primary" link style="display: block; text-align: left; margin-bottom: 8px">
                <el-icon><TrendCharts /></el-icon>
                部署统计大盘
              </el-button>
              <el-button type="primary" link style="display: block; text-align: left; margin-bottom: 8px">
                <el-icon><TrendCharts /></el-icon>
                DORA指标大盘
              </el-button>
              <el-button type="primary" link style="display: block; text-align: left">
                <el-icon><TrendCharts /></el-icon>
                Runner资源监控
              </el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="安全配置" name="security">
        <div class="card">
          <h3>安全配置</h3>
          <el-form label-width="200px">
            <el-form-item label="密码最小长度">
              <el-input-number v-model="securityConfig.minPasswordLength" :min="6" :max="32" />
            </el-form-item>
            <el-form-item label="密码复杂度要求">
              <el-checkbox-group v-model="securityConfig.passwordComplexity">
                <el-checkbox label="uppercase">包含大写字母</el-checkbox>
                <el-checkbox label="lowercase">包含小写字母</el-checkbox>
                <el-checkbox label="number">包含数字</el-checkbox>
                <el-checkbox label="special">包含特殊字符</el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="登录失败锁定">
              <el-input-number v-model="securityConfig.maxLoginAttempts" :min="1" :max="10" />
              <span style="margin-left: 8px">次失败后锁定账户</span>
            </el-form-item>
            <el-form-item label="登录失败锁定时间">
              <el-input-number v-model="securityConfig.lockoutMinutes" :min="1" :max="1440" />
              <span style="margin-left: 8px">分钟</span>
            </el-form-item>
            <el-form-item label="启用双因素认证">
              <el-switch v-model="securityConfig.twoFactorEnabled" />
            </el-form-item>
            <el-form-item label="IP白名单">
              <el-input
                v-model="securityConfig.ipWhitelist"
                type="textarea"
                :rows="3"
                placeholder="每行一个IP或IP段，如: 192.168.1.0/24"
              />
              <div class="form-tip">留空表示不限制IP访问</div>
            </el-form-item>
            <el-form-item label="会话固定保护">
              <el-switch v-model="securityConfig.rotateSessionId" />
              <span style="margin-left: 8px">登录时重新生成会话ID</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveSecurityConfig">保存配置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, TrendCharts } from '@element-plus/icons-vue'

const activeTab = ref('basic')

const basicForm = reactive({
  platformName: 'CI/CD Platform',
  logoUrl: '',
  baseUrl: 'https://cicd.example.com',
  timezone: 'Asia/Shanghai',
  sessionTimeout: 120
})

const notificationChannels = ref([
  { type: 'DINGTALK', name: '钉钉通知', enabled: true, config: { webhook: 'https://oapi.dingtalk.com/robot/send?access_token=xxx' } },
  { type: 'FEISHU', name: '飞书通知', enabled: true, config: { webhook: 'https://open.feishu.cn/open-apis/bot/v2/hook/xxx' } },
  { type: 'WECOM', name: '企业微信', enabled: false, config: { webhook: 'https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx' } },
  { type: 'EMAIL', name: '邮件通知', enabled: true, config: { smtpHost: 'smtp.example.com', smtpPort: 465 } },
  { type: 'SLACK', name: 'Slack', enabled: false, config: { webhook: 'https://hooks.slack.com/services/xxx' } }
])

const notificationRules = ref([
  { event: '构建成功', channels: ['DINGTALK'], recipientType: 'COMMITTER', specifyUsers: '' },
  { event: '构建失败', channels: ['DINGTALK', 'FEISHU', 'EMAIL'], recipientType: 'COMMITTER', specifyUsers: '' },
  { event: '部署开始', channels: ['DINGTALK'], recipientType: 'PROJECT_MEMBERS', specifyUsers: '' },
  { event: '部署成功', channels: ['DINGTALK', 'FEISHU'], recipientType: 'PROJECT_MEMBERS', specifyUsers: '' },
  { event: '部署失败', channels: ['DINGTALK', 'FEISHU', 'EMAIL'], recipientType: 'ALL', specifyUsers: '' },
  { event: '需要审批', channels: ['DINGTALK', 'FEISHU'], recipientType: 'SPECIFIED', specifyUsers: 'admin,manager1' }
])

const artifactRepos = ref([
  { type: 'NEXUS', name: 'Nexus', enabled: true, url: 'https://nexus.example.com', username: 'admin', password: '******', isDefault: true },
  { type: 'HARBOR', name: 'Harbor', enabled: true, url: 'https://harbor.example.com', username: 'admin', password: '******', isDefault: false },
  { type: 'NPM', name: 'NPM Registry', enabled: false, url: 'https://npm.example.com', username: '', password: '', isDefault: false }
])

const k8sClusters = ref([
  { name: 'dev-cluster', apiServer: 'https://k8s-dev.example.com:6443', authType: 'kubeconfig', defaultNamespace: 'default', connected: true },
  { name: 'prod-cluster', apiServer: 'https://k8s-prod.example.com:6443', authType: 'serviceaccount', defaultNamespace: 'production', connected: true }
])

const securityConfig = reactive({
  minPasswordLength: 8,
  passwordComplexity: ['uppercase', 'lowercase', 'number'],
  maxLoginAttempts: 5,
  lockoutMinutes: 30,
  twoFactorEnabled: false,
  ipWhitelist: '',
  rotateSessionId: true
})

const getChannelName = (type) => {
  const map = {
    'DINGTALK': '钉钉',
    'FEISHU': '飞书',
    'WECOM': '企业微信',
    'EMAIL': '邮件',
    'SLACK': 'Slack'
  }
  return map[type] || type
}

const getConfigPreview = (row) => {
  if (row.type === 'EMAIL') {
    return `SMTP: ${row.config.smtpHost}:${row.config.smtpPort}`
  }
  return row.config.webhook || '-'
}

const getRepoName = (type) => {
  const map = {
    'NEXUS': 'Nexus (Maven/通用制品)',
    'HARBOR': 'Harbor (Docker镜像)',
    'NPM': 'NPM Registry (Node.js包)'
  }
  return map[type] || type
}

const saveBasicConfig = () => {
  ElMessage.success('基础配置已保存')
}

const saveNotificationRules = () => {
  ElMessage.success('通知规则已保存')
}

const saveStorageConfig = () => {
  ElMessage.success('存储配置已保存')
}

const saveSecurityConfig = () => {
  ElMessage.success('安全配置已保存')
}

const editChannel = (row) => {
  ElMessage.info(`编辑通道: ${row.name}`)
}

const testChannel = (row) => {
  ElMessage.success(`测试消息已发送到 ${row.name}`)
}

const testStorageConnection = () => {
  ElMessage.success('存储连接测试通过')
}

const editK8sCluster = (row) => {
  ElMessage.info(`编辑集群: ${row.name}`)
}

const testK8sConnection = (row) => {
  ElMessage.success(`${row.name} 连接测试通过`)
}

onMounted(() => {
  // 加载配置
})
</script>

<style scoped lang="scss">
h3 {
  margin: 0 0 20px 0;
  font-size: 16px;
}

.config-card {
  margin-bottom: 16px;

  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-weight: 500;
  }
}

.monitor-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.monitor-card {
  min-height: 200px;
}

.monitor-status {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.status-indicator {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;

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
    }
  }

  &.offline {
    color: #f56c6c;

    .status-dot {
      background: #f56c6c;
    }
  }
}

.monitor-info {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f2f5;

  .label {
    color: #909399;
  }

  .value {
    font-weight: 500;
  }
}

.resource-monitor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.resource-item {
  .resource-header {
    display: flex;
    justify-content: space-between;
    margin-bottom: 8px;
    font-size: 13px;
  }
}

.grafana-links {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.config-preview {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: 12px;
  color: #606266;
}

.form-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
