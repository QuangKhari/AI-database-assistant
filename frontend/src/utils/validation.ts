export const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,72}$/
export const USERNAME_PATTERN = /^[a-zA-Z0-9._-]{3,30}$/

export function validatePassword(password: string): string | null {
  if (!password) return 'Vui lòng nhập mật khẩu.'
  if (!PASSWORD_PATTERN.test(password)) return 'Mật khẩu cần 8–72 ký tự, gồm chữ hoa, chữ thường và số.'
  return null
}

export function validateEmail(email: string): string | null {
  if (!email.trim()) return 'Vui lòng nhập email.'
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return 'Email chưa đúng định dạng.'
  return null
}
