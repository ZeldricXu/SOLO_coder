<template>
  <div class="distribution-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>分发管理</span>
          <el-button type="primary" @click="showChannelDialog">
            <i class="el-icon-plus"></i>
            添加渠道
          </el-button>
        </div>
      </template>

      <el-row :gutter="20">
        <el-col :span="16">
          <h3 style="margin-bottom: 15px;">分发渠道</h3>
          
          <div class="channel-list" v-if="channels.length > 0">
            <div
              class="channel-card"
              v-for="channel in channels"
              :key="channel.config_id"
            >
              <div class="channel-header">
                <div class="channel-type">
                  <i :class="getChannelIcon(channel.channel_type) + ' type-icon'"></i>
                  <div class="type-info">
                    <div class="channel-name">{{ channel.channel_name }}</div>
                    <div class="channel-platform">{{ channel.channel_type | channelTypeText }}</div>
                  </div>
                </div>
                <el-switch
                  v-model="channel.is_active"
                  @change="toggleChannelStatus(channel)"
                ></el-switch>
              </div>
              <div class="channel-status">
                <el-tag :type="channel.is_active ? 'success' : 'info'" size="small">
                  {{ channel.is_active ? '已启用' : '已禁用' }}
                </el-tag>
                <span class="created-time">
                  创建于 {{ channel.created_at | formatDate('YYYY-MM-DD') }}
                </span>
              </div>
              <div class="channel-actions">
                <el-button
                  type="text"
                  size="small"
                  @click="editChannel(channel)"
                >
                  编辑
                </el-button>
                <el-button
                  type="text"
                  size="small"
                  class="danger"
                  @click="deleteChannel(channel)"
                >
                  删除
                </el-button>
              </div>
            </div>
          </div>

          <div class="empty-state" v-else>
            <i class="el-icon-share empty-icon"></i>
            <div class="empty-text">暂无分发渠道</div>
            <el-button type="primary" @click="showChannelDialog" style="margin-top: 15px;">
              添加第一个渠道
            </el-button>
          </div>
        </el-col>

        <el-col :span="8">
          <h3 style="margin-bottom: 15px;">快速分发</h3>
          
          <el-card v-if="selectedMediaList.length > 0">
            <template #header>
              <div style="display: flex; justify-content: space-between; align-items: center;">
                <span>已选择媒体 ({{ selectedMediaList.length }})</span>
                <el-button type="text" size="small" @click="clearSelection">
                  清空
                </el-button>
              </div>
            </template>
            <div class="selected-media-list">
              <div
                class="selected-media-item"
                v-for="media in selectedMediaList"
                :key="media.media_id"
              >
                <i :class="media.file_type | fileTypeIcon"></i>
                <span class="media-name" :title="media.filename">{{ media.filename }}</span>
                <el-tag
                  :type="media.status | statusType"
                  size="mini"
                  style="margin-left: auto;"
                >
                  {{ media.status | statusText }}
                </el-tag>
                <el-button
                  type="text"
                  size="mini"
                  icon="el-icon-close"
                  class="remove-btn"
                  @click="removeMedia(media.media_id)"
                ></el-button>
              </div>
            </div>
          </el-card>

          <el-card style="margin-top: 20px;">
            <template #header>
              <span>选择目标渠道</span>
            </template>
            <el-checkbox-group v-model="selectedChannels">
              <el-checkbox
                v-for="channel in channels.filter(c => c.is_active)"
                :key="channel.config_id"
                :label="channel.config_id"
                style="display: block; margin-bottom: 10px;"
              >
                <i :class="getChannelIcon(channel.channel_type)" style="margin-right: 5px;"></i>
                {{ channel.channel_name }}
              </el-checkbox>
            </el-checkbox-group>
            
            <el-divider></el-divider>
            
            <el-form :model="distributionForm" label-position="top">
              <el-form-item label="分发标题">
                <el-input
                  v-model="distributionForm.title"
                  placeholder="输入分发标题（默认使用文件名）"
                ></el-input>
              </el-form-item>
              <el-form-item label="描述">
                <el-input
                  v-model="distributionForm.description"
                  type="textarea"
                  :rows="3"
                  placeholder="输入分发描述"
                ></el-input>
              </el-form-item>
              <el-form-item label="标签">
                <el-input
                  v-model="distributionForm.tagsInput"
                  placeholder="多个标签用逗号分隔"
                ></el-input>
              </el-form-item>
            </el-form>
            
            <el-button
              type="primary"
              :disabled="selectedMediaList.length === 0 || selectedChannels.length === 0 || isPushing"
              :loading="isPushing"
              style="width: 100%;"
              @click="startDistribution"
            >
              {{ isPushing ? '推送中...' : '开始推送' }}
            </el-button>
          </el-card>

          <el-card style="margin-top: 20px;" v-if="channels.filter(c => c.is_active).length === 0">
            <el-alert
              title="暂无可用渠道"
              type="warning"
              :closable="false"
              show-icon
            >
              <span slot="default">请先添加并启用至少一个分发渠道</span>
              <el-button type="text" @click="showChannelDialog">添加渠道</el-button>
            </el-alert>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-card style="margin-top: 20px;">
      <template #header>
        <div class="card-header">
          <span>分发任务</span>
          <el-button size="small" @click="refreshTasks">
            <i class="el-icon-refresh"></i>
            刷新
          </el-button>
        </div>
      </template>

      <el-table
        v-if="distributionTasks.length > 0"
        :data="distributionTasks"
        style="width: 100%"
        stripe
      >
        <el-table-column label="任务ID" prop="task_id" width="180">
          <template slot-scope="scope">
            <span class="task-id" :title="scope.row.task_id">{{ scope.row.task_id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="标题" prop="title" min-width="200">
          <template slot-scope="scope">
            <div :title="scope.row.title" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
              {{ scope.row.title }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status | distributionStatusType" size="small">
              {{ scope.row.status | distributionStatusText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分发进度" width="150">
          <template slot-scope="scope">
            <div v-if="scope.row.distributions && scope.row.distributions.length > 0">
              <el-progress
                :percentage="getDistributionProgress(scope.row)"
                :status="getStatusByProgress(scope.row)"
                :stroke-width="10"
              ></el-progress>
              <div style="font-size: 12px; color: #909399; margin-top: 5px;">
                {{ getSuccessCount(scope.row) }}/{{ scope.row.distributions.length }} 渠道
              </div>
            </div>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template slot-scope="scope">
            {{ scope.row.created_at | formatDate }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template slot-scope="scope">
            <el-button
              type="text"
              size="small"
              @click="viewTaskDetail(scope.row)"
            >
              详情
            </el-button>
            <el-button
              v-if="scope.row.status === 'pending' || scope.row.status === 'draft'"
              type="text"
              size="small"
              @click="executeTask(scope.row)"
            >
              执行
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="empty-state" v-else>
        <i class="el-icon-folder-opened empty-icon"></i>
        <div class="empty-text">暂无分发任务</div>
      </div>

      <div class="pagination-container" v-if="taskTotal > 0">
        <el-pagination
          @size-change="handleTaskSizeChange"
          @current-change="handleTaskCurrentChange"
          :current-page="taskPagination.page"
          :page-sizes="[10, 20, 50]"
          :page-size="taskPagination.limit"
          layout="total, sizes, prev, pager, next, jumper"
          :total="taskTotal"
        >
        </el-pagination>
      </div>
    </el-card>

    <el-dialog
      title="添加分发渠道"
      :visible.sync="channelDialogVisible"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form
        :model="channelForm"
        :rules="channelRules"
        ref="channelFormRef"
        label-width="100px"
      >
        <el-form-item label="渠道类型" prop="channel_type">
          <el-select v-model="channelForm.channel_type" placeholder="选择渠道类型" style="width: 100%;">
            <el-option label="微信公众号" value="weixin">
              <span><i class="el-icon-chat-dot-round" style="margin-right: 5px;"></i>微信公众号</span>
            </el-option>
            <el-option label="微博" value="weibo">
              <span><i class="el-icon-postcard" style="margin-right: 5px;"></i>微博</span>
            </el-option>
            <el-option label="抖音" value="douyin">
              <span><i class="el-icon-video-camera" style="margin-right: 5px;"></i>抖音</span>
            </el-option>
            <el-option label="哔哩哔哩" value="bilibili">
              <span><i class="el-icon-video-play" style="margin-right: 5px;"></i>哔哩哔哩</span>
            </el-option>
            <el-option label="西瓜视频" value="xigua">
              <span><i class="el-icon-video-camera-solid" style="margin-right: 5px;"></i>西瓜视频</span>
            </el-option>
            <el-option label="自定义渠道" value="custom">
              <span><i class="el-icon-setting" style="margin-right: 5px;"></i>自定义渠道</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="渠道名称" prop="channel_name">
          <el-input
            v-model="channelForm.channel_name"
            placeholder="请输入渠道名称，如：我的抖音账号"
          ></el-input>
        </el-form-item>
        <el-divider>配置参数</el-divider>
        <el-form-item label="App ID" prop="app_id" v-if="channelForm.channel_type === 'weixin'">
          <el-input v-model="channelForm.config.app_id" placeholder="微信公众号 AppID"></el-input>
        </el-form-item>
        <el-form-item label="App Secret" prop="app_secret" v-if="channelForm.channel_type === 'weixin'">
          <el-input v-model="channelForm.config.app_secret" placeholder="微信公众号 AppSecret" show-password></el-input>
        </el-form-item>
        <el-form-item label="Access Token" prop="access_token" v-if="['weibo', 'douyin', 'bilibili', 'xigua'].includes(channelForm.channel_type)">
          <el-input
            v-model="channelForm.config.access_token"
            placeholder="平台授权 Access Token"
            show-password
          ></el-input>
        </el-form-item>
        <el-form-item label="API地址" prop="api_url" v-if="channelForm.channel_type === 'custom'">
          <el-input v-model="channelForm.config.api_url" placeholder="自定义渠道 API 地址"></el-input>
        </el-form-item>
        <el-form-item label="API密钥" prop="api_key" v-if="channelForm.channel_type === 'custom'">
          <el-input v-model="channelForm.config.api_key" placeholder="API 密钥" show-password></el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="channelForm.config.remark"
            type="textarea"
            :rows="2"
            placeholder="备注信息（可选）"
          ></el-input>
        </el-form-item>
      </el-form>
      <span slot="footer" class="dialog-footer">
        <el-button @click="channelDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="saveChannel">确 定</el-button>
      </span>
    </el-dialog>

    <el-dialog
      title="任务详情"
      :visible.sync="taskDetailDialogVisible"
      width="700px"
      top="5vh"
    >
      <div v-if="currentTask">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="任务ID">
            {{ currentTask.task_id }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="currentTask.status | distributionStatusType" size="small">
              {{ currentTask.status | distributionStatusText }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="标题" :span="2">
            {{ currentTask.title }}
          </el-descriptions-item>
          <el-descriptions-item label="描述" :span="2" v-if="currentTask.description">
            {{ currentTask.description }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">
            {{ currentTask.created_at | formatDate }}
          </el-descriptions-item>
          <el-descriptions-item label="完成时间" v-if="currentTask.completed_at">
            {{ currentTask.completed_at | formatDate }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider>分发渠道状态</el-divider>

        <el-table :data="currentTask.distributions || []" style="width: 100%">
          <el-table-column label="渠道ID" prop="channel_config_id" width="180">
            <template slot-scope="scope">
              <span :title="scope.row.channel_config_id">{{ scope.row.channel_config_id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template slot-scope="scope">
              <el-tag :type="scope.row.status | distributionStatusType" size="small">
                {{ scope.row.status | distributionStatusText }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="外部ID" prop="external_id" v-if="currentTask.distributions?.some(d => d.external_id)">
            <template slot-scope="scope">
              {{ scope.row.external_id || '-' }}
            </template>
          </el-table-column>
          <el-table-column label="错误信息" v-if="currentTask.distributions?.some(d => d.error_message)">
            <template slot-scope="scope">
              <span v-if="scope.row.error_message" style="color: #F56C6C;">
                {{ scope.row.error_message }}
              </span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="推送时间" width="160">
            <template slot-scope="scope">
              {{ scope.row.pushed_at ? (scope.row.pushed_at | formatDate) : '-' }}
            </template>
          </el-table-column>
        </el-table>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="taskDetailDialogVisible = false">关 闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import {
  createChannel,
  listChannels,
  updateChannel,
  deleteChannel,
  listDistributionTasks,
  executeDistribution,
  batchDistribute
} from '@/api/distribution'
import { listMedia } from '@/api/media'

export default {
  name: 'Distribution',
  data() {
    const validateChannelType = (rule, value, callback) => {
      if (!value) {
        callback(new Error('请选择渠道类型'))
      } else {
        callback()
      }
    }
    const validateChannelName = (rule, value, callback) => {
      if (!value || !value.trim()) {
        callback(new Error('请输入渠道名称'))
      } else if (value.trim().length > 50) {
        callback(new Error('渠道名称不能超过50个字符'))
      } else {
        callback()
      }
    }

    return {
      channels: [],
      selectedChannels: [],
      selectedMediaList: [],
      distributionTasks: [],
      taskTotal: 0,
      taskPagination: {
        page: 1,
        limit: 10
      },
      isPushing: false,
      channelDialogVisible: false,
      isEditingChannel: false,
      currentChannel: null,
      channelForm: {
        channel_type: '',
        channel_name: '',
        config: {
          app_id: '',
          app_secret: '',
          access_token: '',
          api_url: '',
          api_key: '',
          remark: ''
        }
      },
      channelRules: {
        channel_type: [
          { required: true, validator: validateChannelType, trigger: 'change' }
        ],
        channel_name: [
          { required: true, validator: validateChannelName, trigger: 'blur' }
        ]
      },
      distributionForm: {
        title: '',
        description: '',
        tagsInput: ''
      },
      taskDetailDialogVisible: false,
      currentTask: null
    }
  },
  mounted() {
    this.fetchChannels()
    this.fetchDistributionTasks()
    this.handleRouteParams()
  },
  methods: {
    async handleRouteParams() {
      const mediaIds = this.$route.query.media_ids
      if (mediaIds) {
        const ids = mediaIds.split(',').filter(Boolean)
        if (ids.length > 0) {
          try {
            const res = await listMedia({
              media_ids: ids.join(','),
              limit: 100
            })
            if (res.code === 200 && res.data.media) {
              this.selectedMediaList = res.data.media.filter(m => 
                m.status === 'approved'
              )
              if (this.selectedMediaList.length < ids.length) {
                this.$message.warning('部分媒体状态不支持分发（仅已通过的媒体可分发）')
              }
            }
          } catch (error) {
            console.error('Failed to fetch selected media:', error)
          }
        }
      }
    },
    async fetchChannels() {
      try {
        const res = await listChannels()
        if (res.code === 200) {
          this.channels = res.data.channels || []
        }
      } catch (error) {
        console.error('Failed to fetch channels:', error)
        this.$message.error('获取渠道列表失败')
      }
    },
    async fetchDistributionTasks() {
      try {
        const res = await listDistributionTasks(this.taskPagination)
        if (res.code === 200) {
          this.distributionTasks = res.data.tasks || []
          this.taskTotal = res.data.total || 0
        }
      } catch (error) {
        console.error('Failed to fetch distribution tasks:', error)
      }
    },
    async fetchApprovedMedia() {
      try {
        const res = await listMedia({
          status: 'approved',
          limit: 50
        })
        if (res.code === 200) {
          this.approvedMediaList = res.data.media || []
        }
      } catch (error) {
        console.error('Failed to fetch approved media:', error)
      }
    },
    getChannelIcon(channelType) {
      const iconMap = {
        'weixin': 'el-icon-chat-dot-round',
        'weibo': 'el-icon-postcard',
        'douyin': 'el-icon-video-camera',
        'bilibili': 'el-icon-video-play',
        'xigua': 'el-icon-video-camera-solid',
        'custom': 'el-icon-setting'
      }
      return iconMap[channelType] || 'el-icon-setting'
    },
    showChannelDialog() {
      this.isEditingChannel = false
      this.currentChannel = null
      this.channelForm = {
        channel_type: '',
        channel_name: '',
        config: {
          app_id: '',
          app_secret: '',
          access_token: '',
          api_url: '',
          api_key: '',
          remark: ''
        }
      }
      this.channelDialogVisible = true
      this.$nextTick(() => {
        this.$refs.channelFormRef?.clearValidate()
      })
    },
    editChannel(channel) {
      this.isEditingChannel = true
      this.currentChannel = channel
      this.channelForm = {
        channel_type: channel.channel_type,
        channel_name: channel.channel_name,
        config: {
          ...channel.config,
          app_id: channel.config?.app_id || '',
          app_secret: channel.config?.app_secret || '',
          access_token: channel.config?.access_token || '',
          api_url: channel.config?.api_url || '',
          api_key: channel.config?.api_key || '',
          remark: channel.config?.remark || ''
        }
      }
      this.channelDialogVisible = true
    },
    async toggleChannelStatus(channel) {
      try {
        const res = await updateChannel(channel.config_id, {
          is_active: channel.is_active
        })
        if (res.code === 200) {
          this.$message.success(channel.is_active ? '已启用' : '已禁用')
        } else {
          channel.is_active = !channel.is_active
          this.$message.error('操作失败')
        }
      } catch (error) {
        channel.is_active = !channel.is_active
        console.error('Failed to toggle channel status:', error)
        this.$message.error('操作失败')
      }
    },
    async deleteChannel(channel) {
      try {
        await this.$confirm('确定要删除该渠道吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        const res = await deleteChannel(channel.config_id)
        if (res.code === 200) {
          this.$message.success('删除成功')
          this.fetchChannels()
        }
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to delete channel:', error)
          this.$message.error('删除失败')
        }
      }
    },
    async saveChannel() {
      this.$refs.channelFormRef.validate(async (valid) => {
        if (!valid) return

        try {
          let res
          if (this.isEditingChannel && this.currentChannel) {
            res = await updateChannel(this.currentChannel.config_id, {
              channel_type: this.channelForm.channel_type,
              channel_name: this.channelForm.channel_name,
              config: this.channelForm.config
            })
          } else {
            res = await createChannel({
              channel_type: this.channelForm.channel_type,
              channel_name: this.channelForm.channel_name,
              config: this.channelForm.config
            })
          }

          if (res.code === 200) {
            this.$message.success(this.isEditingChannel ? '更新成功' : '创建成功')
            this.channelDialogVisible = false
            this.fetchChannels()
          }
        } catch (error) {
          console.error('Failed to save channel:', error)
          this.$message.error('保存失败')
        }
      })
    },
    removeMedia(mediaId) {
      const index = this.selectedMediaList.findIndex(m => m.media_id === mediaId)
      if (index > -1) {
        this.selectedMediaList.splice(index, 1)
      }
    },
    clearSelection() {
      this.selectedMediaList = []
      this.selectedChannels = []
      this.distributionForm = {
        title: '',
        description: '',
        tagsInput: ''
      }
    },
    async startDistribution() {
      if (this.selectedMediaList.length === 0) {
        this.$message.warning('请先选择要分发的媒体')
        return
      }
      if (this.selectedChannels.length === 0) {
        this.$message.warning('请选择目标渠道')
        return
      }

      try {
        const tags = this.distributionForm.tagsInput
          ? this.distributionForm.tagsInput.split(',').map(t => t.trim()).filter(Boolean)
          : []

        this.isPushing = true
        const res = await batchDistribute({
          media_ids: this.selectedMediaList.map(m => m.media_id),
          channel_config_ids: this.selectedChannels,
          title: this.distributionForm.title,
          description: this.distributionForm.description,
          tags: tags
        })

        if (res.code === 200) {
          this.$message.success('分发任务已创建')
          this.clearSelection()
          this.fetchDistributionTasks()
        }
      } catch (error) {
        console.error('Failed to start distribution:', error)
        this.$message.error('分发失败')
      } finally {
        this.isPushing = false
      }
    },
    viewTaskDetail(task) {
      this.currentTask = { ...task }
      this.taskDetailDialogVisible = true
    },
    async executeTask(task) {
      try {
        await this.$confirm('确定要执行该分发任务吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        const res = await executeDistribution(task.task_id)
        if (res.code === 200) {
          this.$message.success('已开始执行')
          this.fetchDistributionTasks()
        }
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to execute task:', error)
          this.$message.error('执行失败')
        }
      }
    },
    getDistributionProgress(task) {
      if (!task.distributions || task.distributions.length === 0) return 0
      const completed = task.distributions.filter(d => 
        d.status === 'success' || d.status === 'completed'
      ).length
      return Math.round((completed / task.distributions.length) * 100)
    },
    getSuccessCount(task) {
      if (!task.distributions) return 0
      return task.distributions.filter(d => 
        d.status === 'success' || d.status === 'completed'
      ).length
    },
    getStatusByProgress(task) {
      if (!task.distributions || task.distributions.length === 0) return null
      const hasFailed = task.distributions.some(d => d.status === 'failed')
      const allSuccess = task.distributions.every(d => 
        d.status === 'success' || d.status === 'completed'
      )
      if (allSuccess) return 'success'
      if (hasFailed) return 'exception'
      return null
    },
    refreshTasks() {
      this.fetchDistributionTasks()
    },
    handleTaskSizeChange(val) {
      this.taskPagination.limit = val
      this.fetchDistributionTasks()
    },
    handleTaskCurrentChange(val) {
      this.taskPagination.page = val
      this.fetchDistributionTasks()
    }
  }
}
</script>

<style lang="scss" scoped>
.distribution-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .channel-list {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 15px;

    .channel-card {
      border: 1px solid #EBEEF5;
      border-radius: 8px;
      padding: 16px;
      transition: all 0.3s;

      &:hover {
        box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
        border-color: #409EFF;
      }

      .channel-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 10px;

        .channel-type {
          display: flex;
          align-items: center;

          .type-icon {
            font-size: 32px;
            color: #409EFF;
            margin-right: 12px;
          }

          .type-info {
            .channel-name {
              font-size: 15px;
              font-weight: 600;
              color: #303133;
              margin-bottom: 4px;
            }

            .channel-platform {
              font-size: 12px;
              color: #909399;
            }
          }
        }
      }

      .channel-status {
        display: flex;
        justify-content: space-between;
        align-items: center;
        margin-bottom: 10px;

        .created-time {
          font-size: 12px;
          color: #909399;
        }
      }

      .channel-actions {
        padding-top: 10px;
        border-top: 1px solid #EBEEF5;

        .danger {
          color: #F56C6C !important;
        }
      }
    }
  }

  .selected-media-list {
    max-height: 300px;
    overflow-y: auto;

    .selected-media-item {
      display: flex;
      align-items: center;
      padding: 8px;
      border: 1px solid #EBEEF5;
      border-radius: 4px;
      margin-bottom: 8px;

      & > i {
        font-size: 18px;
        color: #409EFF;
        margin-right: 8px;
      }

      .media-name {
        flex: 1;
        font-size: 13px;
        color: #303133;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-right: 8px;
      }

      .remove-btn {
        padding: 0;
        margin-left: 5px;
      }
    }
  }

  .task-id {
    font-family: 'Courier New', monospace;
    font-size: 12px;
    color: #606266;
  }

  .empty-state {
    text-align: center;
    padding: 60px 0;
    color: #909399;

    .empty-icon {
      font-size: 48px;
      margin-bottom: 16px;
      color: #C0C4CC;
    }

    .empty-text {
      font-size: 14px;
    }
  }

  .pagination-container {
    margin-top: 20px;
    text-align: right;
  }

  .table-thumbnail {
    width: 50px;
    height: 50px;
    object-fit: cover;
    border-radius: 4px;
    border: 1px solid #EBEEF5;
  }

  .success {
    color: #67C23A !important;
  }

  .danger {
    color: #F56C6C !important;
  }
}
</style>
