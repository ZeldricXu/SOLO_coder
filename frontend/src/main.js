import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
import App from './App.vue'
import router from './router'
import store from './store'
import './styles/main.scss'
import dayjs from 'dayjs'

Vue.use(ElementUI)

Vue.prototype.$dayjs = dayjs
Vue.prototype.$message = ElementUI.Message
Vue.prototype.$confirm = ElementUI.MessageBox.confirm

Vue.config.productionTip = false

Vue.filter('formatFileSize', function(size) {
  if (!size || size === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(size) / Math.log(k))
  return parseFloat((size / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
})

Vue.filter('formatDuration', function(seconds) {
  if (!seconds || seconds === 0) return '00:00'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  
  if (h > 0) {
    return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
  }
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
})

Vue.filter('formatDate', function(date, format = 'YYYY-MM-DD HH:mm:ss') {
  if (!date) return ''
  return dayjs(date).format(format)
})

Vue.filter('statusText', function(status) {
  const statusMap = {
    'pending': '等待中',
    'uploading': '上传中',
    'processing': '处理中',
    'pending_review': '待审核',
    'approved': '已通过',
    'rejected': '已拒绝',
    'failed': '失败'
  }
  return statusMap[status] || status
})

Vue.filter('statusType', function(status) {
  const typeMap = {
    'pending': 'info',
    'uploading': 'primary',
    'processing': 'warning',
    'pending_review': 'warning',
    'approved': 'success',
    'rejected': 'danger',
    'failed': 'danger'
  }
  return typeMap[status] || 'info'
})

Vue.filter('priorityText', function(priority) {
  const priorityMap = {
    'low': '低',
    'medium': '中',
    'high': '高',
    'urgent': '紧急'
  }
  return priorityMap[priority] || priority
})

Vue.filter('priorityType', function(priority) {
  const typeMap = {
    'low': 'info',
    'medium': 'warning',
    'high': 'danger',
    'urgent': 'danger'
  }
  return typeMap[priority] || 'info'
})

Vue.filter('fileTypeText', function(type) {
  const typeMap = {
    'image': '图片',
    'video': '视频',
    'audio': '音频',
    'other': '其他'
  }
  return typeMap[type] || type
})

Vue.filter('fileTypeIcon', function(type) {
  const iconMap = {
    'image': 'el-icon-picture',
    'video': 'el-icon-video-camera',
    'audio': 'el-icon-microphone',
    'other': 'el-icon-document'
  }
  return iconMap[type] || 'el-icon-document'
})

Vue.filter('channelTypeText', function(type) {
  const typeMap = {
    'weixin': '微信公众号',
    'weibo': '微博',
    'douyin': '抖音',
    'bilibili': '哔哩哔哩',
    'xigua': '西瓜视频',
    'custom': '自定义渠道'
  }
  return typeMap[type] || type
})

Vue.filter('distributionStatusText', function(status) {
  const statusMap = {
    'draft': '草稿',
    'pending': '待推送',
    'processing': '推送中',
    'pushing': '推送中',
    'success': '已成功',
    'completed': '已完成',
    'failed': '失败'
  }
  return statusMap[status] || status
})

Vue.filter('distributionStatusType', function(status) {
  const typeMap = {
    'draft': 'info',
    'pending': 'warning',
    'processing': 'primary',
    'pushing': 'primary',
    'success': 'success',
    'completed': 'success',
    'failed': 'danger'
  }
  return typeMap[status] || 'info'
})

new Vue({
  router,
  store,
  render: h => h(App)
}).$mount('#app')
