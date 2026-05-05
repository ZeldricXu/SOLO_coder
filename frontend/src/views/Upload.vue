<template>
  <div class="upload-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>媒体上传</span>
          <el-button
            v-if="uploadSessions.length > 0"
            type="text"
            @click="clearCompleted"
          >
            清除已完成
          </el-button>
        </div>
      </template>

      <div
        class="upload-area"
        :class="{ 'is-dragover': isDragover }"
        @click="triggerUpload"
        @dragenter.prevent="handleDragEnter"
        @dragover.prevent="handleDragOver"
        @dragleave.prevent="handleDragLeave"
        @drop.prevent="handleDrop"
      >
        <input
          ref="fileInput"
          type="file"
          multiple
          :accept="allowedTypes"
          style="display: none"
          @change="handleFileSelect"
        />
        <i class="el-icon-upload upload-icon"></i>
        <div class="upload-text">将文件拖拽到此处，或点击上传</div>
        <div class="upload-hint">
          支持图片（JPG、PNG、GIF、WebP）、视频（MP4、WebM、MOV）、音频（MP3、WAV、FLAC）
        </div>
        <div class="upload-hint">
          单文件最大支持 {{ maxFileSize | formatFileSize }}
        </div>
      </div>

      <div class="upload-progress-list" v-if="uploadSessions.length > 0">
        <div
          class="upload-item"
          v-for="session in uploadSessions"
          :key="session.fileId"
        >
          <div class="item-header">
            <div class="file-info">
              <i :class="getFileIcon(session.fileType) + ' file-icon'"></i>
              <div class="file-details">
                <div class="filename" :title="session.filename">
                  {{ session.filename }}
                </div>
                <div class="file-size">
                  {{ session.fileSize | formatFileSize }}
                </div>
              </div>
            </div>
            <div class="item-actions">
              <el-button
                v-if="session.status === 'uploading' || session.status === 'pending'"
                type="text"
                size="small"
                @click="pauseUpload(session.fileId)"
                v-if="!session.isPaused"
              >
                <i class="el-icon-video-pause"></i>
                暂停
              </el-button>
              <el-button
                v-if="session.isPaused"
                type="text"
                size="small"
                @click="resumeUpload(session.fileId)"
              >
                <i class="el-icon-video-play"></i>
                继续
              </el-button>
              <el-button
                v-if="session.status === 'uploading' || session.status === 'pending' || session.isPaused"
                type="text"
                size="small"
                class="danger"
                @click="cancelUpload(session.fileId)"
              >
                <i class="el-icon-close"></i>
                取消
              </el-button>
              <el-button
                v-if="session.status === 'completed'"
                type="text"
                size="small"
                @click="goToMedia"
              >
                <i class="el-icon-view"></i>
                查看
              </el-button>
            </div>
          </div>
          
          <el-progress
            class="progress-bar"
            :percentage="session.progress"
            :status="getProgressStatus(session)"
            :stroke-width="8"
          ></el-progress>
          
          <div class="progress-info">
            <span>
              <el-tag 
                v-if="session.mediaStatus === 'processing'" 
                type="warning" 
                size="small"
              >
                <i class="el-icon-loading" style="animation: spin 1s linear infinite;"></i>
                处理中 {{ session.processingProgress || 0 }}%
              </el-tag>
              <el-tag 
                v-else-if="session.mediaStatus === 'pending_review'" 
                type="primary" 
                size="small"
              >
                <i class="el-icon-success"></i>
                处理完成
              </el-tag>
              <el-tag 
                v-else-if="session.mediaStatus === 'failed'" 
                type="danger" 
                size="small"
              >
                <i class="el-icon-error"></i>
                处理失败
              </el-tag>
              <el-tag v-else-if="session.status === 'uploading'" type="primary" size="small">
                上传中
              </el-tag>
              <el-tag v-else-if="session.status === 'completed'" type="success" size="small">
                已完成
              </el-tag>
              <el-tag v-else-if="session.status === 'failed'" type="danger" size="small">
                失败
              </el-tag>
              <el-tag v-else-if="session.isPaused" type="warning" size="small">
                已暂停
              </el-tag>
              <el-tag v-else type="info" size="small">
                等待中
              </el-tag>
            </span>
            <span v-if="session.status === 'uploading'">
              {{ session.uploadedChunks }} / {{ session.totalChunks }} 分片
              ({{ session.uploadedChunks * chunkSize | formatFileSize }} / {{ session.fileSize | formatFileSize }})
            </span>
          </div>
        </div>
      </div>

      <div class="empty-state" v-else>
        <i class="el-icon-upload2 empty-icon"></i>
        <div class="empty-text">暂无上传任务</div>
      </div>
    </el-card>

    <el-dialog
      title="上传配置"
      :visible.sync="configDialogVisible"
      width="400px"
    >
      <el-form label-width="120px">
        <el-form-item label="分片大小">
          <el-select v-model="uploadConfig.chunkSize" placeholder="选择分片大小">
            <el-option label="1 MB" :value="1 * 1024 * 1024"></el-option>
            <el-option label="2 MB" :value="2 * 1024 * 1024"></el-option>
            <el-option label="5 MB" :value="5 * 1024 * 1024"></el-option>
            <el-option label="10 MB" :value="10 * 1024 * 1024"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="并发上传数">
          <el-input-number
            v-model="uploadConfig.concurrentUploads"
            :min="1"
            :max="10"
          ></el-input-number>
        </el-form-item>
        <el-form-item label="重试次数">
          <el-input-number
            v-model="uploadConfig.retryCount"
            :min="1"
            :max="10"
          ></el-input-number>
        </el-form-item>
      </el-form>
      <span slot="footer" class="dialog-footer">
        <el-button @click="configDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="saveConfig">确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { mapGetters } from 'vuex'
import { ChunkUploader } from '@/api/upload'
import { getConfig } from '@/api/upload'
import { websocketService, initializeWebSocket } from '@/services/websocketService'

export default {
  name: 'Upload',
  data() {
    return {
      isDragover: false,
      uploaders: {},
      configDialogVisible: false,
      uploadConfig: {
        chunkSize: 5 * 1024 * 1024,
        concurrentUploads: 3,
        retryCount: 3
      },
      chunkSize: 5 * 1024 * 1024,
      maxFileSize: 5 * 1024 * 1024 * 1024,
      allowedTypes: '.jpg,.jpeg,.png,.gif,.webp,.bmp,.mp4,.webm,.ogg,.mov,.avi,.mp3,.wav,.flac',
      subscribedMediaIds: new Set()
    }
  },
  computed: {
    ...mapGetters(['uploadSessions'])
  },
  async mounted() {
    await this.loadConfig()
    await this.initializeWebSocket()
  },
  beforeDestroy() {
    this.cleanupSubscriptions()
  },
  methods: {
    async initializeWebSocket() {
      try {
        await initializeWebSocket()
        console.log('[Upload] WebSocket service initialized')
        
        websocketService.on('media:progress', (data) => {
          this.handleMediaProgress(data)
        })
        
        websocketService.on('media:completed', (data) => {
          this.handleMediaCompleted(data)
        })
        
        websocketService.on('media:failed', (data) => {
          this.handleMediaFailed(data)
        })
        
        websocketService.on('upload:progress', (data) => {
          this.handleUploadProgress(data)
        })
        
        websocketService.on('upload:completed', (data) => {
          this.handleUploadCompleted(data)
        })
        
      } catch (error) {
        console.warn('[Upload] WebSocket initialization failed:', error)
      }
    },
    cleanupSubscriptions() {
      this.subscribedMediaIds.forEach(mediaId => {
        websocketService.unsubscribeFromMedia(mediaId)
      })
      this.subscribedMediaIds.clear()
    },
    handleMediaProgress(data) {
      const { mediaId, progress } = data
      
      const session = this.uploadSessions.find(s => s.mediaId === mediaId)
      if (session) {
        this.$store.dispatch('updateUploadSession', {
          fileId: session.fileId,
          updates: {
            mediaStatus: 'processing',
            processingProgress: progress
          }
        })
      }
    },
    handleMediaCompleted(data) {
      const { mediaId, status } = data
      
      const session = this.uploadSessions.find(s => s.mediaId === mediaId)
      if (session) {
        this.$store.dispatch('updateUploadSession', {
          fileId: session.fileId,
          updates: {
            mediaStatus: status || 'pending_review',
            processingProgress: 100
          }
        })
        
        this.$message.success(`文件 ${session.filename} 处理完成`)
        this.$store.dispatch('fetchReviewStats')
      }
      
      websocketService.unsubscribeFromMedia(mediaId)
      this.subscribedMediaIds.delete(mediaId)
    },
    handleMediaFailed(data) {
      const { mediaId, error } = data
      
      const session = this.uploadSessions.find(s => s.mediaId === mediaId)
      if (session) {
        this.$store.dispatch('updateUploadSession', {
          fileId: session.fileId,
          updates: {
            mediaStatus: 'failed',
            processingProgress: 0
          }
        })
        
        this.$message.error(`文件 ${session.filename} 处理失败: ${error || '未知错误'}`)
      }
      
      websocketService.unsubscribeFromMedia(mediaId)
      this.subscribedMediaIds.delete(mediaId)
    },
    handleUploadProgress(data) {
      const { fileId, progress, uploadedChunks, totalChunks } = data
      
      const session = this.uploadSessions.find(s => s.fileId === fileId)
      if (session && session.status === 'uploading') {
        this.$store.dispatch('updateUploadSession', {
          fileId: fileId,
          updates: {
            uploadedChunks: uploadedChunks,
            progress: progress
          }
        })
      }
    },
    handleUploadCompleted(data) {
      const { fileId, mediaId, status } = data
      
      const session = this.uploadSessions.find(s => s.fileId === fileId)
      if (session) {
        this.$store.dispatch('updateUploadSession', {
          fileId: fileId,
          updates: {
            mediaId: mediaId,
            mediaStatus: 'processing',
            processingProgress: 0
          }
        })
        
        websocketService.subscribeToMedia(mediaId)
        this.subscribedMediaIds.add(mediaId)
      }
    },
    async loadConfig() {
      try {
        const res = await getConfig()
        if (res.code === 200) {
          this.chunkSize = res.data.chunk_size
          this.maxFileSize = res.data.max_file_size
        }
      } catch (error) {
        console.error('Failed to load config:', error)
      }
    },
    triggerUpload() {
      this.$refs.fileInput.click()
    },
    handleDragEnter() {
      this.isDragover = true
    },
    handleDragOver() {
      this.isDragover = true
    },
    handleDragLeave() {
      this.isDragover = false
    },
    handleDrop(e) {
      this.isDragover = false
      const files = e.dataTransfer.files
      if (files && files.length > 0) {
        this.processFiles(files)
      }
    },
    handleFileSelect(e) {
      const files = e.target.files
      if (files && files.length > 0) {
        this.processFiles(files)
      }
      this.$refs.fileInput.value = ''
    },
    processFiles(files) {
      for (let i = 0; i < files.length; i++) {
        const file = files[i]
        this.uploadFile(file)
      }
    },
    async uploadFile(file) {
      if (file.size > this.maxFileSize) {
        this.$message.error(`文件 ${file.name} 超过最大限制 ${this.maxFileSize | formatFileSize}`)
        return
      }

      const uploader = new ChunkUploader({
        chunkSize: this.uploadConfig.chunkSize,
        concurrentUploads: this.uploadConfig.concurrentUploads,
        retryCount: this.uploadConfig.retryCount
      })

      const fileId = `upload_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`
      const fileType = this.getFileType(file.type)

      this.$store.dispatch('addUploadSession', {
        fileId,
        filename: file.name,
        fileSize: file.size,
        fileType,
        totalChunks: Math.ceil(file.size / this.uploadConfig.chunkSize),
        uploadedChunks: 0,
        progress: 0,
        status: 'pending',
        isPaused: false
      })

      this.uploaders[fileId] = uploader

      uploader.onProgress = (progress) => {
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: {
            uploadedChunks: progress.uploadedChunks,
            totalChunks: progress.totalChunks,
            progress: progress.progress,
            status: 'uploading'
          }
        })
      }

      uploader.onComplete = (data) => {
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: {
            status: 'completed',
            progress: 100,
            mediaId: data.media_id
          }
        })
        this.$message.success(`文件 ${file.name} 上传成功`)
        this.$store.dispatch('fetchReviewStats')
      }

      uploader.onError = (error) => {
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: {
            status: 'failed'
          }
        })
        this.$message.error(`文件 ${file.name} 上传失败: ${error.message}`)
      }

      try {
        await uploader.prepareUpload(file)
        
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: {
            fileId: uploader.fileId,
            totalChunks: uploader.totalChunks,
            status: 'uploading'
          }
        })

        setTimeout(async () => {
          const result = await uploader.startUpload()
          
          if (result.success) {
            await uploader.complete()
          }
        }, 100)
        
      } catch (error) {
        console.error('Upload preparation failed:', error)
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: {
            status: 'failed'
          }
        })
        this.$message.error(`文件 ${file.name} 上传准备失败: ${error.message}`)
      }
    },
    getFileType(mimeType) {
      if (mimeType.startsWith('image/')) return 'image'
      if (mimeType.startsWith('video/')) return 'video'
      if (mimeType.startsWith('audio/')) return 'audio'
      return 'other'
    },
    getFileIcon(fileType) {
      const iconMap = {
        image: 'el-icon-picture',
        video: 'el-icon-video-camera',
        audio: 'el-icon-microphone',
        other: 'el-icon-document'
      }
      return iconMap[fileType] || 'el-icon-document'
    },
    getProgressStatus(session) {
      if (session.status === 'completed') return 'success'
      if (session.status === 'failed') return 'exception'
      return null
    },
    pauseUpload(fileId) {
      const uploader = this.uploaders[fileId]
      if (uploader) {
        uploader.pause()
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: { isPaused: true }
        })
      }
    },
    async resumeUpload(fileId) {
      const uploader = this.uploaders[fileId]
      if (uploader) {
        uploader.resume()
        this.$store.dispatch('updateUploadSession', {
          fileId,
          updates: { isPaused: false }
        })
        
        setTimeout(async () => {
          const result = await uploader.startUpload()
          if (result.success) {
            await uploader.complete()
          }
        }, 100)
      }
    },
    async cancelUpload(fileId) {
      try {
        await this.$confirm('确定要取消上传吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        const uploader = this.uploaders[fileId]
        if (uploader) {
          await uploader.cancel()
        }

        this.$store.dispatch('removeUploadSession', fileId)
        delete this.uploaders[fileId]
        
        this.$message.success('已取消上传')
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Cancel upload failed:', error)
        }
      }
    },
    clearCompleted() {
      this.$store.dispatch('clearCompletedUploads')
      
      const activeFileIds = this.$store.getters.uploadSessions.map(s => s.fileId)
      const allFileIds = Object.keys(this.uploaders)
      
      allFileIds.forEach(fileId => {
        if (!activeFileIds.includes(fileId)) {
          delete this.uploaders[fileId]
        }
      })
    },
    goToMedia() {
      this.$router.push('/media')
    },
    saveConfig() {
      this.configDialogVisible = false
      this.$message.success('配置已保存')
    }
  }
}
</script>

<style lang="scss" scoped>
.upload-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .upload-area {
    cursor: pointer;

    .upload-icon {
      font-size: 64px;
      color: #c0c4cc;
      margin-bottom: 20px;
    }

    .upload-text {
      font-size: 16px;
      color: #606266;
      margin-bottom: 10px;
    }

    .upload-hint {
      font-size: 12px;
      color: #909399;
      margin-bottom: 5px;
    }
  }

  .upload-progress-list {
    margin-top: 20px;

    .upload-item {
      padding: 15px;
      border: 1px solid #EBEEF5;
      border-radius: 8px;
      margin-bottom: 15px;

      &:hover {
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
      }

      .item-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        margin-bottom: 10px;

        .file-info {
          display: flex;
          align-items: center;

          .file-icon {
            font-size: 32px;
            color: #409EFF;
            margin-right: 12px;
          }

          .file-details {
            .filename {
              font-size: 14px;
              font-weight: 500;
              color: #303133;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
              max-width: 400px;
            }

            .file-size {
              font-size: 12px;
              color: #909399;
              margin-top: 2px;
            }
          }
        }

        .item-actions {
          display: flex;
          gap: 5px;

          .danger {
            color: #F56C6C !important;
          }
        }
      }

      .progress-bar {
        margin: 10px 0;
      }

      .progress-info {
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-size: 12px;
        color: #909399;

        .el-tag {
          margin-right: 5px;
        }
      }
    }
  }

  .danger {
    color: #F56C6C !important;
  }

  @keyframes spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
}
</style>
