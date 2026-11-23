export class AccountError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message) }
}

export async function accountRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try { response = await fetch(`/api/v1${path}`, { credentials: 'same-origin', cache: 'no-store', ...options }) }
  catch { throw new AccountError(0, 'NETWORK', '无法连接服务器，请检查网络后重试。') }
  if (!response.ok) {
    let details: { error?: { code?: string; message?: string } } = {}
    try { details = await response.json() } catch { /* proxy errors need not be JSON */ }
    throw new AccountError(response.status, details.error?.code || '', details.error?.message || '操作未完成，请稍后重试。')
  }
  if (response.status === 204) return undefined as T
  return (await response.json() as { data: T }).data
}

export async function csrfToken(): Promise<string> {
  return (await accountRequest<{ csrfToken: string }>('/auth/csrf')).csrfToken
}

export function validUsername(value: string): boolean { return /^[A-Za-z0-9._-]{3,64}$/.test(value) }
export function validPassword(value: string): boolean {
  return Array.from(value).length >= 12 && new TextEncoder().encode(value).length <= 72
}
