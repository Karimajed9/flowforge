import { vi } from 'vitest'

// Page tests render the signed-in shell. The string is not an identity-provider token.
vi.mock('../auth/session.ts', () => ({
  getAccessToken: vi.fn(async () => {
    if (typeof window === 'undefined') {
      return null
    }
    return 'test-access-token'
  }),
  subscribe: () => () => {},
  signIn: vi.fn(async () => {}),
  signOut: vi.fn(async () => {}),
  clearSession: vi.fn(async () => {}),
  completeSignIn: vi.fn(async () => {}),
}))
