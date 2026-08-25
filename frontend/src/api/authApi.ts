import { apiRequest } from './client'
import type { AuthResponse, OperationResponse, UserProfile } from './types'

export const authApi = {
  register: (payload: {
    username: string
    displayName?: string
    email: string
    password: string
  }) => apiRequest<AuthResponse>('/auth/register', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),

  login: (payload: { identifier: string; password: string }) =>
    apiRequest<AuthResponse>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload),
    }),

  logout: () => apiRequest<void>('/auth/logout', { method: 'POST' }),

  forgotPassword: (email: string) =>
    apiRequest<OperationResponse>('/auth/forgot-password', {
      method: 'POST',
      body: JSON.stringify({ email }),
    }),

  resetPassword: (payload: {
    token: string
    newPassword: string
    confirmPassword: string
  }) => apiRequest<OperationResponse>('/auth/reset-password', {
    method: 'POST',
    body: JSON.stringify(payload),
  }),

  getProfile: () => apiRequest<UserProfile>('/users/me'),

  updateProfile: (payload: { displayName: string; email: string }) =>
    apiRequest<UserProfile>('/users/me', {
      method: 'PUT',
      body: JSON.stringify(payload),
    }),

  changePassword: (payload: {
    currentPassword: string
    newPassword: string
    confirmPassword: string
  }) => apiRequest<OperationResponse>('/users/me/password', {
    method: 'PUT',
    body: JSON.stringify(payload),
  }),
}
