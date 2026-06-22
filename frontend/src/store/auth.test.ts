import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react-hooks'
import { useAuthStore } from '@/store/auth'
import { authApi } from '@/api'
import { createMockUser } from '@/tests/factory'
import type { User } from '@/types'

vi.mock('@/api', () => ({
  authApi: {
    login: vi.fn(),
    me: vi.fn(),
  },
}))

describe('Auth Store 认证状态管理', () => {
  beforeEach(() => {
    localStorage.clear()
    useAuthStore.setState({
      user: null,
      token: null,
      isAuthenticated: false,
    })
    vi.clearAllMocks()
  })

  describe('初始状态', () => {
    it('未登录时初始状态为未认证', () => {
      const { result } = renderHook(() => useAuthStore())
      expect(result.current.isAuthenticated).toBe(false)
      expect(result.current.user).toBeNull()
      expect(result.current.token).toBeNull()
    })

    it('localStorage有token时初始为已认证状态', () => {
      const mockUser = createMockUser()
      localStorage.setItem('token', 'test-token')
      localStorage.setItem('user', JSON.stringify(mockUser))
      useAuthStore.setState({
        token: 'test-token',
        user: mockUser,
        isAuthenticated: true,
      })
      const { result } = renderHook(() => useAuthStore())
      expect(result.current.isAuthenticated).toBe(true)
      expect(result.current.token).toBe('test-token')
      expect(result.current.user?.id).toBe(mockUser.id)
    })
  })

  describe('login 登录流程', () => {
    it('成功登录后状态更新并保存到localStorage', async () => {
      const mockUser = createMockUser()
      const mockToken = 'eyJhbGciOiJIUzI1NiJ9.test'
      vi.mocked(authApi.login).mockResolvedValueOnce({
        data: { token: mockToken, user: mockUser as User },
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })

      const { result, waitForNextUpdate } = renderHook(() => useAuthStore())

      await act(async () => {
        await result.current.login(mockUser.email, 'password123')
      })

      expect(authApi.login).toHaveBeenCalledWith(mockUser.email, 'password123')
      expect(result.current.isAuthenticated).toBe(true)
      expect(result.current.token).toBe(mockToken)
      expect(result.current.user?.id).toBe(mockUser.id)
      expect(result.current.user?.name).toBe(mockUser.name)
      expect(localStorage.getItem('token')).toBe(mockToken)
      const savedUser = JSON.parse(localStorage.getItem('user')!)
      expect(savedUser.id).toBe(mockUser.id)
    })

    it('登录失败时抛出异常，状态不变', async () => {
      const networkError = new Error('Network Error')
      vi.mocked(authApi.login).mockRejectedValueOnce(networkError)

      const { result } = renderHook(() => useAuthStore())

      await expect(result.current.login('bad@example.com', 'wrong')).rejects.toThrow(
        'Network Error',
      )

      expect(result.current.isAuthenticated).toBe(false)
      expect(result.current.token).toBeNull()
      expect(result.current.user).toBeNull()
      expect(localStorage.getItem('token')).toBeNull()
    })
  })

  describe('logout 登出流程', () => {
    it('登出后清空状态和localStorage', () => {
      const mockUser = createMockUser()
      localStorage.setItem('token', 'valid-token')
      localStorage.setItem('user', JSON.stringify(mockUser))
      useAuthStore.setState({
        token: 'valid-token',
        user: mockUser,
        isAuthenticated: true,
      })

      const { result } = renderHook(() => useAuthStore())
      expect(result.current.isAuthenticated).toBe(true)

      act(() => {
        result.current.logout()
      })

      expect(result.current.isAuthenticated).toBe(false)
      expect(result.current.token).toBeNull()
      expect(result.current.user).toBeNull()
      expect(localStorage.getItem('token')).toBeNull()
      expect(localStorage.getItem('user')).toBeNull()
    })
  })

  describe('setUser 更新用户信息', () => {
    it('setUser可以修改用户信息不影响认证状态', () => {
      const originalUser = createMockUser()
      localStorage.setItem('token', 'some-token')
      useAuthStore.setState({
        token: 'some-token',
        user: originalUser,
        isAuthenticated: true,
      })

      const { result } = renderHook(() => useAuthStore())
      const updatedUser: User = {
        ...originalUser,
        name: '更新后的名字',
        department: '新部门',
      }

      act(() => {
        result.current.setUser(updatedUser)
      })

      expect(result.current.isAuthenticated).toBe(true)
      expect(result.current.user?.name).toBe('更新后的名字')
      expect(result.current.user?.department).toBe('新部门')
    })
  })
})
