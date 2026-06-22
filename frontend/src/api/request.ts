import axios, { AxiosInstance, AxiosRequestConfig, AxiosResponse } from 'axios'
import { message } from 'antd'
import type { ApiResponse } from '@/types'

const service: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

service.interceptors.request.use(
  (config) => {
    const userInfo = localStorage.getItem('userInfo')
    if (userInfo) {
      const user = JSON.parse(userInfo)
      config.headers['X-User-ID'] = user.id || 'anonymous'
      config.headers['X-User-Name'] = user.name || 'anonymous'
    } else {
      config.headers['X-User-ID'] = 'anonymous'
      config.headers['X-User-Name'] = 'anonymous'
    }
    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const res = response.data
    if (res.code !== 0) {
      message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || 'Error'))
    }
    return response.data
  },
  (error) => {
    console.error('Response error:', error)
    if (error.response) {
      const status = error.response.status
      if (status === 401) {
        message.error('请重新登录')
        localStorage.removeItem('userInfo')
        window.location.href = '/login'
      } else if (status === 403) {
        message.error('没有权限执行此操作')
      } else if (status === 404) {
        message.error('请求的资源不存在')
      } else if (status === 500) {
        message.error('服务器内部错误')
      } else {
        message.error(error.message || '网络错误')
      }
    } else if (error.request) {
      message.error('网络连接失败，请检查网络')
    } else {
      message.error('请求配置错误')
    }
    return Promise.reject(error)
  }
)

export interface RequestConfig extends AxiosRequestConfig {}

export const request = {
  get: <T = any>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> => {
    return service.get(url, config) as any
  },
  post: <T = any>(url: string, data?: any, config?: RequestConfig): Promise<ApiResponse<T>> => {
    return service.post(url, data, config) as any
  },
  put: <T = any>(url: string, data?: any, config?: RequestConfig): Promise<ApiResponse<T>> => {
    return service.put(url, data, config) as any
  },
  delete: <T = any>(url: string, config?: RequestConfig): Promise<ApiResponse<T>> => {
    return service.delete(url, config) as any
  },
  patch: <T = any>(url: string, data?: any, config?: RequestConfig): Promise<ApiResponse<T>> => {
    return service.patch(url, data, config) as any
  },
}

export default service
