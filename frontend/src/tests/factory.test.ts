import { describe, it, expect, vi } from 'vitest'
import {
  createMockUser,
  createMockRoom,
  createMockBooking,
  createMockRecurringBookings,
  createMockMeetingDoc,
  createMockTodo,
  createMockCheckIn,
  createMockNotification,
  createMockQRCodeData,
  createMockContentWithTodos,
  createMockRoomUsageStats,
  createMockMeetingHoursStats,
  createMockHeatmapData,
  createMockEfficiencyStats,
  createMockDisplayInfo,
  uuid,
} from './factory'
import dayjs from 'dayjs'

describe('测试数据工厂函数', () => {
  describe('用户工厂', () => {
    it('createMockUser - 生成带默认值的普通用户', () => {
      const user = createMockUser()
      expect(user.id).toBeDefined()
      expect(user.id.length).toBeGreaterThan(0)
      expect(user.name).toContain('测试用户_')
      expect(user.email).toContain('@example.com')
      expect(user.role).toBe('user')
      expect(user.department).toBe('研发部')
      expect(user.phone).toBe('13800000000')
    })

    it('createMockUser - 支持自定义字段', () => {
      const user = createMockUser({
        name: '张三',
        email: 'zhangsan@corp.com',
        role: 'admin',
        department: '高管部',
      })
      expect(user.name).toBe('张三')
      expect(user.email).toBe('zhangsan@corp.com')
      expect(user.role).toBe('admin')
      expect(user.department).toBe('高管部')
    })
  })

  describe('会议室工厂', () => {
    it('createMockRoom - 生成默认会议室', () => {
      const room = createMockRoom()
      expect(room.id).toBeDefined()
      expect(room.floor).toBe(1)
      expect(room.capacity).toBe(10)
      expect(room.status).toBe('active')
      expect(room.need_approval).toBe(false)
      expect(room.name).toContain('会议室_')
      expect(room.equipment).toContain('投影仪')
    })

    it('createMockRoom - 需要审批的大会议室', () => {
      const room = createMockRoom({
        name: '董事会议室',
        floor: 20,
        capacity: 50,
        need_approval: true,
      })
      expect(room.name).toBe('董事会议室')
      expect(room.floor).toBe(20)
      expect(room.capacity).toBe(50)
      expect(room.need_approval).toBe(true)
    })
  })

  describe('预订工厂', () => {
    it('createMockBooking - 生成默认单次预订', () => {
      const room = createMockRoom()
      const user = createMockUser()
      const booking = createMockBooking({
        room_id: room.id,
        user_id: user.id,
        room,
        user,
      })
      expect(booking.id).toBeDefined()
      expect(booking.room_id).toBe(room.id)
      expect(booking.user_id).toBe(user.id)
      expect(booking.status).toBe('confirmed')
      expect(booking.approval_status).toBe('approved')
      expect(booking.title).toContain('测试会议_')

      const start = dayjs(booking.start_time)
      const end = dayjs(booking.end_time)
      expect(end.diff(start, 'minute')).toBe(60)
    })

    it('createMockBooking - 支持自定义时间和时长', () => {
      const startTime = dayjs('2025-07-01 14:00:00')
      const booking = createMockBooking({
        start_time: startTime,
        duration_minutes: 120,
      })
      expect(dayjs(booking.start_time).format('YYYY-MM-DD HH:mm:ss')).toBe(
        '2025-07-01 14:00:00',
      )
      expect(dayjs(booking.end_time).format('YYYY-MM-DD HH:mm:ss')).toBe(
        '2025-07-01 16:00:00',
      )
    })

    it('createMockRecurringBookings - 生成周期性会议系列', () => {
      const room = createMockRoom()
      const user = createMockUser()
      const bookings = createMockRecurringBookings(5, room, user)

      expect(bookings).toHaveLength(5)
      const recurringIds = bookings.map((b) => b.recurring_id)
      expect(new Set(recurringIds).size).toBe(1)
      bookings.forEach((b) => {
        expect(b.recurring_rule).toBe('weekly')
        expect(b.room_id).toBe(room.id)
        expect(b.user_id).toBe(user.id)
      })

      const firstStart = dayjs(bookings[0].start_time)
      const secondStart = dayjs(bookings[1].start_time)
      expect(secondStart.diff(firstStart, 'day')).toBe(7)
    })
  })

  describe('会议文档与待办工厂', () => {
    it('createMockMeetingDoc - 生成默认未归档文档', () => {
      const doc = createMockMeetingDoc()
      expect(doc.id).toBeDefined()
      expect(doc.is_archived).toBe(false)
      expect(doc.agenda).toContain('会议议程')
    })

    it('createMockMeetingDoc - 已归档的文档带摘要', () => {
      const doc = createMockMeetingDoc({
        is_archived: true,
        summary: '本次会议很成功',
      })
      expect(doc.is_archived).toBe(true)
      expect(doc.summary).toBe('本次会议很成功')
    })

    it('createMockContentWithTodos - 生成包含TODO的会议纪要内容', () => {
      const todos = ['写接口文档', '修复bug', '评审代码']
      const content = createMockContentWithTodos(todos)
      expect(content).toContain('- [ ] 写接口文档')
      expect(content).toContain('- [ ] 修复bug')
      expect(content).toContain('- [ ] 评审代码')
    })

    it('createMockTodo - 生成默认待办', () => {
      const todo = createMockTodo()
      expect(todo.id).toBeDefined()
      expect(todo.status).toBe('pending')
      expect(todo.priority).toBe(1)
    })
  })

  describe('签到与通知工厂', () => {
    it('createMockCheckIn - 生成签到记录', () => {
      const checkIn = createMockCheckIn()
      expect(checkIn.id).toBeDefined()
      expect(checkIn.status).toBe('checked_in')
    })

    it('createMockQRCodeData - 生成二维码数据', () => {
      const bookingId = uuid()
      const qr = createMockQRCodeData({ booking_id: bookingId })
      expect(qr.token.length).toBeGreaterThan(10)
      expect(qr.booking_id).toBe(bookingId)
      expect(dayjs(qr.expires_at).isAfter(dayjs())).toBe(true)
    })

    it('createMockQRCodeData - 支持过期偏移', () => {
      const qr = createMockQRCodeData({ expires_offset_minutes: -10 })
      expect(dayjs(qr.expires_at).isBefore(dayjs())).toBe(true)
    })

    it('createMockNotification - 生成通知', () => {
      const notif = createMockNotification()
      expect(notif.id).toBeDefined()
      expect(notif.type).toBe('booking_confirm')
      expect(notif.status).toBe('unread')
    })
  })

  describe('统计数据工厂', () => {
    it('createMockRoomUsageStats - 生成会议室使用率统计', () => {
      const rooms = [createMockRoom(), createMockRoom(), createMockRoom()]
      const stats = createMockRoomUsageStats(rooms)
      expect(stats).toHaveLength(3)
      stats.forEach((s, i) => {
        expect(s.room_id).toBe(rooms[i].id)
        expect(s.total_hours).toBeGreaterThan(0)
        expect(s.booking_count).toBeGreaterThan(0)
      })
    })

    it('createMockMeetingHoursStats - 部门时长统计', () => {
      const stats = createMockMeetingHoursStats()
      expect(stats.length).toBeGreaterThanOrEqual(3)
      stats.forEach((s) => {
        expect(s.department).toBeDefined()
        expect(s.total_meetings).toBeGreaterThan(0)
        expect(s.avg_hours).toBeCloseTo(s.total_hours / s.total_meetings, 0)
      })
    })

    it('createMockHeatmapData - 热力图数据', () => {
      const data = createMockHeatmapData()
      expect(data).toHaveLength(7 * 12)
      data.forEach((d) => {
        expect(d.day_of_week).toBeGreaterThanOrEqual(0)
        expect(d.day_of_week).toBeLessThan(7)
        expect(d.hour).toBeGreaterThanOrEqual(8)
        expect(d.hour).toBeLessThan(20)
      })
    })

    it('createMockEfficiencyStats - 会议效率统计', () => {
      const bookings = [createMockBooking(), createMockBooking()]
      const stats = createMockEfficiencyStats(bookings)
      expect(stats).toHaveLength(2)
      stats.forEach((s, i) => {
        expect(s.booking_id).toBe(bookings[i].id)
        expect(s.planned_minutes).toBe(60)
        expect(s.efficiency_rate).toBeGreaterThanOrEqual(0)
      })
    })

    it('createMockDisplayInfo - 屏幕展示信息', () => {
      const room = createMockRoom()
      const current = createMockBooking({ room_id: room.id })
      const next = createMockBooking({
        room_id: room.id,
        start_time: dayjs(current.end_time).add(30, 'minute'),
      })
      const info = createMockDisplayInfo(room, current, next, [current, next])
      expect(info.room.id).toBe(room.id)
      expect(info.current_booking?.id).toBe(current.id)
      expect(info.next_booking?.id).toBe(next.id)
      expect(info.today_bookings).toHaveLength(2)
    })
  })
})
