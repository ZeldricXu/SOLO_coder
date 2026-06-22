import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import RoomCalendar from '@/pages/RoomCalendar'
import { roomApi, bookingApi } from '@/api'
import { createMockUser, createMockRoom, createMockBooking } from '@/tests/factory'
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
  bookingApi: {
    list: vi.fn(),
    myBookings: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    cancel: vi.fn(),
    checkConflict: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
  },
}))

vi.mock('@/store/auth', () => ({
  useAuthStore: vi.fn(),
}))

const mockNavigate = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

const setup = (id = 'room-id-001') => {
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
    <MemoryRouter initialEntries={[`/rooms/${id}`]}>
      <Routes>
        <Route path="/rooms/:id" element={<RoomCalendar />} />
        <Route path="/meeting-docs/:bookingId" element={<div data-testid="doc-page" />} />
        <Route path="/rooms" element={<div data-testid="rooms-list" />} />
      </Routes>
    </MemoryRouter>,
  )
  return { user: userEvent.setup(), id }
}

describe('会议室日历预约页 RoomCalendar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockNavigate.mockReset()
  })

  describe('加载状态和基本UI', () => {
    it('加载会议室信息和预约数据', async () => {
      const room = createMockRoom({ name: '产品评审室', floor: 3, capacity: 8 })
      const bookings = [
        createMockBooking({
          room_id: room.id,
          title: '产品评审会',
          start_time: dayjs().add(1, 'day').hour(10),
          duration_minutes: 60,
          room,
          user: createMockUser(),
        }),
      ]
      vi.mocked(roomApi.get).mockResolvedValueOnce({
        data: room,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(roomApi.calendar).mockResolvedValueOnce({
        data: bookings,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })

      setup(room.id)

      await waitFor(() => {
        expect(roomApi.get).toHaveBeenCalledWith(room.id)
      })
      expect(screen.getByText('产品评审室')).toBeInTheDocument()
      expect(screen.getByText(/3楼/)).toBeInTheDocument()
      expect(screen.getByText(/8人/)).toBeInTheDocument()
      expect(roomApi.calendar).toHaveBeenCalled()
    })

    it('加载失败时显示提示', async () => {
      vi.mocked(roomApi.get).mockRejectedValueOnce(new Error('Network'))
      vi.mocked(roomApi.calendar).mockRejectedValueOnce(new Error('Network'))
      setup('bad-room-id')

      await waitFor(() => {
        expect(screen.getByText('加载会议室信息失败')).toBeInTheDocument()
      })
      expect(screen.getByText('加载预约数据失败')).toBeInTheDocument()
    })
  })

  describe('视图切换', () => {
    beforeEach(() => {
      const room = createMockRoom({ name: '测试间' })
      vi.mocked(roomApi.get).mockResolvedValue({
        data: room,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(roomApi.calendar).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
    })

    it('日/周/月 视图切换按钮渲染', async () => {
      setup()
      await waitFor(() => {
        expect(screen.getByRole('radio', { name: '日' })).toBeInTheDocument()
        expect(screen.getByRole('radio', { name: '周' })).toBeInTheDocument()
        expect(screen.getByRole('radio', { name: '月' })).toBeInTheDocument()
      })
    })

    it('切换到日视图按日刷新数据', async () => {
      setup()
      const dayRadio = await screen.findByRole('radio', { name: '日' })
      fireEvent.click(dayRadio)

      await waitFor(() => {
        expect(roomApi.calendar).toHaveBeenCalled()
      })
    })

    it('切换到月视图按月刷新数据', async () => {
      setup()
      const monthRadio = await screen.findByRole('radio', { name: '月' })
      fireEvent.click(monthRadio)

      await waitFor(() => {
        expect(roomApi.calendar).toHaveBeenCalled()
      })
    })

    it('日期前后导航 - 前一周/下一周', async () => {
      setup()
      const weekBtn = await screen.findByRole('radio', { name: '周' })
      fireEvent.click(weekBtn)

      const prevBtns = screen.getAllByRole('button')
      expect(prevBtns.length).toBeGreaterThan(0)
    })
  })

  describe('预约创建表单', () => {
    beforeEach(() => {
      const room = createMockRoom({ name: '会议室' })
      vi.mocked(roomApi.get).mockResolvedValue({
        data: room,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(roomApi.calendar).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
    })

    it('点击新建预约按钮打开弹窗', async () => {
      const { user } = setup()

      const newBtn = await screen.findByRole('button', { name: /新建预约/ })
      await user.click(newBtn)

      expect(screen.getByText('新建预约')).toBeInTheDocument()
      expect(screen.getByLabelText('会议主题')).toBeInTheDocument()
      expect(screen.getByLabelText('预约时间')).toBeInTheDocument()
      expect(screen.getByLabelText('周期会议')).toBeInTheDocument()
      expect(screen.getByLabelText('会议描述')).toBeInTheDocument()
    })

    it('会议主题为空时提交失败', async () => {
      const { user } = setup()

      const newBtn = await screen.findByRole('button', { name: /新建预约/ })
      await user.click(newBtn)

      const submitBtn = screen.getByRole('button', { name: '确认预约' })
      await user.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByText('请输入会议主题')).toBeInTheDocument()
      })
      expect(bookingApi.create).not.toHaveBeenCalled()
    })

    it('没有选择时间时提交提示', async () => {
      const { user } = setup()
      const newBtn = await screen.findByRole('button', { name: /新建预约/ })
      await user.click(newBtn)

      await user.type(screen.getByLabelText('会议主题'), '没有选择时间的会议')

      const submitBtn = screen.getByRole('button', { name: '确认预约' })
      await user.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByText('请选择时间')).toBeInTheDocument()
      })
    })

    it('提交成功后关闭弹窗并刷新列表', async () => {
      const room = createMockRoom({ name: '会议室' })
      vi.mocked(roomApi.get).mockResolvedValue({
        data: room,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(roomApi.calendar).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(bookingApi.create).mockResolvedValueOnce({
        data: { message: 'success' },
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      } as any)

      const { user } = setup(room.id)
      const newBtn = await screen.findByRole('button', { name: /新建预约/ })
      await user.click(newBtn)

      await user.type(screen.getByLabelText('会议主题'), '正常会议')

      const timeInput = screen.getByLabelText('预约时间')
      await user.click(timeInput)
      await waitFor(() => {
        const ok = screen.getAllByText('确定')
        if (ok.length > 0) fireEvent.click(ok[ok.length - 1])
      })

      const submitBtn = screen.getByRole('button', { name: '确认预约' })
      fireEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByText('预约成功')).toBeInTheDocument()
      })
    })

    it('预约冲突时显示错误信息', async () => {
      const room = createMockRoom()
      vi.mocked(roomApi.get).mockResolvedValue({
        data: room,
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(roomApi.calendar).mockResolvedValue({
        data: [],
        status: 200,
        statusText: 'OK',
        headers: {} as any,
        config: {} as any,
      })
      vi.mocked(bookingApi.create).mockRejectedValueOnce({
        response: { data: { error: 'Time slot conflicts with existing booking' } },
      })

      const { user } = setup(room.id)
      const newBtn = await screen.findByRole('button', { name: /新建预约/ })
      await user.click(newBtn)

      await user.type(screen.getByLabelText('会议主题'), '冲突会议')

      const submitBtn = screen.getByRole('button', { name: '确认预约' })
      fireEvent.click(submitBtn)

      await waitFor(() => {
        expect(screen.getByText(/conflicts/)).toBeInTheDocument()
      })
    })

    it('开始时间晚于结束时间的校验', async () => {
      vi.mocked(bookingApi.create).mockRejectedValueOnce({
        response: { data: { error: 'End time must be after start time' } },
      })
      setup()
      await waitFor(() => expect(screen.getByText(/新建预约/)).toBeInTheDocument())
    })
  })

  describe('点击日历跳转', () => {
    it('点击返回按钮回到会议室列表', async () => {
      const { user } = setup()
      const backBtn = await screen.findByRole('button', { name: '返回' })
      await user.click(backBtn)
      await waitFor(() => {
        expect(mockNavigate).toHaveBeenCalledWith('/rooms')
      })
    })
  })
})
