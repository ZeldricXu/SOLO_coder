<template>
  <div class="review-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>内容审核</span>
          <el-tabs v-model="activeTab" type="card" @tab-click="handleTabClick">
            <el-tab-pane label="待审核" name="pending">
              <span class="tab-badge" v-if="reviewStats.pending > 0">{{ reviewStats.pending }}</span>
            </el-tab-pane>
            <el-tab-pane label="我的审核" name="my"></el-tab-pane>
            <el-tab-pane label="全部" name="all"></el-tab-pane>
          </el-tabs>
        </div>
      </template>

      <el-row :gutter="20" style="margin-bottom: 20px;">
        <el-col :span="6">
          <div class="stats-card">
            <div class="stat-icon primary">
              <i class="el-icon-time"></i>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ reviewStats.pending || 0 }}</div>
              <div class="stat-label">待审核</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stats-card">
            <div class="stat-icon warning">
              <i class="el-icon-loading"></i>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ reviewStats.in_progress || 0 }}</div>
              <div class="stat-label">审核中</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stats-card">
            <div class="stat-icon success">
              <i class="el-icon-circle-check"></i>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ reviewStats.approved || 0 }}</div>
              <div class="stat-label">已通过</div>
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="stats-card">
            <div class="stat-icon danger">
              <i class="el-icon-circle-close"></i>
            </div>
            <div class="stat-content">
              <div class="stat-value">{{ reviewStats.rejected || 0 }}</div>
              <div class="stat-label">已拒绝</div>
            </div>
          </div>
        </el-col>
      </el-row>

      <el-table
        v-if="reviewList.length > 0"
        :data="reviewList"
        style="width: 100%"
        stripe
      >
        <el-table-column label="缩略图" width="80">
          <template slot-scope="scope">
            <img
              class="table-thumbnail"
              :src="getThumbnailUrl(scope.row)"
              :alt="scope.row.media_id?.filename"
              @click="viewMedia(scope.row)"
              style="cursor: pointer;"
            />
          </template>
        </el-table-column>
        <el-table-column label="文件名" prop="media_id.filename" min-width="200">
          <template slot-scope="scope">
            <div
              :title="scope.row.media_id?.filename"
              style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer;"
              @click="viewMedia(scope.row)"
            >
              {{ scope.row.media_id?.filename || '-' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="100">
          <template slot-scope="scope">
            <el-tag :type="scope.row.priority | priorityType" size="small">
              {{ scope.row.priority | priorityText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template slot-scope="scope">
            <el-tag :type="scope.row.status === 'approved' ? 'success' : scope.row.status === 'rejected' ? 'danger' : scope.row.status === 'in_progress' ? 'primary' : 'warning'" size="small">
              {{ scope.row.status === 'pending' ? '待审核' : scope.row.status === 'in_progress' ? '审核中' : scope.row.status === 'approved' ? '已通过' : '已拒绝' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分配时间" width="160">
          <template slot-scope="scope">
            {{ scope.row.assigned_at | formatDate }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template slot-scope="scope">
            <el-button
              type="text"
              size="small"
              @click="viewMedia(scope.row)"
            >
              预览
            </el-button>
            <el-button
              v-if="scope.row.status === 'pending'"
              type="text"
              size="small"
              @click="startReview(scope.row)"
            >
              开始审核
            </el-button>
            <el-button
              v-if="scope.row.status === 'in_progress' || scope.row.status === 'pending'"
              type="text"
              size="small"
              class="success"
              @click="approveReview(scope.row)"
            >
              通过
            </el-button>
            <el-button
              v-if="scope.row.status === 'in_progress' || scope.row.status === 'pending'"
              type="text"
              size="small"
              class="danger"
              @click="showRejectDialog(scope.row)"
            >
              拒绝
            </el-button>
            <el-button
              type="text"
              size="small"
              @click="showCommentDialog(scope.row)"
            >
              备注
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="empty-state" v-else>
        <i class="el-icon-document-checked empty-icon"></i>
        <div class="empty-text">暂无审核任务</div>
      </div>

      <div class="pagination-container" v-if="total > 0">
        <el-pagination
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
          :current-page="pagination.page"
          :page-sizes="[10, 20, 50, 100]"
          :page-size="pagination.limit"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
        >
        </el-pagination>
      </div>
    </el-card>

    <el-dialog
      title="媒体预览"
      :visible.sync="previewDialogVisible"
      width="900px"
      top="5vh"
    >
      <div v-if="currentReview" class="review-preview">
        <div class="preview-area">
          <video
            v-if="currentReview.media_id?.file_type === 'video'"
            :src="currentReview.media_id?.presigned_url"
            controls
            style="width: 100%; max-height: 500px;"
          ></video>
          <audio
            v-else-if="currentReview.media_id?.file_type === 'audio'"
            :src="currentReview.media_id?.presigned_url"
            controls
            style="width: 100%;"
          ></audio>
          <img
            v-else-if="currentReview.media_id?.file_type === 'image'"
            :src="currentReview.media_id?.presigned_url || currentReview.media_id?.thumbnail_url"
            :alt="currentReview.media_id?.filename"
            style="max-width: 100%; max-height: 500px; display: block; margin: 0 auto;"
          />
          <div v-else class="no-preview">
            <i :class="currentReview.media_id?.file_type | fileTypeIcon" style="font-size: 64px; color: #c0c4cc;"></i>
            <p>该文件类型暂不支持在线预览</p>
          </div>
        </div>

        <el-divider></el-divider>

        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="文件名">
            {{ currentReview.media_id?.filename }}
          </el-descriptions-item>
          <el-descriptions-item label="文件类型">
            {{ currentReview.media_id?.file_type | fileTypeText }}
          </el-descriptions-item>
          <el-descriptions-item label="文件大小">
            {{ currentReview.media_id?.file_size | formatFileSize }}
          </el-descriptions-item>
          <el-descriptions-item label="分辨率">
            <span v-if="currentReview.media_id?.metadata?.width">
              {{ currentReview.media_id?.metadata.width }} x {{ currentReview.media_id?.metadata.height }}
            </span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="时长">
            <span v-if="currentReview.media_id?.metadata?.duration > 0">
              {{ currentReview.media_id?.metadata.duration | formatDuration }}
            </span>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="上传时间">
            {{ currentReview.media_id?.created_at | formatDate }}
          </el-descriptions-item>
        </el-descriptions>

        <el-divider>审核备注</el-divider>

        <div class="comments-list" v-if="currentReview.comments?.length > 0">
          <div
            class="comment-item"
            v-for="(comment, index) in currentReview.comments"
            :key="index"
          >
            <div class="comment-header">
              <span class="comment-author">审核员</span>
              <span class="comment-time">{{ comment.created_at | formatDate }}</span>
            </div>
            <div class="comment-content">{{ comment.comment }}</div>
          </div>
        </div>
        <p v-else style="color: #909399; text-align: center; padding: 20px;">
          暂无备注
        </p>

        <el-input
          v-model="commentText"
          type="textarea"
          placeholder="输入审核备注..."
          :rows="2"
          style="margin-top: 15px;"
        ></el-input>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="previewDialogVisible = false">关 闭</el-button>
        <el-button @click="addComment">添加备注</el-button>
        <el-button type="danger" @click="showRejectDialog(currentReview)">拒 绝</el-button>
        <el-button type="primary" @click="approveReview(currentReview)">通 过</el-button>
      </span>
    </el-dialog>

    <el-dialog
      title="拒绝原因"
      :visible.sync="rejectDialogVisible"
      width="400px"
    >
      <el-input
        v-model="rejectReason"
        type="textarea"
        placeholder="请输入拒绝原因..."
        :rows="4"
      ></el-input>
      <span slot="footer" class="dialog-footer">
        <el-button @click="rejectDialogVisible = false">取 消</el-button>
        <el-button type="danger" @click="confirmReject">确 定</el-button>
      </span>
    </el-dialog>

    <el-dialog
      title="添加备注"
      :visible.sync="commentDialogVisible"
      width="400px"
    >
      <el-input
        v-model="commentText"
        type="textarea"
        placeholder="请输入备注内容..."
        :rows="4"
      ></el-input>
      <span slot="footer" class="dialog-footer">
        <el-button @click="commentDialogVisible = false">取 消</el-button>
        <el-button type="primary" @click="confirmAddComment">确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { listPendingReviews, listMyReviews, startReview, approveReview, rejectReview, addComment, getStats } from '@/api/review'
import { getMediaById, getPresignedUrl } from '@/api/media'

export default {
  name: 'Review',
  data() {
    return {
      activeTab: 'pending',
      reviewList: [],
      reviewStats: {
        pending: 0,
        in_progress: 0,
        approved: 0,
        rejected: 0
      },
      pagination: {
        page: 1,
        limit: 20
      },
      total: 0,
      previewDialogVisible: false,
      rejectDialogVisible: false,
      commentDialogVisible: false,
      currentReview: null,
      rejectReason: '',
      commentText: ''
    }
  },
  mounted() {
    this.fetchStats()
    this.fetchReviewList()
  },
  methods: {
    async fetchStats() {
      try {
        const res = await getStats()
        if (res.code === 200) {
          this.reviewStats = res.data
        }
      } catch (error) {
        console.error('Failed to fetch stats:', error)
      }
    },
    async fetchReviewList() {
      try {
        let res
        if (this.activeTab === 'pending') {
          res = await listPendingReviews(this.pagination)
        } else {
          res = await listMyReviews(this.pagination)
        }

        if (res.code === 200) {
          this.reviewList = res.data.reviews || []
        }
      } catch (error) {
        console.error('Failed to fetch review list:', error)
      }
    },
    handleTabClick(tab) {
      this.activeTab = tab.name
      this.pagination.page = 1
      this.fetchReviewList()
    },
    handleSizeChange(val) {
      this.pagination.limit = val
      this.fetchReviewList()
    },
    handleCurrentChange(val) {
      this.pagination.page = val
      this.fetchReviewList()
    },
    getThumbnailUrl(review) {
      if (review.media_id?.thumbnail_url) {
        return review.media_id.thumbnail_url
      }
      const fileType = review.media_id?.file_type || 'image'
      const thumbnails = {
        image: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxMDAiIGhlaWdodD0iMTAwIiB2aWV3Qm94PSIwIDAgMTAwIDEwMCI+PHJlY3Qgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiIGZpbGw9IiNmNWY3ZmEiLz48dGV4dCB4PSI1MCIgeT0iNjAiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNCIgZmlsbD0iIzlmOTM5OSIgdGV4dC1hbmNob3I9Im1pZGRsZSI+5aSn54mHPC90ZXh0Pjwvc3ZnPg==',
        video: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxMDAiIGhlaWdodD0iMTAwIiB2aWV3Qm94PSIwIDAgMTAwIDEwMCI+PHJlY3Qgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiIGZpbGw9IiNmNWY3ZmEiLz48dGV4dCB4PSI1MCIgeT0iNjAiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNCIgZmlsbD0iIzlmOTM5OSIgdGV4dC1hbmNob3I9Im1pZGRsZSI+6KeG6aKRPC90ZXh0Pjwvc3ZnPg==',
        audio: 'data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIxMDAiIGhlaWdodD0iMTAwIiB2aWV3Qm94PSIwIDAgMTAwIDEwMCI+PHJlY3Qgd2lkdGg9IjEwMCIgaGVpZ2h0PSIxMDAiIGZpbGw9IiNmNWY3ZmEiLz48dGV4dCB4PSI1MCIgeT0iNjAiIGZvbnQtZmFtaWx5PSJBcmlhbCIgZm9udC1zaXplPSIxNCIgZmlsbD0iIzlmOTM5OSIgdGV4dC1hbmNob3I9Im1pZGRsZSI+6Z+z6aKRPC90ZXh0Pjwvc3ZnPg=='
      }
      return thumbnails[fileType] || thumbnails.image
    },
    async viewMedia(review) {
      try {
        this.currentReview = { ...review }
        
        if (review.media_id?.media_id) {
          const mediaRes = await getMediaById(review.media_id.media_id)
          if (mediaRes.code === 200) {
            this.currentReview.media_id = mediaRes.data
            
            if (this.currentReview.media_id.storage_path) {
              try {
                const urlRes = await getPresignedUrl(this.currentReview.media_id.media_id)
                if (urlRes.code === 200) {
                  this.currentReview.media_id.presigned_url = urlRes.data.presigned_url
                }
              } catch (urlError) {
                console.error('Failed to get presigned URL:', urlError)
              }
            }
          }
        }
        
        this.previewDialogVisible = true
      } catch (error) {
        console.error('Failed to view media:', error)
        this.$message.error('获取媒体详情失败')
      }
    },
    async startReview(review) {
      try {
        const res = await startReview(review.review_id)
        if (res.code === 200) {
          this.$message.success('已开始审核')
          this.fetchReviewList()
          this.fetchStats()
        }
      } catch (error) {
        console.error('Failed to start review:', error)
        this.$message.error('开始审核失败')
      }
    },
    async approveReview(review) {
      try {
        await this.$confirm('确定要通过该审核吗？', '提示', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'success'
        })

        const res = await approveReview(review.review_id, {
          comment: this.commentText
        })

        if (res.code === 200) {
          this.$message.success('审核通过')
          this.previewDialogVisible = false
          this.commentText = ''
          this.fetchReviewList()
          this.fetchStats()
        }
      } catch (error) {
        if (error !== 'cancel') {
          console.error('Failed to approve review:', error)
          this.$message.error('审核操作失败')
        }
      }
    },
    showRejectDialog(review) {
      this.currentReview = review
      this.rejectReason = ''
      this.rejectDialogVisible = true
    },
    async confirmReject() {
      if (!this.rejectReason.trim()) {
        this.$message.warning('请输入拒绝原因')
        return
      }

      try {
        const res = await rejectReview(this.currentReview.review_id, {
          reason: this.rejectReason
        })

        if (res.code === 200) {
          this.$message.success('已拒绝')
          this.rejectDialogVisible = false
          this.previewDialogVisible = false
          this.commentText = ''
          this.fetchReviewList()
          this.fetchStats()
        }
      } catch (error) {
        console.error('Failed to reject review:', error)
        this.$message.error('拒绝操作失败')
      }
    },
    showCommentDialog(review) {
      this.currentReview = review
      this.commentText = ''
      this.commentDialogVisible = true
    },
    async confirmAddComment() {
      if (!this.commentText.trim()) {
        this.$message.warning('请输入备注内容')
        return
      }

      await this.addComment()
      this.commentDialogVisible = false
    },
    async addComment() {
      if (!this.commentText.trim() || !this.currentReview) {
        return
      }

      try {
        const res = await addComment(this.currentReview.review_id, {
          comment: this.commentText
        })

        if (res.code === 200) {
          this.$message.success('备注已添加')
          this.commentText = ''
          
          if (this.currentReview.comments) {
            this.currentReview.comments.push({
              reviewer_id: 'system',
              comment: this.commentText,
              created_at: new Date().toISOString()
            })
          }
        }
      } catch (error) {
        console.error('Failed to add comment:', error)
        this.$message.error('添加备注失败')
      }
    }
  }
}
</script>

<style lang="scss" scoped>
.review-page {
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;

    .tab-badge {
      background-color: #F56C6C;
      color: white;
      font-size: 12px;
      padding: 0 6px;
      border-radius: 10px;
      margin-left: 5px;
    }
  }

  .review-preview {
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

    .comments-list {
      max-height: 200px;
      overflow-y: auto;

      .comment-item {
        padding: 10px;
        background-color: #f5f7fa;
        border-radius: 4px;
        margin-bottom: 10px;

        .comment-header {
          display: flex;
          justify-content: space-between;
          margin-bottom: 5px;

          .comment-author {
            font-weight: 600;
            color: #606266;
          }

          .comment-time {
            font-size: 12px;
            color: #909399;
          }
        }

        .comment-content {
          color: #303133;
          line-height: 1.6;
        }
      }
    }
  }

  .success {
    color: #67C23A !important;
  }

  .danger {
    color: #F56C6C !important;
  }
}
</style>
