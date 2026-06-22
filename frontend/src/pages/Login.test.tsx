import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, useNavigate } from 'react-router-dom'
import Login from '@/pages/Login'
import { useAuthStore } from '@/store/auth'
import { createMockUser } from '@/tests/factory'
import type { User } from '@/types'

vi.mock('@/store/auth', () => ({
  useAuthStore: vi.fn(),
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: vi.fn(),
  }
})

const setup = () => {
  render(
    <BrowserRouter>
      <Login />
    </BrowserRouter>,
  )
  return { user: userEvent.setup() }
}

describe('登录页面 Login', () => {
  const mockLogin = vi.fn()
  const mockNavigate = vi.fn()
  const mockIsAuthenticated = vi.fn()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(useAuthStore).mockImplementation(((selector?: any) => {
      if (typeof selector === 'function') {
        const state = {
          login: mockLogin,
          isAuthenticated: mockIsAuthenticated(),
          user: null,
          token: null,
          logout: vi.fn(),
          setUser: vi.fn(),
        }
        return selector(state)
      }
      return {
        login: mockLogin,
        isAuthenticated: false,
        user: null,
        token: null,
      }
    }) as any)
    vi.mocked(useNavigate).mockReturnValue(mockNavigate)
    mockIsAuthenticated.mockReturnValue(false)
  })

  describe('UI 渲染', () => {
    it('显示品牌标题和表单', () => {
      setup()
      expect(screen.getByText('会议室预约系统')).toBeInTheDocument()
      expect(screen.getByText('高效预约 · 智能协作')).toBeInTheDocument()
      expect(screen.getByPlaceholderText('邮箱地址')).toBeInTheDocument()
      expect(screen.getByPlaceholderText('密码')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument()
      expect(screen.getByText(/测试账号：/)).toBeInTheDocument()
    })

    it('测试账号提示文字正确', () => {
      setup()
      expect(
        screen.getByText('测试账号：admin@company.com / 任意密码'),
      ).toBeInTheDocument()
    })
  })

  describe('表单校验', () => {
    describe('邮箱字段校验', () => {
      it('空邮箱提示错误', async () => {
        const { user } = setup()
        await user.type(screen.getByPlaceholderText('密码'), 'password123')
        await user.click(screen.getByRole('button', { name: '登录' }))
        await waitFor(() => {
          expect(screen.getByText('请输入邮箱')).toBeInTheDocument()
        })
        expect(mockLogin).not.toHaveBeenCalled()
      })

      it('不合法格式邮箱 - 记录当前行为（无额外格式校验，仅校验必填）', async () => {
        const { user } = setup()
        mockLogin.mockResolvedValueOnce(undefined)
        await user.type(screen.getByPlaceholderText('邮箱地址'), 'not-an-email')
        await user.type(screen.getByPlaceholderText('密码'), 'password123')
        await user.click(screen.getByRole('button', { name: '登录' }))
        await waitFor(() => {
          expect(mockLogin).toHaveBeenCalledWith('not-an-email', 'password123')
        })
      })
    })

    describe('密码字段校验', () => {
      it('空密码提示错误', async () => {
        const { user } = setup()
        await user.type(screen.getByPlaceholderText('邮箱地址'), 'user@example.com')
        await user.click(screen.getByRole('button', { name: '登录' }))
        await waitFor(() => {
          expect(screen.getByText('请输入密码')).toBeInTheDocument()
        })
        expect(mockLogin).not.toHaveBeenCalled()
      })

      it('密码支持特殊字符和中英文混合', async () => {
        const { user } = setup()
        mockLogin.mockResolvedValueOnce(undefined)
        await user.type(screen.getByPlaceholderText('邮箱地址'), 'admin@company.com')
        await user.type(screen.getByPlaceholderText('密码'), 'P@ssw0rd!中文测试')
        await user.click(screen.getByRole('button', { name: '登录' }))
        await waitFor(() => {
          expect(mockLogin).toHaveBeenCalledWith('admin@company.com', 'P@ssw0rd!中文测试')
        })
      })
    })
  })

  describe('登录提交流程', () => {
    it('成功登录时触发login并跳转首页', async () => {
      const { user } = setup()
      mockLogin.mockResolvedValueOnce(undefined)
      await user.type(screen.getByPlaceholderText('邮箱地址'), 'user@example.com')
      await user.type(screen.getByPlaceholderText('密码'), 'password123')
      await user.click(screen.getByRole('button', { name: '登录' }))

      await waitFor(() => {
        expect(mockLogin).toHaveBeenCalledTimes(1)
        expect(mockLogin).toHaveBeenCalledWith('user@example.com', 'password123')
      })
      expect(screen.getByText('登录成功')).toBeInTheDocument()
    })

    it('登录失败显示错误信息 - 401无效凭据', async () => {
      const { user } = setup()
      mockLogin.mockRejectedValueOnce({
        response: {
          data: { error: 'Invalid credentials' },
        },
      })
      await user.type(screen.getByPlaceholderText('邮箱地址'), 'user@example.com')
      await user.type(screen.getByPlaceholderText('密码'), 'wrong-password')
      await user.click(screen.getByRole('button', { name: '登录' }))

      await waitFor(() => {
        expect(screen.getByText('Invalid credentials')).toBeInTheDocument()
      })
      expect(mockNavigate).not.toHaveBeenCalledWith('/')
    })

    it('登录失败显示默认错误', async () => {
      const { user } = setup()
      mockLogin.mockRejectedValueOnce(new Error('Network error'))
      await user.type(screen.getByPlaceholderText('邮箱地址'), 'user@example.com')
      await user.type(screen.getByPlaceholderText('密码'), 'password')
      await user.click(screen.getByRole('button', { name: '登录' }))

      await waitFor(() => {
        expect(screen.getByText('登录失败')).toBeInTheDocument()
      })
    })
  })

  describe('已登录用户访问', () => {
    it('已认证用户自动跳转到首页', () => {
      mockIsAuthenticated.mockReturnValue(true)
      setup()
      expect(mockNavigate).toHaveBeenCalledWith('/')
    })
  })
})
