export interface UserInfo {
  id: number
  username: string
  displayName: string | null
  email: string
  role: 'USER' | 'ADMIN'
  createdAt: string
}

export interface UserProfile extends UserInfo {
  enabled: boolean
  locked: boolean
  updatedAt: string
}

export interface AuthResponse {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
  user: UserInfo
}

export interface ApiErrorBody {
  status: number
  code: string
  message: string
  fieldErrors?: Record<string, string>
}

export interface OperationResponse {
  message: string
}

export interface DatabaseConnection {
  id: number
  name: string
  dbType: 'mysql'
  host: string
  port: number
  databaseName: string
  username: string
  active: boolean
  lastTestedAt: string | null
  lastTestSuccessful: boolean | null
  createdAt: string
  updatedAt: string
}

export interface ConnectionPayload {
  name: string
  dbType: 'mysql'
  host: string
  port: number
  databaseName: string
  username: string
  password: string
}

export interface ConnectionTestResult {
  successful: boolean
  readOnlyVerified: boolean
  code: string
  message: string
  durationMs: number
  serverVersion: string | null
}

export interface AdminStats {
  totalUsers: number
  activeUsers: number
  lockedUsers: number
  activeConnections: number
}

export interface AdminUser {
  id: number
  username: string
  displayName: string | null
  email: string
  role: 'USER'
  enabled: boolean
  locked: boolean
  connectionCount: number
  createdAt: string
}

export interface AdminUserPage {
  content: AdminUser[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
