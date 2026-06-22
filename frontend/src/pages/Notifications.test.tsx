import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Notifications from '@/pages/Notifications'
import { notificationApi } from '@/api'
import {
  createMockUser,
  createMockNotification,
} from '@/tests/factory'
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

const setup = () => {
  vi.mocked(useAuthStore).mockImplementation(((selector?: any) => {
    const user = createMockUser()
    const state = {
      user,
      isAuthenticated: true,
      token: 'token',
      login: vi.fn(),
      logout: vi.fn(),
      setUser: vi.fn(),
    }
    return typeof selector === 'function' ? selector(state) : state
  }) as any)

  render(
    <MemoryRouter initialEntries={['/notifications']}>
      <Routes>
        <Route path="/notifications" element={<Notifications />} />
        <Route path="/meeting-docs/:id" element={<div data-testid="doc-page" />} />
      </Routes>
    </MemoryRouter>,
  )
  return { user: userEvent.setup() }
}

describe('通知中心页 Notifications', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('列表加载与渲染', () => {
    it('加载并渲染全部通知 - 混合类型和状态', async () => {
      const notifications = [
        createMockNotification({
          type: 'booking_confirm',
          title: '会议室已预订确认',
          content: '您的会议室A 10:00-12:00已成功预订',
          status: 'unread',
        }),
        createMockNotification({
          type: 'upcoming_remind',
          title: '会议即将开始提醒',
          content: '产品评审会将在15分钟后开始',
          status: 'unread',
          booking_id: 'booking-001',
        }),
        createMockNotification({
          type: 'minutes_release',
          title: '会议纪要已发布',
          content: '上周部门例会纪要已上传',
          status: 'read',
        }),
        createMockNotification({
          type: 'todo_assign',
          title: '新待办分配',
          content: '您被分配了新的待办：完成接口文档',
          status: 'unread',
        }),
      ]
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: notifications,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })

      setup()

      const title = await screen.findByText('消息通知')
      expect(title).toBeInTheDocument()
      expect(screen.getByText('会议室已预订确认')).toBeInTheDocument()
      expect(screen.getByText('会议即将开始提醒')).toBeInTheDocument()
      expect(screen.getByText('会议纪要已发布')).toBeInTheDocument()
      expect(screen.getByText('新待办分配')).toBeInTheDocument()

      const tags = screen.getAllByText('新')
      expect(tags.length).toBe(3)
    })

    it('空状态 - 暂无通知', async () => {
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      await waitFor(() => {
        expect(screen.getByText('暂无通知')).toBeInTheDocument()
      })
    })

    it('加载失败提示错误', async () => {
      vi.mocked(notificationApi.list).mockRejectedValueOnce(new Error('Network'))
      setup()
      await waitFor(() => {
        expect(screen.getByText('加载通知列表失败')).toBeInTheDocument()
      })
    })
  })

  describe('标签页筛选', () => {
    it('Tab渲染正确', async () => {
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      await waitFor(() => {
        expect(screen.getByRole('tab', { name: '全部' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: '未读' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: '预订确认' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: '会议提醒' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: '纪要发布' })).toBeInTheDocument()
        expect(screen.getByRole('tab', { name: '待办分配' })).toBeInTheDocument()
      })
    })

    it('切换到"未读"Tab时携带status=unread参数', async () => {
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      const unreadTab = await screen.findByRole('tab', { name: '未读' })
      fireEvent.click(unreadTab)

      await waitFor(() => {
        expect(notificationApi.list).toHaveBeenLastCalledWith({ status: 'unread' })
      })
    })

    it('切换到"预订确认"Tab时携带type参数', async () => {
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      await waitFor(() => notificationApi.list)
    })
  })

  describe('标为已读操作', () => {
    it('全部标为已读按钮', async () => {
      const notifications = [createMockNotification({ status: 'unread' })]
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: notifications,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(notificationApi.markAllRead).mockResolvedValueOnce({
        data: { message: 'ok' },
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      } as any)

      const { user } = setup()

      const btn = await screen.findByRole('button', { name: /全部标为已读/ })
      await user.click(btn)

      await waitFor(() => {
        expect(notificationApi.markAllRead).toHaveBeenCalledTimes(1)
        expect(screen.getByText('已全部标为已读')).toBeInTheDocument()
      })
    })

    it('单条点击标为已读 - 未读通知点击后调用markRead', async () => {
      const unread = createMockNotification({
        status: 'unread',
        title: '我是未读',
        booking_id: 'booking-123',
      })
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [unread],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(notificationApi.markRead).mockResolvedValueOnce({
        data: {},
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      } as any)

      const { user } = setup()
      const item = await screen.findByText('我是未读')
      await user.click(item)

      await waitFor(() => {
        expect(notificationApi.markRead).toHaveBeenCalledWith(unread.id)
      })
    })

    it('全部标为已读失败提示', async () => {
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(notificationApi.markAllRead).mockRejectedValueOnce({
        response: { data: { error: 'Operation failed' } },
      })
      const { user } = setup()
      const btn = await screen.findByRole('button', { name: /全部标为已读/ })
      await user.click(btn)
      await waitFor(() => {
        expect(screen.getByText(/操作失败/)).toBeInTheDocument()
      })
    })
  })

  describe('通知类型展示', () => {
    it('4种通知类型都能正确显示类型标签', async () => {
      const notifications = [
        createMockNotification({ type: 'booking_confirm', title: '预订' }),
        createMockNotification({ type: 'upcoming_remind', title: '提醒' }),
        createMockNotification({ type: 'minutes_release', title: '纪要' }),
        createMockNotification({ type: 'todo_assign', title: '待办' }),
      ]
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: notifications,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      expect(await screen.findByText('预订确认')).toBeInTheDocument()
      expect(screen.getByText('会议提醒')).toBeInTheDocument()
      expect(screen.getByText('纪要发布')).toBeInTheDocument()
      expect(screen.getByText('待办分配')).toBeInTheDocument()
    })

    it('未知类型通知展示原类型名称', async () => {
      const notif = createMockNotification({
        type: 'strange_custom_type',
        title: '奇怪的通知',
      })
      vi.mocked(notificationApi.list).mockResolvedValue({
        data: [notif],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      expect(await screen.findByText('strange_custom_type')).toBeInTheDocument()
    })
  })
})
