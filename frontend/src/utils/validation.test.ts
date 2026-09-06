import { describe, expect, it } from 'vitest'
import { validateEmail, validatePassword } from './validation'

describe('form validation', () => {
  it('accepts a strong password', () => expect(validatePassword('Secure123')).toBeNull())
  it('rejects a password without uppercase, lowercase and number', () => expect(validatePassword('weakpassword')).not.toBeNull())
  it('validates email format', () => {
    expect(validateEmail('student@example.com')).toBeNull()
    expect(validateEmail('invalid-email')).not.toBeNull()
  })
})
