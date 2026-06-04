import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authAPI } from '@/api'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('userInfo') || 'null'))
  const roles = ref(JSON.parse(localStorage.getItem('roles') || '[]'))
  const currentProject = ref(JSON.parse(localStorage.getItem('currentProject') || '{"id":1,"name":"默认项目"}'))

  const isLoggedIn = computed(() => !!token.value)

  const login = async (username, password) => {
    const response = await authAPI.login({ username, password })
    token.value = response.token
    userInfo.value = response.user
    roles.value = response.roles || []
    localStorage.setItem('token', token.value)
    localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    localStorage.setItem('roles', JSON.stringify(roles.value))
    return response
  }

  const logout = () => {
    try {
      authAPI.logout()
    } catch (e) {
      // ignore
    }
    token.value = ''
    userInfo.value = null
    roles.value = []
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    localStorage.removeItem('roles')
  }

  const setCurrentProject = (project) => {
    currentProject.value = project
    localStorage.setItem('currentProject', JSON.stringify(project))
  }

  const hasRole = (role) => {
    return roles.value.includes(role)
  }

  const hasAnyRole = (roleList) => {
    return roleList.some(role => roles.value.includes(role))
  }

  return {
    token,
    userInfo,
    roles,
    currentProject,
    isLoggedIn,
    login,
    logout,
    setCurrentProject,
    hasRole,
    hasAnyRole
  }
})
