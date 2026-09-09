import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { connectionApi } from '../api/connectionApi'
import { schemaApi } from '../api/schemaApi'
import { ToastProvider } from '../context/ToastContext'
import { SchemaExplorerPage } from './SchemaExplorerPage'

vi.mock('../api/connectionApi', () => ({ connectionApi: { list: vi.fn() } }))
vi.mock('../api/schemaApi', () => ({ schemaApi: {
  get: vi.fn(),
  sync: vi.fn(),
  updateTableDescription: vi.fn(),
  updateColumnDescription: vi.fn(),
} }))

const schema = {
  id: 1, connectionId: 2, databaseName: 'shop', lastSyncedAt: '2026-08-27T10:00:00',
  tables: [{ id: 3, name: 'orders', description: null, columns: [
    { id: 4, name: 'customer_id', dataType: 'BIGINT', nullable: false, primaryKey: false,
      foreignKey: true, referencedTable: 'customers', referencedColumn: 'id', description: null },
  ] }],
}

describe('SchemaExplorerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(connectionApi.list).mockResolvedValue([{ id: 2, name: 'Shop', dbType: 'mysql', host: 'localhost', port: 3306, databaseName: 'shop', username: 'reader', active: true, lastTestedAt: null, lastTestSuccessful: true, createdAt: '', updatedAt: '' }])
    vi.mocked(schemaApi.get).mockResolvedValue(schema)
    vi.mocked(schemaApi.sync).mockResolvedValue(schema)
  })

  it('shows metadata and allows the owner to sync it', async () => {
    render(<ToastProvider><SchemaExplorerPage /></ToastProvider>)

    expect(await screen.findByText('orders')).toBeInTheDocument()
    expect(screen.getByText('customer_id')).toBeInTheDocument()
    expect(screen.getByText('FK')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Đồng bộ schema' }))

    await waitFor(() => expect(schemaApi.sync).toHaveBeenCalledWith(2))
  })

  it('lets the user collapse and reopen a table', async () => {
    render(<ToastProvider><SchemaExplorerPage /></ToastProvider>)

    const tableButton = await screen.findByRole('button', { name: /orders/i })
    expect(tableButton).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('customer_id')).toBeInTheDocument()

    await userEvent.click(tableButton)
    expect(tableButton).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByText('customer_id')).not.toBeInTheDocument()

    await userEvent.click(tableButton)
    expect(screen.getByText('customer_id')).toBeInTheDocument()
  })
})
