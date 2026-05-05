import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use(
  (config) => {
    return config
  },
  (error) => {
    console.error('Request error:', error)
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  (response) => {
    const { data } = response
    if (data.code === 200 || data.success) {
      return data.data || data
    }
    ElMessage.error(data.message || '请求失败')
    return Promise.reject(new Error(data.message || '请求失败'))
  },
  (error) => {
    console.error('Response error:', error)
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

const profileRequest = axios.create({
  baseURL: '/profile-api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

profileRequest.interceptors.response.use(
  (response) => response.data,
  (error) => {
    console.error('Profile API error:', error)
    return Promise.reject(error)
  }
)

export const eventApi = {
  report(events) {
    return request.post('/v1/events/report', { events })
  },
  
  getEvent(eventId) {
    return request.get(`/v1/events/${eventId}`)
  },
  
  heartbeat(data) {
    return request.post('/v1/events/heartbeat', data)
  },
  
  getEventStats(gameId) {
    return request.get(`/v1/events/stats?game_id=${gameId}`)
  }
}

export const statsApi = {
  getOnlineStats(gameId) {
    return request.get(`/v1/stats/online?game_id=${gameId}`)
  },
  
  getTrend(gameId, period = '1h') {
    return request.get(`/v1/stats/trend?game_id=${gameId}&period=${period}`)
  },
  
  getDailyMetrics(gameId, date) {
    return request.get(`/v1/stats/daily?game_id=${gameId}&date=${date}`)
  }
}

export const profileApi = {
  getPlayerProfile(playerId) {
    return profileRequest.get(`/profiles/${playerId}`)
  },
  
  generateProfiles(data) {
    return profileRequest.post('/profiles/generate', data)
  },
  
  refreshProfile(playerId) {
    return profileRequest.post(`/profiles/${playerId}/refresh`)
  },
  
  getStatsSummary(gameId) {
    return profileRequest.get('/profiles/stats/summary', {
      params: { game_id: gameId }
    })
  },
  
  getHighRiskPlayers(gameId, limit = 100) {
    return profileRequest.get('/profiles/churn/high-risk', {
      params: { game_id: gameId, limit }
    })
  },
  
  getTopPayers(gameId, limit = 50) {
    return profileRequest.get('/profiles/top-payers', {
      params: { game_id: gameId, limit }
    })
  }
}

export const analysisApi = {
  getRetention(params) {
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          day1_retention: 42.5,
          day3_retention: 28.3,
          day7_retention: 18.7,
          day14_retention: 12.1,
          day30_retention: 6.8,
          retention_matrix: [
            [100, 42.5, 28.3, 18.7, 12.1, 6.8],
            [100, 45.2, 30.1, 20.3, 13.5, 7.2],
            [100, 43.8, 29.5, 19.8, 12.8, 7.0]
          ],
          cohorts: ['2026-05-01', '2026-05-02', '2026-05-03'],
          days: [0, 1, 3, 7, 14, 30]
        })
      }, 500)
    })
  },
  
  getFunnelAnalysis(params) {
    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          funnel_steps: [
            { step: '注册', count: 10000, conversion_rate: 100 },
            { step: '首次登录', count: 8500, conversion_rate: 85 },
            { step: '完成新手引导', count: 7200, conversion_rate: 84.7 },
            { step: '首次游戏', count: 6500, conversion_rate: 90.3 },
            { step: '首次付费', count: 650, conversion_rate: 10.0 }
          ],
          drop_off_points: [
            { from: '注册', to: '首次登录', drop_count: 1500 },
            { from: '首次登录', to: '完成新手引导', drop_count: 1300 },
            { from: '首次游戏', to: '首次付费', drop_count: 5850 }
          ],
          overall_conversion: 6.5,
          avg_conversion_per_step: 74.0
        })
      }, 500)
    })
  }
}

export default request
