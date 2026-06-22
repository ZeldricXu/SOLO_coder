import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const request = axios.create({
  baseURL: '',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('wr_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

request.interceptors.response.use(
  response => response.data,
  error => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        localStorage.removeItem('wr_token')
        localStorage.removeItem('wr_user')
        ElMessage.warning('登录已过期，请重新登录')
        router.push('/login')
      } else {
        ElMessage.error(data?.detail || `请求失败 (${status})`)
      }
    } else {
      ElMessage.error('网络连接失败')
    }
    return Promise.reject(error)
  }
)

export default request
