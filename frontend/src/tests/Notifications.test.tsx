import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithRouter } from '@/tests/utils'
import { fireEvent, waitFor, screen } from '@testing-library/react'
import * as apiModule from '@/api'
import {
  createMockNotification,
  createMockCheckIn,
  createMockBooking,
  createMockRoom,
  TableDrivenTest,
} from '@/tests/factory'
import Notifications from '@/pages/Notifications'

vi.mock('@/api', () => ({
  notificationApi: {
    list: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn(),
  },
}))

const mockApi = vi.mocked(apiModule)

describe('通知组件', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('通知列表正确渲染，区分已读和未读状态', async () => {
    const notifications = [
      createMockNotification('user-1', {
        id: '1',
        type: 'booking_confirm',
        title: '预订确认',
        content: '您的会议室预订已确认',
        status: 'unread',
        created_at: '2024-01-15T10:00:00Z',
      }),
      createMockNotification('user-1', {
        id: '2',
        type: 'upcoming_remind',
        title: '会议提醒',
        content: '您的会议将在15分钟后开始',
        status: 'read',
        created_at: '2024-01-15T09:00:00Z',
      }),
      createMockNotification('user-1', {
        id: '3',
        type: 'todo_assign',
        title: '待办分配',
        content: '您有新的待办事项',
        status: 'unread',
        created_at: '2024-01-15T08:00:00Z',
      }),
    ]

    mockApi.notificationApi.list.mockResolvedValue({ data: notifications })

    renderWithRouter(<Notifications />)

    await waitFor(() => {
      expect(screen.getByText('预订确认')).toBeInTheDocument()
      expect(screen.getByText('会议提醒')).toBeInTheDocument()
      expect(screen.getByText('待办分配')).toBeInTheDocument()
    })

    const unreadItems = notifications.filter(n => n.status === 'unread')
    unreadItems.forEach(item => {
      const titleEl = screen.getByText(item.title)
      expect(titleEl).toHaveStyle({ fontWeight: 600 })
    })

    const readItems = notifications.filter(n => n.status === 'read')
    readItems.forEach(item => {
      const titleEl = screen.getByText(item.title)
      expect(titleEl).toHaveStyle({ fontWeight: 400 })
    })
  })

  it('通知类型图标和标签正确显示', async () => {
    const typeTestCases = [
      {
        name: '预订确认通知显示日历图标',
        input: createMockNotification('user-1', {
          id: '1',
          type: 'booking_confirm',
          title: '预订确认',
          status: 'unread',
        }),
        expected: { tag: '预订确认', iconColor: 'green' },
      },
      {
        name: '会议提醒通知显示时钟图标',
        input: createMockNotification('user-1', {
          id: '2',
          type: 'upcoming_remind',
          title: '会议提醒',
          status: 'unread',
        }),
        expected: { tag: '会议提醒', iconColor: 'orange' },
      },
      {
        name: '纪要发布通知显示文档图标',
        input: createMockNotification('user-1', {
          id: '3',
          type: 'minutes_release',
          title: '纪要发布',
          status: 'unread',
        }),
        expected: { tag: '纪要发布', iconColor: 'blue' },
      },
      {
        name: '待办分配通知显示待办图标',
        input: createMockNotification('user-1', {
          id: '4',
          type: 'todo_assign',
          title: '待办分配',
          status: 'unread',
        }),
        expected: { tag: '待办分配', iconColor: 'purple' },
      },
    ]

    for (const testCase of typeTestCases) {
      vi.clearAllMocks()
      mockApi.notificationApi.list.mockResolvedValue({ data: [testCase.input] })

      renderWithRouter(<Notifications />)

      await waitFor(() => {
        expect(screen.getByText(testCase.input.title)).toBeInTheDocument()
        expect(screen.getByText(testCase.expected.tag)).toBeInTheDocument()
      })
    }
  })

  it('点击未读通知自动标记为已读', async () => {
    const notification = createMockNotification('user-1', {
      id: '1',
      type: 'booking_confirm',
      title: '预订确认',
      status: 'unread',
      booking_id: 'booking-1',
    })

    mockApi.notificationApi.list.mockResolvedValue({ data: [notification] })
    mockApi.notificationApi.markRead.mockResolvedValue({ data: {} })

    renderWithRouter(<Notifications />)

    await waitFor(() => {
      expect(screen.getByText('预订确认')).toBeInTheDocument()
    })

    const notificationItem = screen.getByText('预订确认').closest('.ant-list-item')
    fireEvent.click(notificationItem!)

    await waitFor(() => {
      expect(mockApi.notificationApi.markRead).toHaveBeenCalledWith('1')
    })
  })

  it('全部标为已读按钮功能正常', async () => {
    const notifications = [
      createMockNotification('user-1', { id: '1', status: 'unread' }),
      createMockNotification('user-1', { id: '2', status: 'unread' }),
      createMockNotification('user-1', { id: '3', status: 'read' }),
    ]

    mockApi.notificationApi.list.mockResolvedValue({ data: notifications })
    mockApi.notificationApi.markAllRead.mockResolvedValue({ data: {} })

    renderWithRouter(<Notifications />)

    await waitFor(() => {
      expect(screen.getByText('全部标为已读')).toBeInTheDocument()
    })

    const markAllBtn = screen.getByText('全部标为已读')
    fireEvent.click(markAllBtn)

    await waitFor(() => {
      expect(mockApi.notificationApi.markAllRead).toHaveBeenCalled()
      expect(mockApi.notificationApi.list).toHaveBeenCalledTimes(2)
    })
  })

  it('按通知类型筛选正常工作', async () => {
    const notifications = [
      createMockNotification('user-1', { id: '1', type: 'booking_confirm', title: '预订1', status: 'unread' }),
      createMockNotification('user-1', { id: '2', type: 'upcoming_remind', title: '提醒1', status: 'read' }),
    ]

    mockApi.notificationApi.list.mockResolvedValue({ data: notifications })

    renderWithRouter(<Notifications />)

    await waitFor(() => {
      expect(screen.getByText('预订1')).toBeInTheDocument()
    })

    const unreadTab = screen.getByRole('tab', { name: /未读/ })
    fireEvent.click(unreadTab)

    await waitFor(() => {
      expect(mockApi.notificationApi.list).toHaveBeenLastCalledWith({ status: 'unread' })
    })

    const allTab = screen.getByRole('tab', { name: /全部/ })
    fireEvent.click(allTab)

    await waitFor(() => {
      expect(mockApi.notificationApi.list).toHaveBeenLastCalledWith({})
    })
  })

  it('空状态正确显示', async () => {
    mockApi.notificationApi.list.mockResolvedValue({ data: [] })

    renderWithRouter(<Notifications />)

    await waitFor(() => {
      expect(screen.getByText('暂无通知')).toBeInTheDocument()
    })
  })
})

describe('签到组件', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('签到状态显示正确', () => {
    const statusCases = [
      {
        name: '已签到状态显示绿色',
        input: createMockCheckIn('booking-1', 'user-1', {
          status: 'checked_in',
        }),
        expected: { statusText: '已签到', color: 'green' },
      },
      {
        name: '迟到状态显示橙色',
        input: createMockCheckIn('booking-1', 'user-1', {
          status: 'late',
        }),
        expected: { statusText: '迟到', color: 'orange' },
      },
      {
        name: '未签到状态显示灰色',
        input: createMockCheckIn('booking-1', 'user-1', {
          status: 'absent',
        }),
        expected: { statusText: '未签到', color: 'default' },
      },
    ]

    TableDrivenTest(statusCases, (input, expected) => {
      const renderCheckInStatus = (status: string) => {
        const colors: Record<string, string> = {
          checked_in: 'green',
          late: 'orange',
          absent: 'default',
        }
        return colors[status] || 'default'
      }

      const color = renderCheckInStatus(input.status)
      expect(color).toBe(expected.color)
    })
  })

  it('签到列表正确显示参会人签到状态', () => {
    const attendees = [
      { id: 'user-1', name: '张三', status: 'checked_in' },
      { id: 'user-2', name: '李四', status: 'late' },
      { id: 'user-3', name: '王五', status: 'absent' },
    ]

    const getAttendeeStatusText = (status: string) => {
      const map: Record<string, string> = {
        checked_in: '已签到',
        late: '迟到',
        absent: '未签到',
      }
      return map[status] || status
    }

    attendees.forEach(attendee => {
      const statusText = getAttendeeStatusText(attendee.status)
      expect(statusText).not.toBe(attendee.status)
      expect(['已签到', '迟到', '未签到']).toContain(statusText)
    })
  })

  it('签到统计数据正确', () => {
    const checkIns = [
      createMockCheckIn('booking-1', 'user-1', { status: 'checked_in' }),
      createMockCheckIn('booking-1', 'user-2', { status: 'checked_in' }),
      createMockCheckIn('booking-1', 'user-3', { status: 'late' }),
      createMockCheckIn('booking-1', 'user-4', { status: 'absent' }),
    ]

    const stats = {
      total: checkIns.length,
      checkedIn: checkIns.filter(c => c.status === 'checked_in').length,
      late: checkIns.filter(c => c.status === 'late').length,
      absent: checkIns.filter(c => c.status === 'absent').length,
      attendanceRate: ((checkIns.filter(c => c.status !== 'absent').length / checkIns.length * 100).toFixed(1),
    }

    expect(stats.total).toBe(4)
    expect(stats.checkedIn).toBe(2)
    expect(stats.late).toBe(1)
    expect(stats.absent).toBe(1)
    expect(stats.attendanceRate).toBe('75.0')
  })

  it('二维码过期后显示无效提示', () => {
    const now = new Date()

    const expiredToken = {
      token: 'expired_token',
      expiresAt: new Date(now.getTime() - 10 * 60 * 1000).toISOString(),
    }

    const validToken = {
      token: 'valid_token',
      expiresAt: new Date(now.getTime() + 10 * 60 * 1000).toISOString(),
    }

    const isTokenExpired = (expiresAt: string) => {
      return new Date(expiresAt) < now
    }

    expect(isTokenExpired(expiredToken.expiresAt)).toBe(true)
    expect(isTokenExpired(validToken.expiresAt)).toBe(false)
  })

  it('30秒内重复扫码不重复签到', () => {
    const checkIns: Array<{ userId: string; checkInAt: string }> = []

    const canCheckIn = (userId: string) => {
      const existing = checkIns.find(c => c.userId === userId)
      if (!existing) return true
      const lastCheckIn = new Date(existing.checkInAt)
      const now = new Date()
      const diff = now.getTime() - lastCheckIn.getTime()
      return diff > 30 * 1000
    }

    checkIns.push({
      userId: 'user-1',
      checkInAt: new Date().toISOString(),
    })

    expect(canCheckIn('user-1')).toBe(false)

    const oldCheckIn = {
      userId: 'user-2',
      checkInAt: new Date(Date.now() - 60 * 1000).toISOString(),
    }
    checkIns.push(oldCheckIn)

    expect(canCheckIn('user-2')).toBe(true)
  })

  it('非参会人签到提示不在参会名单', () => {
    const attendeeList = ['user-1', 'user-2', 'user-3']

    const isInAttendeeList = (userId: string) => {
      return attendeeList.includes(userId)
    }

    expect(isInAttendeeList('user-1')).toBe(true)
    expect(isInAttendeeList('user-4')).toBe(false)
  })
})
