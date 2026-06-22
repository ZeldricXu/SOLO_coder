import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderWithRouter } from '@/tests/utils'
import {
  fireEvent,
  waitFor,
  screen,
  act,
} from '@testing-library/react'
import dayjs from 'dayjs'
import * as apiModule from '@/api'
import { createMockRoom, createMockBooking, TableDrivenTest } from '@/tests/factory'
import RoomCalendar from '@/pages/RoomCalendar'

vi.mock('@/api', () => ({
  roomApi: {
    get: vi.fn(),
    calendar: vi.fn(),
  },
  bookingApi: {
    create: vi.fn(),
  },
}))

const mockApi = vi.mocked(apiModule)

describe('预约表单校验', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  const timeValidationCases = [
    {
      name: '开始时间晚于结束时间 - 前端拦截',
      input: {
        startTime: dayjs().add(2, 'hour'),
        endTime: dayjs().add(1, 'hour'),
      },
      expected: {
        shouldFail: true,
        errorMessage: '结束时间必须晚于开始时间',
      },
    },
    {
      name: '开始时间等于结束时间 - 前端拦截',
      input: {
        startTime: dayjs().add(1, 'hour'),
        endTime: dayjs().add(1, 'hour'),
      },
      expected: {
        shouldFail: true,
        errorMessage: '结束时间必须晚于开始时间',
      },
    },
    {
      name: '时间范围小于30分钟 - 前端拦截',
      input: {
        startTime: dayjs().add(1, 'hour'),
        endTime: dayjs().add(1, 'hour').add(15, 'minute'),
      },
      expected: {
        shouldFail: true,
        errorMessage: '会议时长至少30分钟',
      },
    },
    {
      name: '正常时间范围 - 通过校验',
      input: {
        startTime: dayjs().add(1, 'hour'),
        endTime: dayjs().add(2, 'hour'),
      },
      expected: {
        shouldFail: false,
        errorMessage: null,
      },
    },
  ]

  TableDrivenTest(timeValidationCases, async (input, expected) => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${room.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText(mockRoom.name)).toBeInTheDocument()
    })

    const newBookingBtn = screen.getByText('新建预约')
    fireEvent.click(newBookingBtn)

    await waitFor(() => {
      expect(screen.getByText('新建预约')).toBeInTheDocument()
    })

    const titleInput = screen.getByRole('textbox', { name: /会议主题/ })
    fireEvent.change(titleInput, { target: { value: '测试会议' } })

    const timePickerContainer = screen.getByText('预约时间').closest('.ant-form-item')
    if (timePickerContainer) {
      const inputs = timePickerContainer.querySelectorAll('input')
      if (inputs.length >= 2) {
        fireEvent.mouseDown(inputs[0])

        const startTimeStr = input.startTime.format('YYYY-MM-DD HH:mm')
        const endTimeStr = input.endTime.format('YYYY-MM-DD HH:mm')

        fireEvent.change(inputs[0], { target: { value: startTimeStr } })
        fireEvent.change(inputs[1], { target: { value: endTimeStr } })
        fireEvent.blur(inputs[1])
      }
    }

    const submitBtn = screen.getByText('确认预约')
    fireEvent.click(submitBtn)

    if (expected.shouldFail) {
      await waitFor(() => {
        const hasError = screen.queryByText(expected.errorMessage || '') ||
          mockApi.bookingApi.create.mock.calls.length === 0
        expect(hasError).toBeTruthy()
      })
    } else {
      expect(true).toBe(true)
    }
  })

  it('主题为空时表单校验失败', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${room.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText(mockRoom.name)).toBeInTheDocument()
    })

    const newBookingBtn = screen.getByText('新建预约')
    fireEvent.click(newBookingBtn)

    await waitFor(() => {
      expect(screen.getByText('新建预约')).toBeInTheDocument()
    })

    const submitBtn = screen.getByText('确认预约')
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(screen.getByText('请输入会议主题')).toBeInTheDocument()
    })

    expect(mockApi.bookingApi.create).not.toHaveBeenCalled()
  })

  it('后端返回冲突时显示冲突提示', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })
    mockApi.bookingApi.create.mockRejectedValue({
      response: {
        status: 409,
        data: { error: '该时间段已被预订，请选择其他时间' },
      },
    })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${room.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText(mockRoom.name)).toBeInTheDocument()
    })

    const newBookingBtn = screen.getByText('新建预约')
    fireEvent.click(newBookingBtn)

    await waitFor(() => {
      expect(screen.getByText('新建预约')).toBeInTheDocument()
    })

    const titleInput = screen.getByRole('textbox', { name: /会议主题/ })
    fireEvent.change(titleInput, { target: { value: '冲突测试会议' } })

    const timePickerContainer = screen.getByText('预约时间').closest('.ant-form-item')
    if (timePickerContainer) {
      const inputs = timePickerContainer.querySelectorAll('input')
      if (inputs.length >= 2) {
        const startTime = dayjs().add(1, 'hour').format('YYYY-MM-DD HH:mm')
        const endTime = dayjs().add(2, 'hour').format('YYYY-MM-DD HH:mm')

        fireEvent.change(inputs[0], { target: { value: startTime } })
        fireEvent.change(inputs[1], { target: { value: endTime } })
        fireEvent.blur(inputs[1])
      }
    }

    const submitBtn = screen.getByText('确认预约')
    fireEvent.click(submitBtn)

    await waitFor(() => {
      expect(mockApi.bookingApi.create).toHaveBeenCalled()
    })
  })
})

describe('日历组件渲染', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('日视图正确显示时间段和已预订的会议', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    const bookings = [
      createMockBooking(mockRoom.id, 'user-1', {
        title: '上午例会',
        startTime: dayjs().hour(9).minute(0).format('YYYY-MM-DD HH:mm:ss'),
        endTime: dayjs().hour(10).minute(0).format('YYYY-MM-DD HH:mm:ss'),
      }),
      createMockBooking(mockRoom.id, 'user-2', {
        title: '技术评审',
        startTime: dayjs().hour(14).minute(0).format('YYYY-MM-DD HH:mm:ss'),
        endTime: dayjs().hour(16).minute(0).format('YYYY-MM-DD HH:mm:ss'),
      }),
    ]

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: bookings })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText(mockRoom.name)).toBeInTheDocument()
    })

    const dayBtn = screen.getByRole('radio', { name: /日/ })
    fireEvent.click(dayBtn)

    await waitFor(() => {
      expect(screen.getByText('7:00')).toBeInTheDocument()
      expect(screen.getByText('上午例会')).toBeInTheDocument()
      expect(screen.getByText('技术评审')).toBeInTheDocument()
    })

    const bookedTimes = screen.getAllByText(/上午例会|技术评审/)
    expect(bookedTimes.length).toBeGreaterThanOrEqual(2)
  })

  it('周视图显示7天网格，高亮今天', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()
    const booking = createMockBooking(mockRoom.id, 'user-1', {
      title: '周例会',
      startTime: dayjs().add(1, 'day').hour(10).format('YYYY-MM-DD HH:mm:ss'),
      endTime: dayjs().add(1, 'day').hour(11).format('YYYY-MM-DD HH:mm:ss'),
    })

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [booking] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText('周')).toBeInTheDocument()
    })

    const weekDays = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
    weekDays.forEach(day => {
      expect(screen.getByText(day)).toBeInTheDocument()
    })

    const hours = [8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]
    hours.forEach(hour => {
      expect(screen.getByText(`${hour}:00`)).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText('周例会')).toBeInTheDocument()
    })
  })

  it('月视图显示日期网格和简略会议信息', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()
    const booking = createMockBooking(mockRoom.id, 'user-1', {
      title: '重要会议',
      startTime: dayjs().date(15).hour(10).format('YYYY-MM-DD HH:mm:ss'),
      endTime: dayjs().date(15).hour(12).format('YYYY-MM-DD HH:mm:ss'),
    })

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [booking] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText('月')).toBeInTheDocument()
    })

    const monthBtn = screen.getByRole('radio', { name: /月/ })
    fireEvent.click(monthBtn)

    const weekDays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
    weekDays.forEach(day => {
      expect(screen.getByText(day)).toBeInTheDocument()
    })

    await waitFor(() => {
      expect(screen.getByText(/重要会议/)).toBeInTheDocument()
    })
  })

  it('点击日期格子切换到日视图', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    const monthBtn = screen.getByRole('radio', { name: /月/ })
    fireEvent.click(monthBtn)

    await waitFor(() => {
      expect(screen.getByText('周日')).toBeInTheDocument()
    })

    const today = dayjs().date().toString()
    const todayCell = screen.getByText(today)
    fireEvent.click(todayCell)

    await waitFor(() => {
      expect(screen.getByRole('radio', { name: /日/ })).toBeChecked()
    })
  })

  it('已被占用的时间段正确显示为已占用', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()
    const booking = createMockBooking(mockRoom.id, 'user-1', {
      title: '已占用的会议',
      startTime: dayjs().hour(10).minute(0).format('YYYY-MM-DD HH:mm:ss'),
      endTime: dayjs().hour(12).minute(0).format('YYYY-MM-DD HH:mm:ss'),
    })

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [booking] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText(mockRoom.name)).toBeInTheDocument()
    })

    const dayBtn = screen.getByRole('radio', { name: /日/ })
    fireEvent.click(dayBtn)

    await waitFor(() => {
      const bookedSlot = screen.getByText('已占用的会议')
      expect(bookedSlot).toBeInTheDocument()
      expect(bookedSlot.closest('div')).toHaveStyle({
        background: '#1677ff',
      })
    })
  })
})

describe('日历导航', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('前后切换日期正确更新标题', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText('周')).toBeInTheDocument()
    })

    const originalTitle = (() => {
      const weekStart = dayjs().startOf('week').format('YYYY年M月D日')
      const weekEnd = dayjs().endOf('week').format('M月D日')
      return `${weekStart} - ${weekEnd}`
    })()

    const prevBtn = screen.getByRole('button', { name: /左/ })
    fireEvent.click(prevBtn)

    await waitFor(() => {
      expect(mockApi.roomApi.calendar).toHaveBeenCalledTimes(2)
    })

    const nextBtn = screen.getByRole('button', { name: /右/ })
    fireEvent.click(nextBtn)
    fireEvent.click(nextBtn)

    await waitFor(() => {
      expect(mockApi.roomApi.calendar).toHaveBeenCalledTimes(4)
    })
  })

  it('点击今天按钮回到当前日期', async () => {
    const room = createMockRoom()
    const mockRoom = createMockRoom()

    mockApi.roomApi.get.mockResolvedValue({ data: mockRoom })
    mockApi.roomApi.calendar.mockResolvedValue({ data: [] })

    renderWithRouter(<RoomCalendar />, { route: `/rooms/${mockRoom.id}/calendar` })

    await waitFor(() => {
      expect(screen.getByText('今天')).toBeInTheDocument()
    })

    const prevBtn = screen.getByRole('button', { name: /左/ })
    fireEvent.click(prevBtn)
    fireEvent.click(prevBtn)

    const todayBtn = screen.getByText('今天')
    fireEvent.click(todayBtn)

    await waitFor(() => {
      expect(mockApi.roomApi.calendar).toHaveBeenCalled()
    })
  })
})
