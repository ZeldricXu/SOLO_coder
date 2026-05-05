<template>
  <div class="media-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>媒体库</span>
          <div>
            <el-button
              v-if="selectedMedia.length > 0"
              type="danger"
              size="small"
              @click="batchDelete"
            >
              批量删除 ({{ selectedMedia.length }})
            </el-button>
            <el-button
              v-if="selectedMedia.length > 0"
              type="primary"
              size="small"
              @click="goToDistribution"
              style="margin-left: 10px"
            >
              批量分发
            </el-button>
            <el-radio-group
              v-model="viewMode"
              size="small"
              style="margin-left: 10px"
            >
              <el-radio-button label="grid">
                <i class="el-icon-s-grid"></i>
              </el-radio-button>
              <el-radio-button label="list">
                <i class="el-icon-s-operation"></i>
              </el-radio-button>
            </el-radio-group>
          </div>
        </div>
      </template>

      <el-form :inline="true" class="search-form">
        <el-form-item label="搜索">
          <el-input
            v-model="searchParams.query"
            placeholder="文件名或标签"
            clearable
            @keyup.enter.native="searchMedia"
          ></el-input>
        </el-form-item>
        <el-form-item label="类型">
          <el-select
            v-model="searchParams.fileType"
            placeholder="全部类型"
            clearable
            @change="searchMedia"
          >
            <el-option label="图片" value="image"></el-option>
            <el-option label="视频" value="video"></el-option>
            <el-option label="音频" value="audio"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="searchParams.status"
            placeholder="全部状态"
            clearable
            @change="searchMedia"
          >
            <el-option label="待审核" value="pending_review"></el-option>
            <el-option label="已通过" value="approved"></el-option>
            <el-option label="已拒绝" value="rejected"></el-option>
            <el-option label="处理中" value="processing"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="searchMedia">搜索</el-button>
          <el-button @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="media-grid" v-if="viewMode === 'grid' && mediaList.length > 0">
        <div
          class="media-card"
          v-for="media in mediaList"
          :key="media.media_id"
          :class="{ selected: isSelected(media.media_id), 'processing': media.status === 'processing' }"
          @click="toggleSelection(media.media_id)"
        >
          <div class="media-thumbnail">
            <img
              :src="getThumbnailSrc(media)"
              :alt="media.filename"
              :class="{ 'lazy-loading': isLazyLoading(media.media_id) }"
              @error="handleImageError($event, media)"
              @load="handleImageLoad($event, media)"
              ref="thumbnailImages"
            />
            <div 
              class="processing-overlay" 
              v-if="media.status === 'processing'"
            >
              <div class="processing-content">
                <i class="el-icon-loading processing-icon"></i>
                <div class="processing-text">
                  处理中...
                </div>
                <el-progress 
                  :percentage="getProcessingProgress(media.media_id)"
                  :stroke-width="6"
                  :show-text="false"
                  style="width: 100px; margin-top: 8px;"
                ></el-progress>
              </div>
            </div>
            <div class="media-overlay" @click.stop>
              <div class="overlay-actions">
                <el-button
                  type="primary"
                  size="small"
                  icon="el-icon-view"
                  @click="viewMedia(media)"
                >
                  预览
                </el-button>
                <el-button
                  type="danger"
                  size="small"
                  icon="el-icon-delete"
                  @click="deleteMedia(media)"
                >
                  删除
                </el-button>
              </div>
            </div>
            <div class="media-status" v-if="media.status !== 'approved'">
              <el-tag :type="media.status | statusType" size="mini">
                {{ media.status | statusText }}
              </el-tag>
            </div>
            <div class="media-duration" v-if="media.metadata?.duration > 0">
              {{ media.metadata.duration | formatDuration }}
            </div>
            <el-checkbox
              class="select-checkbox"
              :value="isSelected(media.media_id)"
              @change="toggleSelection(media.media_id)"
              @click.stop
            ></el-checkbox>
          </div>
          <div class="media-info">
            <div class="media-name" :title="media.filename">{{ media.filename }}</div>
            <div class="media-meta">
              <span><i :class="media.file_type | fileTypeIcon"></i> {{ media.file_type | fileTypeText }}</span>
              <span>{{ media.file_size | formatFileSize }}</span>
            </div>
          </div>
        </div>
      </div>

      <el-table
        v-else-if="viewMode === 'list' && mediaList.length > 0"
        :data="mediaList"
        style="width: 100%"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="55"></el-table-column>
        <el-table-column label="缩略图" width="80">
          <template slot-scope="scope">
            <div class="table-thumbnail-container">
              <img
                class="table-thumbnail"
                :src="getThumbnailSrc(scope.row)"
                :alt="scope.row.filename"
                @error="handleImageError($event, scope.row)"
              />
              <div 
                class="table-processing-badge"
                v-if="scope.row.status === 'processing'"
              >
                <i class="el-icon-loading"></i>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="文件名" prop="filename" min-width="200">
          <template slot-scope="scope">
            <div :title="scope.row.filename" style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
              {{ scope.row.filename }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template slot-scope="scope">
            <i :class="scope.row.file_type | fileTypeIcon"></i>
            {{ scope.row.file_type | fileTypeText }}
          </template>
        </el-table-column>
        <el-table-column label="大小" width="120">
          <template slot-scope="scope">
            {{ scope.row.file_size | formatFileSize }}
          </template>
        </el-table-column>
        <el-table-column label="分辨率" width="120" v-if="viewMode === 'list'">
          <template slot-scope="scope">
            <span v-if="scope.row.metadata?.width && scope.row.metadata?.height">
              {{ scope.row.metadata.width }} x {{ scope.row.metadata.height }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template slot-scope="scope">
            <span v-if="scope.row.metadata?.duration > 0">
              {{ scope.row.metadata.duration | formatDuration }}
            </span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status | statusType" size="small">
              {{ scope.row.status | statusText }}
              <i 
                v-if="scope.row.status === 'processing'" 
                class="el-icon-loading"
                style="margin-left: 4px;"
              ></i>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="160">
          <template slot-scope="scope">
            {{ scope.row.created_at | formatDate }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template slot-scope="scope">
            <el-button
              type="text"
              size="small"
              @click="viewMedia(scope.row)"
            >
              预览
            </el-button>
            <el-button
              type="text"
              size="small"
              class="danger"
              @click="deleteMedia(scope.row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="empty-state" v-else>
        <i class="el-icon-folder-opened empty-icon"></i>
        <div class="empty-text">暂无媒体文件</div>
      </div>

      <div class="pagination-container" v-if="total > 0">
        <el-pagination
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :current-page="searchParams.page"
          :page-sizes="[10, 20, 50, 100]"
          :page-size="searchParams.limit"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
        >
        </el-pagination>
      </div>
    </el-card>

    <el-dialog
      title="媒体详情"
      :visible.sync="detailDialogVisible"
      width="800px"
    >
      <div v-if="currentMedia" class="media-detail">
        <div class="preview-area">
          <video
            v-if="currentMedia.file_type === 'video'"
            :src="currentMedia.presigned_url"
            controls
            style="width: 100%; max-height: 400px;"
          ></video>
          <audio
            v-else-if="currentMedia.file_type === 'audio'"
            :src="currentMedia.presigned_url"
            controls
            style="width: 100%;"
          ></audio>
          <img
            v-else-if="currentMedia.file_type === 'image'"
            :src="currentMedia.presigned_url || currentMedia.thumbnail_url"
            :alt="currentMedia.filename"
            style="max-width: 100%; max-height: 400px; display: block; margin: 0 auto;"
          />
          <div v-else class="no-preview">
            <i :class="currentMedia.file_type | fileTypeIcon" style="font-size: 64px; color: #c0c4cc;"></i>
            <p>该文件类型暂不支持在线预览</p>
          </div>
        </div>

        <el-divider></el-divider>

        <el-descriptions :column="2" border>
          <el-descriptions-item label="文件名">
            {{ currentMedia.filename }}
          </el-descriptions-item>
          <el-descriptions-item label="文件类型">
            <el-tag :type="currentMedia.file_type === 'video' ? 'primary' : currentMedia.file_type === 'image' ? 'success' : 'info'">
              {{ currentMedia.file_type | fileTypeText }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">
            {{ currentMedia.file_size | formatFileSize }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="currentMedia.status | statusType">
              {{ currentMedia.status | statusText }}
              <i 
                v-if="currentMedia.status === 'processing'" 
                class="el-icon-loading"
                style="margin-left: 4px;"
              ></i>
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="分辨率" v-if="currentMedia.metadata?.width">
            {{ currentMedia.metadata.width }} x {{ currentMedia.metadata.height }}
          </el-descriptions-item>
          <el-descriptions-item label="时长" v-if="currentMedia.metadata?.duration > 0">
            {{ currentMedia.metadata.duration | formatDuration }}
          </el-descriptions-item>
          <el-descriptions-item label="码率" v-if="currentMedia.metadata?.bitrate > 0">
            {{ Math.round(currentMedia.metadata.bitrate / 1000) }} kbps
          </el-descriptions-item>
          <el-descriptions-item label="标签">
            <el-tag
              v-for="tag in currentMedia.tags"
              :key="tag"
              size="small"
              style="margin-right: 5px;"
            >
              {{ tag }}
            </el-tag>
            <span v-if="!currentMedia.tags || currentMedia.tags.length === 0">暂无标签</span>
          </el-descriptions-item>
          <el-descriptions-item label="上传时间">
            {{ currentMedia.created_at | formatDate }}
          </el-descriptions-item>
          <el-descriptions-item label="审核时间" v-if="currentMedia.reviewed_at">
            {{ currentMedia.reviewed_at | formatDate }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="detailDialogVisible = false">关 闭</el-button>
        <el-button
          v-if="currentMedia?.status === 'approved'"
          type="primary"
          @click="distributeMedia"
        >
          分发
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { getMediaList, deleteMedia, getMediaById, getPresignedUrl, batchDelete } from '@/api/media'
import { thumbnailLoader, PLACEHOLDER_TYPES } from '@/services/thumbnailLoader'
import { websocketService, initializeWebSocket } from '@/services/websocketService'

export default {
  name: 'Media',
  data() {
    return {
      viewMode: 'grid',
      mediaList: [],
      selectedMedia: [],
      searchParams: {
        query: '',
        fileType: '',
        status: '',
        page: 1,
        limit: 20
      },
      total: 0,
      detailDialogVisible: false,
      currentMedia: null,
      processingProgress: {},
      lazyLoadingStatus: {},
      subscribedMediaIds: new Set()
    }
  },
  async mounted() {
    this.initializeServices()
    this.fetchMediaList()
  },
  beforeDestroy() {
    this.cleanupSubscriptions()
  },
  methods: {
    async initializeServices() {
      try {
        await initializeWebSocket()
        console.log('[Media] WebSocket service initialized')
        
        websocketService.on('media:progress', (data) => {
          this.handleMediaProgress(data)
        })
        
        websocketService.on('media:completed', (data) => {
          this.handleMediaCompleted(data)
        })
        
        websocketService.on('media:failed', (data) => {
          this.handleMediaFailed(data)
        })
      } catch (error) {
        console.warn('[Media] WebSocket initialization failed:', error)
      }
    },
    cleanupSubscriptions() {
      this.subscribedMediaIds.forEach(mediaId => {
        websocketService.unsubscribeFromMedia(mediaId)
      })
      this.subscribedMediaIds.clear()
    },
    subscribeToMedia(mediaId) {
      if (!this.subscribedMediaIds.has(mediaId)) {
        websocketService.subscribeToMedia(mediaId)
        this.subscribedMediaIds.add(mediaId)
      }
    },
    handleMediaProgress(data) {
      const { mediaId, progress, status } = data
      this.$set(this.processingProgress, mediaId, progress)
      
      const media = this.mediaList.find(m => m.media_id === mediaId)
      if (media) {
        media.status = 'processing'
      }
    },
    handleMediaCompleted(data) {
      const { mediaId, metadata, thumbnailUrl, status } = data
      
      const mediaIndex = this.mediaList.findIndex(m => m.media_id === mediaId)
      if (mediaIndex > -1) {
        const media = this.mediaList[mediaIndex]
        media.metadata = metadata || media.metadata
        media.thumbnail_url = thumbnailUrl || media.thumbnail_url
        media.status = status || 'pending_review'
        
        this.$set(this.mediaList, mediaIndex, { ...media })
      }
      
      this.$delete(this.processingProgress, mediaId)
      websocketService.unsubscribeFromMedia(mediaId)
      this.subscribedMediaIds.delete(mediaId)
      
      this.$message.success('媒体处理完成: ' + (this.mediaList[mediaIndex]?.filename || mediaId))
    },
    handleMediaFailed(data) {
      const { mediaId, error } = data
      
      const mediaIndex = this.mediaList.findIndex(m => m.media_id === mediaId)
      if (mediaIndex > -1) {
        const media = this.mediaList[mediaIndex]
        media.status = 'failed'
        this.$set(this.mediaList, mediaIndex, { ...media })
      }
      
      this.$delete(this.processingProgress, mediaId)
      websocketService.unsubscribeFromMedia(mediaId)
      this.subscribedMediaIds.delete(mediaId)
      
      this.$message.error('媒体处理失败: ' + (error || '未知错误'))
    },
    getProcessingProgress(mediaId) {
      return this.processingProgress[mediaId] || 0
    },
    isLazyLoading(mediaId) {
      return this.lazyLoadingStatus[mediaId] === 'loading'
    },
    getThumbnailSrc(media) {
      if (media.thumbnail_url) {
        return `/api/v1/storage/${encodeURIComponent(media.thumbnail_url)}`
      }
      
      if (media.status === 'processing') {
        this.subscribeToMedia(media.media_id)
      }
      
      return thumbnailLoader.getFileTypePlaceholder(media.file_type)
    },
    async fetchMediaList() {
      try {
        const res = await getMediaList(this.searchParams)
        if (res.code === 200) {
          this.mediaList = res.data.media || []
          this.total = res.data.pagination?.total || 0
          
          this.mediaList.forEach(media => {
            if (media.status === 'processing') {
              this.subscribeToMedia(media.media_id)
            }
          })
          
          this.preloadVisibleThumbnails()
        }
      } catch (error) {
        console.error('Failed to fetch media list:', error)
      }
    },
    preloadVisibleThumbnails() {
      const visibleMedia = this.mediaList.slice(0, this.searchParams.limit * 2)
      const mediaToPreload = visibleMedia.filter(m => m.thumbnail_url)
      
      if (mediaToPreload.length > 0) {
        thumbnailLoader.preloadMedia(mediaToPreload, { priority: 'high' })
      }
    },
    searchMedia() {
      this.searchParams.page = 1
      this.fetchMediaList()
    },
    resetSearch() {
      this.searchParams = {
        query: '',
        fileType: '',
        status: '',
        page: 1,
        limit: this.searchParams.limit
      }
      this.fetchMediaList()
    },
    handleSizeChange(val) {
      this.searchParams.limit = val
      this.fetchMediaList()
    },
    handleCurrentChange(val) {
      this.searchParams.page = val
      this.fetchMediaList()
    },
    handleSelectionChange(val) {
      this.selectedMedia = val.map(item => item.media_id)
    },
    isSelected(mediaId) {
      return this.selectedMedia.includes(mediaId)
    },
    toggleSelection(mediaId) {
      const index = this.selectedMedia.indexOf(mediaId)
      if (index > -1) {
        this.selectedMedia.splice(index, 1)
      } else {
        this.selectedMedia.push(mediaId)
      }
    },
    handleImageError(e, media) {
      e.target.src = thumbnailLoader.getFileTypePlaceholder(media?.file_type || 'image')
    },
    handleImageLoad(e, media) {
      this.$set(this.lazyLoadingStatus, media.media_id, 'loaded')
    },
    async viewMedia(media) {
      try {
        const res = await getMediaById(media.media_id)
        if (res.code === 200) {
          this.currentMedia = res.data
          
          if (this.currentMedia.storage_path) {
            try {
              const urlRes = await getPresignedUrl(media.media_id)
              if (urlRes.code === 200) {
                this.currentMedia.presigned_url = urlRes.data.presigned_url
              }
            } catch (urlError) {
              console.error('Failed to get presigned URL:', urlError)
            }
          }
          
          this.detailDialogVisible = true
        }
      } catch (error) {
        console.error('Failed to get media detail:', error)
        this.$message.error('获取媒体详情失败')
      }
    },
    async deleteMedia(media) {
      try {
        await this.$confirm(`确定要删除文件 "${media.filename" 吗？`, '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        await deleteMedia(media.media_id)
        this.$message.success('删除成功')
        
        websocketService.unsubscribeFromMedia(media.media_id)
        this.subscribedMediaIds.delete(media.media_id)
        
        this.fetchMediaList()
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to delete media:', error)
          this.$message.error('删除失败')
        }
      }
    },
    async batchDelete() {
      if (this.selectedMedia.length === 0) {
        this.$message.warning('请先选择要删除的文件')
        return
      }

      try {
        await this.$confirm(`确定要删除选中的 ${this.selectedMedia.length} 个文件吗？`, '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })

        await batchDelete({ media_ids: this.selectedMedia })
        this.$message.success('批量删除成功')
        
        this.selectedMedia.forEach(mediaId => {
          websocketService.unsubscribeFromMedia(mediaId)
          this.subscribedMediaIds.delete(mediaId)
        })
        
        this.selectedMedia = []
        this.fetchMediaList()
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to batch delete:', error)
          this.$message.error('批量删除失败')
        }
      }
    },
    goToDistribution() {
      this.$router.push({
        path: '/distribution',
        query: { media_ids: this.selectedMedia.join(',') }
      })
    },
    distributeMedia() {
      this.detailDialogVisible = false
      this.$router.push({
        path: '/distribution',
        query: { media_ids: this.currentMedia.media_id }
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.media-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }

  .media-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
    gap: 20px;
  }

  .media-card {
    border: 1px solid #EBEEF5;
    border-radius: 8px;
    overflow: hidden;
    cursor: pointer;
    transition: all 0.3s;
    position: relative;

    &:hover {
      box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
    }

    &.selected {
      border-color: #409EFF;
      box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.2);
    }

    &.processing {
      .media-thumbnail {
        filter: brightness(0.9);
      }
    }

    .media-thumbnail {
      position: relative;
      width: 100%;
      height: 120px;
      background-color: #F5F7FA;
      overflow: hidden;

      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        transition: transform 0.3s;

        &.lazy-loading {
          opacity: 0.6;
        }
      }

      .processing-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 10;

        .processing-content {
          text-align: center;
          color: white;

          .processing-icon {
            font-size: 32px;
            animation: spin 1s linear infinite;
          }

          .processing-text {
            margin-top: 8px;
            font-size: 12px;
          }
        }
      }

      .media-overlay {
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: rgba(0, 0, 0, 0.6);
        display: flex;
        align-items: center;
        justify-content: center;
        opacity: 0;
        transition: opacity 0.3s;
        z-index: 20;

        &:hover {
          opacity: 1;
        }

        .overlay-actions {
          display: flex;
          gap: 10px;
        }
      }

      .media-status {
        position: absolute;
        top: 8px;
        left: 8px;
        z-index: 15;
      }

      .media-duration {
        position: absolute;
        bottom: 8px;
        right: 8px;
        background: rgba(0, 0, 0, 0.7);
        color: white;
        padding: 2px 6px;
        border-radius: 4px;
        font-size: 11px;
        z-index: 15;
      }

      .select-checkbox {
        position: absolute;
        top: 8px;
        right: 8px;
        z-index: 15;
      }
    }

    .media-info {
      padding: 12px;

      .media-name {
        font-size: 13px;
        color: #303133;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        margin-bottom: 8px;
      }

      .media-meta {
        display: flex;
        justify-content: space-between;
        font-size: 12px;
        color: #909399;

        span {
          display: flex;
          align-items: center;
          gap: 4px;
        }
      }
    }
  }

  .table-thumbnail-container {
    position: relative;
    width: 50px;
    height: 50px;

    .table-thumbnail {
      width: 50px;
      height: 50px;
      object-fit: cover;
      border-radius: 4px;
      border: 1px solid #EBEEF5;
    }

    .table-processing-badge {
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(255, 255, 255, 0.8);
      display: flex;
      align-items: center;
      justify-content: center;
      border-radius: 4px;

      i {
        font-size: 20px;
        animation: spin 1s linear infinite;
        color: #409EFF;
      }
    }
  }

  .media-detail {
    .preview-area {
      text-align: center;
      background-color: #f5f7fa;
      padding: 20px;
      border-radius: 4px;

      .no-preview {
        padding: 60px 0;
        color: #909399;
      }
    }
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
