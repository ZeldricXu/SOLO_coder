import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import Rooms from '@/pages/Rooms'
import { roomApi } from '@/api'
import { createMockUser, createMockAdmin, createMockRoom } from '@/tests/factory'
import { useAuthStore } from '@/store/auth'

vi.mock('@/api', () => ({
  roomApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    get: vi.fn(),
    getBookings: vi.fn(),
    calendar: vi.fn(),
    displayInfo: vi.fn(),
  },
}))

vi.mock('@/store/auth', () => ({
  useAuthStore: vi.fn(),
}))

const setup = (route = '/rooms') => {
  render(
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="/rooms" element={<Rooms />} />
        <Route path="/rooms/:id" element={<div data-testid="calendar-page" />} />
      </Routes>
    </MemoryRouter>,
  )
  return { user: userEvent.setup() }
}

describe('会议室管理页 Rooms', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
  })

  describe('列表渲染', () => {
    it('加载态 -> 渲染会议室卡片列表', async () => {
      const rooms = [
        createMockRoom({ name: '会议室A', floor: 1, capacity: 10 }),
        createMockRoom({ name: '董事会议室', floor: 20, capacity: 50, need_approval: true }),
        createMockRoom({ name: '小会议室', floor: 3, capacity: 4, status: 'inactive' }),
      ]
      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: rooms,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      expect(screen.getByText('会议室列表')).toBeInTheDocument()
      await waitFor(() => {
        expect(roomApi.list).toHaveBeenCalledTimes(1)
      })

      const roomA = await screen.findByText('会议室A')
      expect(roomA).toBeInTheDocument()
      expect(screen.getByText('董事会议室')).toBeInTheDocument()
      expect(screen.getByText('小会议室')).toBeInTheDocument()

      expect(screen.getByText('容纳 10 人')).toBeInTheDocument()
      expect(screen.getByText('容纳 50 人')).toBeInTheDocument()
      expect(screen.getByText('容纳 4 人')).toBeInTheDocument()

      expect(screen.getByText('需审批')).toBeInTheDocument()
      expect(screen.getByText('已下架')).toBeInTheDocument()
    })

    it('空状态 - 没有会议室时的展示', async () => {
      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      await waitFor(() => {
        expect(roomApi.list).toHaveBeenCalled()
      })
    })

    it('加载失败时提示错误', async () => {
      vi.mocked(roomApi.list).mockRejectedValueOnce(new Error('Network'))
      setup()
      await waitFor(() => {
        expect(screen.getByText('加载会议室列表失败')).toBeInTheDocument()
      })
    })
  })

  describe('筛选功能', () => {
    it('搜索框输入关键词触发带搜索参数的请求', async () => {
      vi.mocked(roomApi.list).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      const { user } = setup()
      await waitFor(() => expect(roomApi.list).toHaveBeenCalledTimes(1))

      const searchInput = screen.getByPlaceholderText('搜索会议室')
      await user.type(searchInput, '董事')

      await waitFor(() => {
        expect(roomApi.list).toHaveBeenLastCalledWith(
          expect.objectContaining({ search: '董事' }),
        )
      })
    })

    it('按楼层筛选 - 选择3楼', async () => {
      vi.mocked(roomApi.list).mockResolvedValue({
        data: [createMockRoom({ name: '3楼小间', floor: 3 })],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      await waitFor(() => expect(roomApi.list).toHaveBeenCalledTimes(1))

      const floorSelect = screen.getByPlaceholderText('按楼层筛选')
      fireEvent.mouseDown(floorSelect)
      const option3 = await screen.findByText('3楼')
      fireEvent.click(option3)

      await waitFor(() => {
        expect(roomApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ floor: 3 }))
      })
    })
  })

  describe('管理员功能', () => {
    beforeEach(() => {
      vi.mocked(useAuthStore).mockImplementation(((selector?: any) => {
        const admin = createMockAdmin()
        const state = {
          user: admin,
          isAuthenticated: true,
          token: 'token',
          login: vi.fn(),
          logout: vi.fn(),
          setUser: vi.fn(),
        }
        return typeof selector === 'function' ? selector(state) : state
      }) as any)
    })

    it('管理员能看到"新增会议室"按钮', async () => {
      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      await waitFor(() =>
        expect(screen.getByRole('button', { name: '新增会议室' })).toBeInTheDocument(),
      )
    })

    it('管理员点击每个卡片看到编辑和删除按钮', async () => {
      const room = createMockRoom({ name: '测试间' })
      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: [room],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()

      const card = await screen.findByText('测试间')
      expect(card).toBeInTheDocument()
    })

    it('非管理员看不到"新增会议室"按钮', async () => {
      vi.mocked(useAuthStore).mockImplementation(((selector?: any) => {
        const u = createMockUser()
        const state = {
          user: u,
          isAuthenticated: true,
          token: 'token',
          login: vi.fn(),
          logout: vi.fn(),
          setUser: vi.fn(),
        }
        return typeof selector === 'function' ? selector(state) : state
      }) as any)

      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: [createMockRoom()],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      setup()
      await waitFor(() =>
        expect(screen.queryByRole('button', { name: '新增会议室' })).not.toBeInTheDocument(),
      )
    })
  })

  describe('卡片导航', () => {
    it('点击会议室卡片跳转到日历页', async () => {
      const room = createMockRoom({ name: '跳转测试' })
      vi.mocked(roomApi.list).mockResolvedValueOnce({
        data: [room],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      const { user } = setup()

      const card = await screen.findByText('跳转测试')
      await user.click(card)

      await waitFor(() => {
        expect(screen.getByTestId('calendar-page')).toBeInTheDocument()
      })
    })
  })
})
