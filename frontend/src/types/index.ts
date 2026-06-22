export interface User {
  id: string
  name: string
  email: string
  phone?: string
  department?: string
  avatar?: string
  role: string
  wechat_id?: string
  dingtalk_id?: string
  feishu_id?: string
  created_at?: string
  updated_at?: string
}

export interface Room {
  id: string
  name: string
  floor: number
  capacity: number
  equipment?: string
  description?: string
  status: string
  need_approval: boolean
  approver_id?: string
  location?: string
  created_at?: string
  updated_at?: string
}

export interface Booking {
  id: string
  room_id: string
  user_id: string
  title: string
  description?: string
  start_time: string
  end_time: string
  status: string
  recurring_rule?: string
  recurring_id?: string
  attendees?: string
  approval_status: string
  approver_id?: string
  approved_at?: string
  reject_reason?: string
  created_at?: string
  updated_at?: string
  room?: Room
  user?: User
}

export interface MeetingDoc {
  id: string
  booking_id: string
  agenda?: string
  content?: string
  summary?: string
  is_archived: boolean
  archived_at?: string
  created_at?: string
  updated_at?: string
}

export interface Todo {
  id: string
  doc_id: string
  booking_id: string
  content: string
  assignee_id: string
  status: string
  due_date?: string
  priority: number
  created_at?: string
  updated_at?: string
  assignee?: User
}

export interface CheckIn {
  id: string
  booking_id: string
  user_id: string
  check_in_at: string
  qr_code?: string
  status: string
  created_at?: string
  user?: User
}

export interface Notification {
  id: string
  user_id: string
  type: string
  title: string
  content?: string
  channels?: string
  status: string
  booking_id?: string
  created_at?: string
  read_at?: string
}

export interface NotificationPreference {
  id: string
  user_id: string
  booking_confirm: boolean
  upcoming_remind: boolean
  minutes_release: boolean
  todo_assign: boolean
  channels: string
  created_at?: string
  updated_at?: string
}

export interface QRCodeData {
  token: string
  expires_at: string
  booking_id: string
}

export interface RoomUsageStat {
  room_id: string
  room_name: string
  total_hours: number
  usage_rate: number
  booking_count: number
}

export interface MeetingHoursStat {
  department: string
  total_meetings: number
  total_hours: number
  avg_hours: number
}

export interface HeatmapData {
  day_of_week: number
  hour: number
  count: number
}

export interface EfficiencyStat {
  booking_id: string
  title: string
  planned_minutes: number
  actual_minutes: number
  efficiency_rate: number
}

export interface DisplayInfo {
  room: Room
  current_time: string
  current_booking?: Booking
  next_booking?: Booking
  today_bookings: Booking[]
}
