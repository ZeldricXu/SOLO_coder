import axios from 'axios'
import { Message } from 'element-ui'

const service = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

service.interceptors.request.use(
  config => {
    return config
  },
  error => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  response => {
    const res = response.data
    
    if (res.code !== 200) {
      Message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    
    return res
  },
  error => {
    console.error('Response error:', error)
    
    if (error.response) {
      const status = error.response.status
      const message = error.response.data?.message || error.message
      
      switch (status) {
        case 400:
          Message.error(`请求参数错误: ${message}`)
          break
        case 401:
          Message.error('未授权，请登录')
          break
        case 403:
          Message.error('拒绝访问')
          break
        case 404:
          Message.error('请求地址不存在')
          break
        case 500:
          Message.error('服务器内部错误')
          break
        default:
          Message.error(`请求失败: ${message}`)
      }
    } else {
      Message.error('网络错误，请检查网络连接')
    }
    
    return Promise.reject(error)
  }
)

export default service
