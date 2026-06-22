import dayjs from 'dayjs'
import type {
  User,
  Room,
  Booking,
  MeetingDoc,
  Todo,
  CheckIn,
  Notification,
  QRCodeData,
  RoomUsageStat,
  MeetingHoursStat,
  AttendanceStat,
  HeatmapData,
  EfficiencyStat,
  DisplayInfo,
} from '@/types'

const UUID = () => 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
  const r = (Math.random() * 16) | 0
  const v = c === 'x' ? r : (r & 0x3) | 0x8
  return v.toString(16)
})

export const uuid = UUID

export interface UserFactoryOptions {
  name?: string
  email?: string
  role?: string
  department?: string
}

export const createMockUser = (opts: UserFactoryOptions = {}): User => {
  const id = UUID()
  return {
    id,
    name: opts.name ?? `测试用户_${id.slice(0, 6)}`,
    email: opts.email ?? `user_${id.slice(0, 6)}@example.com`,
    phone: '13800000000',
    department: opts.department ?? '研发部',
    avatar: '',
    role: opts.role ?? 'user',
    created_at: dayjs().toISOString(),
    updated_at: dayjs().toISOString(),
  }
}

export const createMockAdmin = (overrides: Partial<User> = {}): User => ({
  ...createMockUser({ name: '系统管理员', email: 'admin@example.com', role: 'admin' }),
  ...overrides,
})

export interface RoomFactoryOptions {
  name?: string
  floor?: number
  capacity?: number
  status?: string
  need_approval?: boolean
}

export const createMockRoom = (opts: RoomFactoryOptions = {}): Room => {
  const id = UUID()
  return {
    id,
    name: opts.name ?? `会议室_${id.slice(0, 6)}`,
    floor: opts.floor ?? 1,
    capacity: opts.capacity ?? 10,
    equipment: '投影仪,白板,视频会议',
    description: '设备齐全的会议室',
    status: opts.status ?? 'active',
    need_approval: opts.need_approval ?? false,
    location: 'A座101',
    created_at: dayjs().toISOString(),
    updated_at: dayjs().toISOString(),
  }
}

export interface BookingFactoryOptions {
  room_id?: string
  user_id?: string
  title?: string
  start_time?: dayjs.Dayjs
  duration_minutes?: number
  status?: string
  approval_status?: string
  recurring_rule?: string
  recurring_id?: string
  room?: Room
  user?: User
}

export const createMockBooking = (opts: BookingFactoryOptions = {}): Booking => {
  const id = UUID()
  const start = opts.start_time ?? dayjs().add(1, 'day').hour(10).minute(0).second(0)
  const duration = opts.duration_minutes ?? 60
  const end = start.add(duration, 'minute')
  return {
    id,
    room_id: opts.room_id ?? UUID(),
    user_id: opts.user_id ?? UUID(),
    title: opts.title ?? `测试会议_${id.slice(0, 6)}`,
    description: '测试预订描述',
    start_time: start.format('YYYY-MM-DD HH:mm:ss'),
    end_time: end.format('YYYY-MM-DD HH:mm:ss'),
    status: opts.status ?? 'confirmed',
    recurring_rule: opts.recurring_rule,
    recurring_id: opts.recurring_id,
    approval_status: opts.approval_status ?? 'approved',
    room: opts.room,
    user: opts.user,
    created_at: dayjs().toISOString(),
    updated_at: dayjs().toISOString(),
  }
}

export const createMockRecurringBookings = (
  count: number,
  room: Room,
  user: User,
  base: dayjs.Dayjs = dayjs().add(1, 'day').hour(14).minute(0),
): Booking[] => {
  const recurring_id = UUID()
  return Array.from({ length: count }).map((_, i) =>
    createMockBooking({
      room_id: room.id,
      user_id: user.id,
      start_time: base.add(i * 7, 'day'),
      duration_minutes: 90,
      recurring_rule: 'weekly',
      recurring_id,
      title: `每周例会 #${i + 1}`,
      room,
      user,
    }),
  )
}

export interface MeetingDocFactoryOptions {
  booking_id?: string
  content?: string
  agenda?: string
  summary?: string
  is_archived?: boolean
}

export const createMockMeetingDoc = (opts: MeetingDocFactoryOptions = {}): MeetingDoc => {
  const id = UUID()
  return {
    id,
    booking_id: opts.booking_id ?? UUID(),
    agenda: opts.agenda ?? '## 会议议程\n- 项目进度\n- 问题讨论',
    content: opts.content ?? '讨论内容：项目进展顺利',
    summary: opts.summary,
    is_archived: opts.is_archived ?? false,
    created_at: dayjs().toISOString(),
    updated_at: dayjs().toISOString(),
  }
}

export const createMockContentWithTodos = (todos: string[]): string => {
  const items = todos.map((t, i) => `- [ ] ${t}`).join('\n')
  return `本次会议讨论内容\n\n${items}\n\n讨论完毕`
}

export interface TodoFactoryOptions {
  doc_id?: string
  booking_id?: string
  assignee_id?: string
  content?: string
  status?: string
  priority?: number
}

export const createMockTodo = (opts: TodoFactoryOptions = {}): Todo => {
  const id = UUID()
  return {
    id,
    doc_id: opts.doc_id ?? UUID(),
    booking_id: opts.booking_id ?? UUID(),
    content: opts.content ?? `待办事项_${id.slice(0, 6)}`,
    assignee_id: opts.assignee_id ?? UUID(),
    status: opts.status ?? 'pending',
    priority: opts.priority ?? 1,
    created_at: dayjs().toISOString(),
    updated_at: dayjs().toISOString(),
  }
}

export interface CheckInFactoryOptions {
  booking_id?: string
  user_id?: string
  status?: string
  offset_minutes?: number
}

export const createMockCheckIn = (opts: CheckInFactoryOptions = {}): CheckIn => {
  const id = UUID()
  return {
    id,
    booking_id: opts.booking_id ?? UUID(),
    user_id: opts.user_id ?? UUID(),
    check_in_at: dayjs().add(opts.offset_minutes ?? 0, 'minute').toISOString(),
    qr_code: UUID(),
    status: opts.status ?? 'checked_in',
    created_at: dayjs().toISOString(),
  }
}

export interface QRCodeDataOptions {
  booking_id?: string
  expires_offset_minutes?: number
}

export const createMockQRCodeData = (opts: QRCodeDataOptions = {}): QRCodeData => ({
  token: UUID() + UUID(),
  expires_at: dayjs()
    .add(opts.expires_offset_minutes ?? 5, 'minute')
    .toISOString(),
  booking_id: opts.booking_id ?? UUID(),
})

export interface NotificationFactoryOptions {
  user_id?: string
  type?: string
  title?: string
  content?: string
  status?: string
  booking_id?: string
}

export const createMockNotification = (opts: NotificationFactoryOptions = {}): Notification => {
  const id = UUID()
  return {
    id,
    user_id: opts.user_id ?? UUID(),
    type: opts.type ?? 'booking_confirm',
    title: opts.title ?? `通知_${id.slice(0, 6)}`,
    content: opts.content ?? '通知内容',
    channels: 'wechat,email',
    status: opts.status ?? 'unread',
    booking_id: opts.booking_id,
    created_at: dayjs().toISOString(),
  }
}

export const createMockRoomUsageStats = (rooms: Room[]): RoomUsageStat[] =>
  rooms.map((r, i) => ({
    room_id: r.id,
    room_name: r.name,
    total_hours: 5 + i * 2,
    usage_rate: 10 + i * 5,
    booking_count: 3 + i,
  }))

export const createMockMeetingHoursStats = (): MeetingHoursStat[] => [
  { department: '研发部', total_meetings: 45, total_hours: 80.5, avg_hours: 1.79 },
  { department: '产品部', total_meetings: 32, total_hours: 52.0, avg_hours: 1.63 },
  { department: '设计部', total_meetings: 18, total_hours: 27.0, avg_hours: 1.5 },
]

export const createMockHeatmapData = (): HeatmapData[] => {
  const data: HeatmapData[] = []
  for (let day = 0; day < 7; day++) {
    for (let hour = 8; hour < 20; hour++) {
      data.push({
        day_of_week: day,
        hour,
        count: day >= 1 && day <= 5 && hour >= 10 && hour <= 17 ? Math.floor(Math.random() * 6) : 0,
      })
    }
  }
  return data
}

export const createMockEfficiencyStats = (bookings: Booking[]): EfficiencyStat[] =>
  bookings.map((b) => {
    const planned = dayjs(b.end_time).diff(dayjs(b.start_time), 'minute')
    const actual = Math.round(planned * (0.6 + Math.random() * 0.5))
    return {
      booking_id: b.id,
      title: b.title,
      planned_minutes: planned,
      actual_minutes: actual,
      efficiency_rate: actual > 0 ? (actual / planned) * 100 : 0,
    }
  })

export const createMockDisplayInfo = (
  room: Room,
  current: Booking | null,
  next: Booking | null,
  bookings: Booking[],
): DisplayInfo => ({
  room,
  current_time: dayjs().toISOString(),
  current_booking: current ?? undefined,
  next_booking: next ?? undefined,
  today_bookings: bookings,
})
