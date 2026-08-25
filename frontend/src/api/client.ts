import type { ApiErrorBody } from './types'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api'
const TOKEN_KEY = 'aidb_access_token'

export class ApiError extends Error {
  status: number
  code: string
  fieldErrors?: Record<string, string>

  constructor(body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = body.status
    this.code = body.code
    this.fieldErrors = body.fieldErrors
  }
}

export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function storeToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function removeToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

export async function apiRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const token = getStoredToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers })

  if (response.status === 401 && token) {
    removeToken()
    window.dispatchEvent(new Event('auth:unauthorized'))
  }

  if (!response.ok) {
    let errorBody: ApiErrorBody
    try {
      errorBody = (await response.json()) as ApiErrorBody
    } catch {
      errorBody = {
        status: response.status,
        code: 'HTTP_ERROR',
        message: 'Không thể xử lý yêu cầu. Vui lòng thử lại.',
      }
    }
    throw new ApiError(errorBody)
  }

  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}
