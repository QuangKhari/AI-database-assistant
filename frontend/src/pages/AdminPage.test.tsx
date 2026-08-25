import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminApi } from '../api/adminApi'
import { ToastProvider } from '../context/ToastContext'
import { AdminPage } from './AdminPage'

vi.mock('../api/adminApi', () => ({
  adminApi: { stats: vi.fn(), users: vi.fn(), lock: vi.fn(), unlock: vi.fn() },
}))

describe('AdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.stats).mockResolvedValue({ totalUsers: 1, activeUsers: 1, lockedUsers: 0, activeConnections: 2 })
    vi.mocked(adminApi.users).mockResolvedValue({
      content: [{ id: 8, username: 'student', displayName: 'Student', email: 'student@example.com', role: 'USER', enabled: true, locked: false, connectionCount: 2, createdAt: '2026-08-25T00:00:00' }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    })
    vi.mocked(adminApi.lock).mockResolvedValue({ id: 8, username: 'student', displayName: 'Student', email: 'student@example.com', role: 'USER', enabled: true, locked: true, connectionCount: 2, createdAt: '2026-08-25T00:00:00' })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('renders stats and lets an admin lock a regular user', async () => {
    render(<ToastProvider><AdminPage /></ToastProvider>)

    expect(await screen.findByText('Student')).toBeInTheDocument()
    expect(screen.getAllByText('2').length).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: 'Khóa' }))

    await waitFor(() => expect(adminApi.lock).toHaveBeenCalledWith(8))
  })
})
