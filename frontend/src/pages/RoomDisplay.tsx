import { useState, useEffect } from 'react'
import { useParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { roomApi, checkInApi } from '@/api'
import type { DisplayInfo } from '@/types'

function RoomDisplay() {
  const { roomId } = useParams<{ roomId: string }>()
  const [displayInfo, setDisplayInfo] = useState<DisplayInfo | null>(null)
  const [currentTime, setCurrentTime] = useState(dayjs())
  const [qrToken, setQrToken] = useState<string>('')

  useEffect(() => {
    if (roomId) {
      loadDisplayInfo()
      loadQRCode()
    }
    const timer = setInterval(() => {
      setCurrentTime(dayjs())
    }, 1000)

    const refreshTimer = setInterval(() => {
      loadDisplayInfo()
      loadQRCode()
    }, 30000)

    return () => {
      clearInterval(timer)
      clearInterval(refreshTimer)
    }
  }, [roomId])

  const loadDisplayInfo = async () => {
    if (!roomId) return
    try {
      const { data } = await roomApi.displayInfo(roomId)
      setDisplayInfo(data)
    } catch (error) {
      console.error('Failed to load display info:', error)
    }
  }

  const loadQRCode = async () => {
    if (!roomId || !displayInfo?.current_booking) return
    try {
      const { data } = await checkInApi.getQRCode(displayInfo.current_booking.id)
      setQrToken(data.token)
    } catch (error) {
      // ignore
    }
  }

  const getTimeRemaining = () => {
    if (!displayInfo?.current_booking) {
      if (displayInfo?.next_booking) {
        const diff = dayjs(displayInfo.next_booking.start_time).diff(currentTime, 'minute')
        if (diff < 60) {
          return `距离下一场会议还有 ${diff} 分钟`
        }
        return `距离下一场会议还有 ${Math.floor(diff / 60)} 小时 ${diff % 60} 分钟`
      }
      return '暂无会议安排'
    }
    const end = dayjs(displayInfo.current_booking.end_time)
    const diff = end.diff(currentTime, 'minute')
    if (diff <= 0) {
      return '会议已结束'
    }
    if (diff < 60) {
      return `还剩 ${diff} 分钟`
    }
    return `还剩 ${Math.floor(diff / 60)} 小时 ${diff % 60} 分钟`
  }

  const getStatusColor = () => {
    if (displayInfo?.current_booking) {
      return '#f5222d'
    }
    return '#52c41a'
  }

  const getStatusText = () => {
    if (displayInfo?.current_booking) {
      return '使用中'
    }
    return '空闲'
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
        padding: 40,
        color: '#fff',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
      }}
    >
      <div style={{ maxWidth: 800, margin: '0 auto' }}>
        {/* 头部 - 会议室信息 */}
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 12,
              background: 'rgba(255,255,255,0.2)',
              padding: '8px 24px',
              borderRadius: 30,
              marginBottom: 16,
            }}
          >
            <div
              style={{
                width: 12,
                height: 12,
                borderRadius: '50%',
                background: getStatusColor(),
                animation: 'pulse 2s infinite',
              }}
            />
            <span style={{ fontSize: 18, fontWeight: 500 }}>{getStatusText()}</span>
          </div>
          <h1 style={{ fontSize: 48, fontWeight: 700, margin: 0 }}>
            {displayInfo?.room?.name || '会议室'}
          </h1>
          <p style={{ fontSize: 20, opacity: 0.9, marginTop: 8 }}>
            {displayInfo?.room?.floor}楼 · 容纳 {displayInfo?.room?.capacity} 人
          </p>
        </div>

        {/* 当前时间 */}
        <div style={{ textAlign: 'center', marginBottom: 40 }}>
          <div style={{ fontSize: 72, fontWeight: 300, lineHeight: 1 }}>
            {currentTime.format('HH:mm:ss')}
          </div>
          <div style={{ fontSize: 20, opacity: 0.8, marginTop: 8 }}>
            {currentTime.format('YYYY年M月D日 dddd')}
          </div>
        </div>

        {/* 当前会议 */}
        {displayInfo?.current_booking ? (
          <div
            style={{
              background: 'rgba(255,255,255,0.15)',
              backdropFilter: 'blur(10px)',
              borderRadius: 20,
              padding: 32,
              marginBottom: 24,
              border: '1px solid rgba(255,255,255,0.2)',
            }}
          >
            <div style={{ fontSize: 16, opacity: 0.8, marginBottom: 12 }}>当前会议</div>
            <h2 style={{ fontSize: 32, fontWeight: 600, margin: '0 0 16px 0' }}>
              {displayInfo.current_booking.title}
            </h2>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <div style={{ fontSize: 18, marginBottom: 4 }}>
                  {dayjs(displayInfo.current_booking.start_time).format('HH:mm')} - {dayjs(displayInfo.current_booking.end_time).format('HH:mm')}
                </div>
                <div style={{ opacity: 0.8, fontSize: 16 }}>
                  组织者：{displayInfo.current_booking.user?.name || '未知'}
                </div>
              </div>
              <div
                style={{
                  textAlign: 'right',
                  background: 'rgba(245, 34, 45, 0.3)',
                  padding: '12px 24px',
                  borderRadius: 12,
                }}
              >
                <div style={{ fontSize: 14, opacity: 0.9 }}>距离结束</div>
                <div style={{ fontSize: 28, fontWeight: 600 }}>{getTimeRemaining()}</div>
              </div>
            </div>

            {/* 签到二维码 */}
            {qrToken && (
              <div style={{ marginTop: 24, textAlign: 'center' }}>
                <div style={{ fontSize: 14, opacity: 0.8, marginBottom: 8 }}>扫码签到</div>
                <div
                  style={{
                    display: 'inline-block',
                    background: '#fff',
                    padding: 12,
                    borderRadius: 8,
                  }}
                >
                  <div
                    style={{
                      width: 120,
                      height: 120,
                      background: '#f0f0f0',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: '#999',
                      fontSize: 12,
                    }}
                  >
                    二维码
                    <br />
                    <span style={{ fontSize: 10 }}>{qrToken.slice(0, 8)}...</span>
                  </div>
                </div>
              </div>
            )}
          </div>
        ) : (
          <div
            style={{
              background: 'rgba(82, 196, 26, 0.2)',
              backdropFilter: 'blur(10px)',
              borderRadius: 20,
              padding: 48,
              textAlign: 'center',
              marginBottom: 24,
              border: '1px solid rgba(255,255,255,0.2)',
            }}
          >
            <div style={{ fontSize: 64, marginBottom: 16 }}>✓</div>
            <h2 style={{ fontSize: 28, fontWeight: 600, margin: 0 }}>会议室空闲中</h2>
            <p style={{ fontSize: 18, opacity: 0.8, marginTop: 12 }}>{getTimeRemaining()}</p>
          </div>
        )}

        {/* 下一场会议 */}
        {displayInfo?.next_booking && (
          <div
            style={{
              background: 'rgba(255,255,255,0.1)',
              backdropFilter: 'blur(10px)',
              borderRadius: 16,
              padding: 24,
              border: '1px solid rgba(255,255,255,0.15)',
            }}
          >
            <div style={{ fontSize: 14, opacity: 0.7, marginBottom: 8 }}>下一场会议</div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <div style={{ fontSize: 22, fontWeight: 600 }}>
                  {displayInfo.next_booking.title}
                </div>
                <div style={{ opacity: 0.8, marginTop: 4 }}>
                  {dayjs(displayInfo.next_booking.start_time).format('HH:mm')} - {dayjs(displayInfo.next_booking.end_time).format('HH:mm')}
                  {' · '}
                  {displayInfo.next_booking.user?.name || '未知'}
                </div>
              </div>
              <div
                style={{
                  background: 'rgba(250, 140, 22, 0.3)',
                  padding: '8px 16px',
                  borderRadius: 8,
                  fontSize: 14,
                }}
              >
                {dayjs(displayInfo.next_booking.start_time).diff(currentTime, 'minute') > 0
                  ? `${Math.floor(dayjs(displayInfo.next_booking.start_time).diff(currentTime, 'minute') / 60)}小时后开始`
                  : '即将开始'}
              </div>
            </div>
          </div>
        )}

        {/* 今日会议列表 */}
        {displayInfo?.today_bookings && displayInfo.today_bookings.length > 0 && (
          <div style={{ marginTop: 24 }}>
            <div style={{ fontSize: 16, marginBottom: 12, opacity: 0.9 }}>今日会议</div>
            <div
              style={{
                background: 'rgba(255,255,255,0.1)',
                borderRadius: 12,
                overflow: 'hidden',
              }}
            >
              {displayInfo.today_bookings.map((booking, index) => (
                <div
                  key={booking.id}
                  style={{
                    padding: '12px 20px',
                    borderBottom: index < displayInfo.today_bookings.length - 1 ? '1px solid rgba(255,255,255,0.1)' : 'none',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    opacity: dayjs(booking.end_time).isBefore(currentTime) ? 0.5 : 1,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div
                      style={{
                        width: 8,
                        height: 8,
                        borderRadius: '50%',
                        background:
                          dayjs(booking.start_time).isBefore(currentTime) && dayjs(booking.end_time).isAfter(currentTime)
                            ? '#f5222d'
                            : dayjs(booking.end_time).isBefore(currentTime)
                            ? '#999'
                            : '#52c41a',
                      }}
                    />
                    <span style={{ fontSize: 14, fontWeight: 500 }}>{booking.title}</span>
                  </div>
                  <span style={{ fontSize: 13, opacity: 0.8 }}>
                    {dayjs(booking.start_time).format('HH:mm')} - {dayjs(booking.end_time).format('HH:mm')}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 底部提示 */}
        <div style={{ textAlign: 'center', marginTop: 40, opacity: 0.6, fontSize: 14 }}>
          扫码预约 · 智能协作 · 高效会议
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.5; }
        }
      `}</style>
    </div>
  )
}

export default RoomDisplay
