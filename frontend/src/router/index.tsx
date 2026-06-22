import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Spin } from 'antd'
import AppLayout from '@/components/Layout'

const Loading = () => (
  <div style={{ display: 'flex', justifyContent: 'center', padding: '100px' }}>
    <Spin size="large" />
  </div>
)

const SwitchList = lazy(() => import('@/pages/SwitchList'))
const SwitchDetail = lazy(() => import('@/pages/SwitchDetail'))
const SwitchCreate = lazy(() => import('@/pages/SwitchCreate'))
const ApprovalList = lazy(() => import('@/pages/ApprovalList'))
const Dashboard = lazy(() => import('@/pages/Dashboard'))

const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      {
        index: true,
        element: <Navigate to="/dashboard" replace />,
      },
      {
        path: 'dashboard',
        element: (
          <Suspense fallback={<Loading />}>
            <Dashboard />
          </Suspense>
        ),
      },
      {
        path: 'switches',
        element: (
          <Suspense fallback={<Loading />}>
            <SwitchList />
          </Suspense>
        ),
      },
      {
        path: 'switches/create',
        element: (
          <Suspense fallback={<Loading />}>
            <SwitchCreate />
          </Suspense>
        ),
      },
      {
        path: 'switches/:id',
        element: (
          <Suspense fallback={<Loading />}>
            <SwitchDetail />
          </Suspense>
        ),
      },
      {
        path: 'approvals',
        element: (
          <Suspense fallback={<Loading />}>
            <ApprovalList />
          </Suspense>
        ),
      },
    ],
  },
])

export default router
