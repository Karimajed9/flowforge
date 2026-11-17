import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const listeners = new Set<() => void>()

let manager: UserManager | null = null

function issuer(): string {
  const configured = import.meta.env.VITE_OIDC_ISSUER_URI?.trim() ?? ''
  return configured.length > 0
    ? configured.replace(/\/$/, '')
    : 'http://localhost:8180/realms/flowforge'
}

function clientId(): string {
  const configured = import.meta.env.VITE_OIDC_CLIENT_ID?.trim() ?? ''
  return configured.length > 0 ? configured : 'flowforge-api'
}

function userManager(): UserManager {
  if (manager) {
    return manager
  }
  manager = new UserManager({
    authority: issuer(),
    client_id: clientId(),
    redirect_uri: `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: 'code',
    scope: 'openid',
    loadUserInfo: false,
    automaticSilentRenew: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  })
  return manager
}

function emit(): void {
  for (const listener of listeners) {
    listener()
  }
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export async function getAccessToken(): Promise<string | null> {
  if (typeof window === 'undefined' || typeof sessionStorage === 'undefined') {
    return null
  }
  const user = await userManager().getUser()
  if (!user || user.expired || user.access_token.length === 0) {
    return null
  }
  return user.access_token
}

export function signIn(): Promise<void> {
  return userManager().signinRedirect()
}

export async function completeSignIn(): Promise<void> {
  await userManager().signinRedirectCallback()
  emit()
}

export async function clearSession(): Promise<void> {
  if (typeof window === 'undefined') {
    return
  }
  await userManager().removeUser()
  emit()
}

export async function signOut(): Promise<void> {
  if (typeof window === 'undefined') {
    return
  }
  try {
    await userManager().signoutRedirect()
  } catch {
    await clearSession()
  }
}
