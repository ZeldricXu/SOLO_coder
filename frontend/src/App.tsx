import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuthStore } from '@/store'
import MainLayout from '@/components/Layout'
import Login from '@/pages/Login'
import Rooms from '@/pages/Rooms'
import RoomCalendar from '@/pages/RoomCalendar'
import MyBookings from '@/pages/MyBookings'
import MeetingDoc from '@/pages/MeetingDoc'
import MyTodos from '@/pages/MyTodos'
import Statistics from '@/pages/Statistics'
import Notifications from '@/pages/Notifications'
import Settings from '@/pages/Settings'
import RoomDisplay from '@/pages/RoomDisplay'

function App() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated)

  const ProtectedRoute = ({ children }: { children: JSX.Element }) => {
    if (!isAuthenticated) {
      return <Navigate to="/login" replace />
    }
    return children
  }

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/display/:roomId" element={<RoomDisplay />} />
      <Route
        path="/"
        element={
          <ProtectedRoute>
            <MainLayout />
          </ProtectedRoute>
        }
      >
        <Route index element={<Navigate to="/rooms" replace />} />
        <Route path="rooms" element={<Rooms />} />
        <Route path="rooms/:id" element={<RoomCalendar />} />
        <Route path="my-bookings" element={<MyBookings />} />
        <Route path="meeting-docs/:bookingId" element={<MeetingDoc />} />
        <Route path="my-todos" element={<MyTodos />} />
        <Route path="statistics" element={<Statistics />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="settings" element={<Settings />} />
      </Route>
    </Routes>
  )
}

export default App
