import React from 'react'
import { render, type RenderOptions, type RenderResult } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { vi } from 'vitest'
import type { User } from '@/types'
import { createMockUser } from './factory'

export interface AppTestProvidersProps {
  children: React.ReactNode
  route?: string
  user?: User | null
}

export const AppTestProviders: React.FC<AppTestProvidersProps> = ({
  children,
  route = '/',
  user = createMockUser(),
}) => {
  localStorage.setItem(
    'token',
    'eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyX2lkIjoiZmQwNTk5MWYtY2NhZi00MGUzLWFmYmUtM2IzMjAzODcxNDAzIn0.test',
  )
  if (user) {
    localStorage.setItem('user', JSON.stringify(user))
  }
  return (
    <MemoryRouter initialEntries={[route]}>
      <Routes>
        <Route path="*" element={<>{children}</>} />
      </Routes>
    </MemoryRouter>
  )
}

export interface RenderWithProvidersOptions extends Omit<RenderOptions, 'wrapper'> {
  route?: string
  user?: User | null
}

export const renderWithProviders = (
  ui: React.ReactElement,
  options: RenderWithProvidersOptions = {},
): RenderResult => {
  const { route, user, ...renderOptions } = options
  return render(ui, {
    wrapper: ({ children }) => (
      <AppTestProviders route={route} user={user}>
        {children}
      </AppTestProviders>
    ),
    ...renderOptions,
  })
}

export const mockApiResponse = <T,>(data: T, status = 200, headers?: Record<string, string>) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })

export const mockApiError = (message: string, status = 400) =>
  new Response(JSON.stringify({ error: message }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

export const createFile = (name: string, size = 1024, type = 'application/octet-stream'): File => {
  const buffer = new ArrayBuffer(size)
  return new File([buffer], name, { type })
}

export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export const waitForNextTick = () => sleep(0)
