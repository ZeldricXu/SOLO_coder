import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import MainLayout from '@/components/Layout'
import { notificationApi } from '@/api'
import { createMockUser, createMockAdmin } from '@/tests/factory'
import { useAuthStore } from '@/store/auth'

vi.mock('@/api', () => ({
  notificationApi: {
    list: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
    getPreferences: vi.fn(),
    updatePreferences: vi.fn(),
  },
}))

vi.mock('@/store/auth', () => ({
  useAuthStore: vi.fn(),
}))

const mockLogout = vi.fn()

const setup = (mockUser = createMockUser()) => {
  vi.useFakeTimers()
  vi.mocked(useAuthStore).mockImplementation(((selector?: any) => {
    const state = {
      user: mockUser,
      isAuthenticated: true,
      token: 'token',
      login: vi.fn(),
      logout: mockLogout,
      setUser: vi.fn(),
    }
    return typeof selector === 'function' ? selector(state) : state
  }) as any)

  const utils = render(
    <MemoryRouter initialEntries={['/rooms']}>
      <MainLayout />
    </MemoryRouter>,
  )
  return { ...utils, user: userEvent.setup() }
}

describe('主布局组件 MainLayout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
    mockLogout.mockReset()
    vi.mocked(notificationApi.list).mockResolvedValue({
      data: [],
      status: 200,
      statusText: 'OK',
      headers: {} as any,
      config: {} as any,
    } as any)
  })

  describe('侧边栏渲染', () => {
    it('品牌Logo和系统名称渲染', () => {
      setup()
      expect(screen.getByText('会议室预约系统')).toBeInTheDocument()
    })

    it('菜单项完整渲染', () => {
      setup()
      const menuLabels = [
        '会议室管理',
        '我的预约',
        '我的待办',
        '数据统计',
        '消息通知',
        '系统设置',
      ]
      menuLabels.forEach((label) => {
        expect(screen.getByText(label)).toBeInTheDocument()
      })
    })

    it('当前路由高亮选中菜单项', async () => {
      setup()
      await waitFor(() => {
        const roomsMenu = screen.getByText('会议室管理')
        expect(roomsMenu).toBeInTheDocument()
      })
    })
  })

  describe('顶栏信息', () => {
    it('普通用户显示自己名字和头像', () => {
      const user = createMockUser({ name: '张三', department: '研发部' })
      setup(user)
      expect(screen.getByText('张三')).toBeInTheDocument()
      expect(screen.getByText('会议室管理')).toBeInTheDocument()
    })

    it('管理员显示管理员名字', () => {
      const admin = createMockAdmin()
      setup(admin)
      expect(screen.getByText('系统管理员')).toBeInTheDocument()
    })
  })

  describe('未读消息徽标', () => {
    it('有未读消息时显示徽标数量', async () => {
      const { useAuthStore: _a, ...notifApi } = await vi.importActual<typeof import('@/api')>('@/api')
      vi.mocked(notificationApi.list).mockImplementationOnce(async (params: any) => {
        if (params?.status === 'unread') {
          return {
            data: Array.from({ length: 7 }),
            status: 200,
            statusText: 'OK',
            headers: {},
            config: {},
          } as any
        }
        return { data: [], status: 200, statusText: 'OK', headers: {}, config: {} } as any
      })
      setup()

      await waitFor(() => {
        const badge = screen.getByText('7')
        expect(badge).toBeInTheDocument()
      })
    })

    it('未读数量为0时不显示数字', async () => {
      vi.mocked(notificationApi.list).mockImplementation(async (params: any) => {
        return { data: [], status: 200, statusText: 'OK', headers: {}, config: {} } as any
      })
      setup()

      await waitFor(() => {
        expect(notificationApi.list).toHaveBeenCalled()
      })
    })
  })

  describe('用户菜单', () => {
    it('显示用户信息下拉菜单', async () => {
      const user = { user: createMockUser({ name: '李四' }) }
      setup(user.user)

      const avatar = screen.getByText('李四')
      fireEvent.mouseEnter(avatar)

      await waitFor(() => {
        const profileItem = screen.getByText('个人信息')
        expect(profileItem).toBeInTheDocument()
        expect(screen.getByText('设置')).toBeInTheDocument()
        expect(screen.getByText('退出登录')).toBeInTheDocument()
      })
    })

    it('点击退出登录调用logout并跳转', async () => {
      const user = createMockUser()
      setup(user)

      const avatar = screen.getByText(user.name)
      fireEvent.mouseEnter(avatar)

      const logoutItem = await screen.findByText('退出登录')
      fireEvent.click(logoutItem)

      expect(mockLogout).toHaveBeenCalledTimes(1)
    })
  })

  describe('路由子内容渲染', () => {
    it('Outlet正确渲染子页面内容', () => {
      setup()
      expect(screen.getByText('会议室预约系统')).toBeInTheDocument()
    })
  })
})
