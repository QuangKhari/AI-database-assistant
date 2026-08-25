import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { authApi } from '../api/authApi'
import { getStoredToken, removeToken, storeToken } from '../api/client'
import type { UserInfo } from '../api/types'

interface AuthContextValue {
  user: UserInfo | null
  loading: boolean
  login: (identifier: string, password: string) => Promise<UserInfo>
  register: (payload: { username: string; displayName?: string; email: string; password: string }) => Promise<void>
  logout: () => Promise<void>
  refreshUser: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserInfo | null>(null)
  const [loading, setLoading] = useState(true)

  const refreshUser = useCallback(async () => {
    if (!getStoredToken()) {
      setUser(null)
      return
    }
    const profile = await authApi.getProfile()
    setUser(profile)
  }, [])

  useEffect(() => {
    refreshUser().catch(() => {
      removeToken()
      setUser(null)
    }).finally(() => setLoading(false))

    const handleUnauthorized = () => setUser(null)
    window.addEventListener('auth:unauthorized', handleUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', handleUnauthorized)
  }, [refreshUser])

  const login = useCallback(async (identifier: string, password: string) => {
    const response = await authApi.login({ identifier, password })
    storeToken(response.accessToken)
    setUser(response.user)
    return response.user
  }, [])

  const register = useCallback(async (payload: { username: string; displayName?: string; email: string; password: string }) => {
    const response = await authApi.register(payload)
    storeToken(response.accessToken)
    setUser(response.user)
  }, [])

  const logout = useCallback(async () => {
    try {
      if (getStoredToken()) await authApi.logout()
    } finally {
      removeToken()
      setUser(null)
    }
  }, [])

  const value = useMemo(() => ({ user, loading, login, register, logout, refreshUser }), [user, loading, login, register, logout, refreshUser])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used within AuthProvider')
  return value
}
