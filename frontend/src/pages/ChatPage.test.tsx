import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { chatApi } from '../api/chatApi'
import { connectionApi } from '../api/connectionApi'
import { queryApi } from '../api/queryApi'
import { ToastProvider } from '../context/ToastContext'
import { ChatPage } from './ChatPage'

vi.mock('../api/connectionApi', () => ({ connectionApi: { list: vi.fn() } }))
vi.mock('../api/chatApi', () => ({ chatApi: {
  conversations: vi.fn(), messages: vi.fn(), preview: vi.fn(), removeConversation: vi.fn(),
} }))
vi.mock('../api/queryApi', () => ({ queryApi: { execute: vi.fn() } }))

describe('ChatPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(connectionApi.list).mockResolvedValue([{ id: 2, name: 'Shop', dbType: 'mysql', host: 'localhost', port: 3306, databaseName: 'shop', username: 'reader', active: true, lastTestedAt: null, lastTestSuccessful: true, createdAt: '', updatedAt: '' }])
    vi.mocked(chatApi.conversations).mockResolvedValue([])
    vi.mocked(chatApi.preview).mockResolvedValue({ conversationId: 9, userMessageId: 10, assistantMessageId: 11, generatedSql: 'SELECT * FROM orders', valid: true, validationError: null })
    vi.mocked(chatApi.messages).mockResolvedValue([
      { id: 10, role: 'user', content: 'Liệt kê đơn hàng', generatedSql: null, generatedSqlValid: null, createdAt: '', queryLogs: [] },
      { id: 11, role: 'assistant', content: 'SQL preview đã sẵn sàng.', generatedSql: 'SELECT * FROM orders', generatedSqlValid: true, createdAt: '', queryLogs: [] },
    ])
    vi.mocked(queryApi.execute).mockResolvedValue({
      conversationId: 9,
      messageId: 11,
      generatedSql: 'SELECT * FROM orders',
      status: 'SUCCESS',
      timeoutSeconds: 20,
      result: {
        columns: ['id', 'total'],
        rows: [{ id: 1, total: 125000 }],
        executionTimeMs: 18,
        rowCount: 1,
        truncated: false,
        errorCode: null,
        error: null,
      },
    })
  })

  it('sends a question and renders the safe SQL preview', async () => {
    render(<ToastProvider><ChatPage /></ToastProvider>)
    const input = await screen.findByPlaceholderText('Hỏi bằng tiếng Việt hoặc tiếng Anh…')
    await userEvent.type(input, 'Liệt kê đơn hàng')
    await userEvent.click(screen.getByRole('button', { name: 'Tạo SQL preview' }))

    await waitFor(() => expect(chatApi.preview).toHaveBeenCalledWith({ connectionId: 2, question: 'Liệt kê đơn hàng' }))
    expect(await screen.findByText('SELECT * FROM orders')).toBeInTheDocument()
    expect(screen.getByText('Chưa thực thi')).toBeInTheDocument()
  })

  it('runs a safe preview and renders a dynamic result table', async () => {
    render(<ToastProvider><ChatPage /></ToastProvider>)
    const input = await screen.findByPlaceholderText('Hỏi bằng tiếng Việt hoặc tiếng Anh…')
    await userEvent.type(input, 'Liệt kê đơn hàng')
    await userEvent.click(screen.getByRole('button', { name: 'Tạo SQL preview' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Run query' }))

    await waitFor(() => expect(queryApi.execute).toHaveBeenCalledWith({
      assistantMessageId: 11,
      timeoutSeconds: 20,
    }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getByText('total')).toBeInTheDocument()
    expect(screen.getByText('125000')).toBeInTheDocument()
    expect(screen.getByText('1 dòng')).toBeInTheDocument()
  })
})
