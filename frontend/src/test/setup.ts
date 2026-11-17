import { vi } from 'vitest'

// Page tests render a signed-in user. This profile is not an access token.
vi.mock('../auth/session.ts', () => ({
  loadProfile: vi.fn(async () => ({
    subject: 'user',
    issuer: 'http://auth.test/realms/flowforge',
    username: 'Ada',
    permissions: ['flowforge.user'],
  })),
  subscribe: () => () => {},
  signIn: vi.fn(),
  signOut: vi.fn(async () => {}),
  noteSignedOut: vi.fn(),
  isAdmin: (profile: { permissions?: string[] } | null | undefined) =>
    Array.isArray(profile?.permissions) &&
    profile.permissions.includes('flowforge.admin'),
}))

vi.mock('../auth/csrf.ts', () => ({
  csrfHeaders: vi.fn(async () => ({ 'X-XSRF-TOKEN': 'test-csrf' })),
  clearCsrf: vi.fn(),
  refreshCsrf: vi.fn(async () => ({
    headerName: 'X-XSRF-TOKEN',
    parameterName: '_csrf',
    token: 'test-csrf',
  })),
  currentCsrf: vi.fn(async () => ({
    headerName: 'X-XSRF-TOKEN',
    parameterName: '_csrf',
    token: 'test-csrf',
  })),
}))
