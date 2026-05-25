function resolveApiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim()
  const normalized = configured?.replace(/\/$/, '') ?? ''

  if (import.meta.env.DEV) {
    const allowAbsoluteInDev = String(import.meta.env.VITE_DEV_ALLOW_ABSOLUTE_API ?? '')
      .toLowerCase()
      .trim() === 'true'

    if (normalized.startsWith('/')) {
      return normalized
    }
    if (!normalized || !allowAbsoluteInDev) {
      return '/api'
    }
  }

  return normalized || '/api'
}

const API_BASE_URL = resolveApiBaseUrl()

export type AdminOperationStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface AdminOperationSnapshot {
  id: string
  type: string
  status: AdminOperationStatus
  queuedAt: string
  startedAt?: string | null
  finishedAt?: string | null
  logs: string[]
  result: Record<string, unknown>
  error?: string | null
}

export interface AdminOperationsListResponse {
  count: number
  items: AdminOperationSnapshot[]
}

interface AdminStatusResponse {
  ok: boolean
  configured: boolean
}

function buildAuthHeaders(tokenHash: string): HeadersInit {
  return {
    Authorization: `Bearer ${tokenHash}`,
    'X-Admin-Authorization': `Bearer ${tokenHash}`,
  }
}

async function parseResponse<T>(response: Response): Promise<T> {
  const responseText = await response.text()
  if (response.ok) {
    if (!responseText) {
      return {} as T
    }
    try {
      return JSON.parse(responseText) as T
    } catch {
      throw new Error(`Expected JSON response, received non-JSON body (status ${response.status}).`)
    }
  }

  let message = `Request failed with status ${response.status}`
  try {
    const errorBody = responseText ? (JSON.parse(responseText) as { message?: unknown }) : null
    if (typeof errorBody?.message === 'string' && errorBody.message.trim()) {
      message = errorBody.message
    }
  } catch {
    if (responseText.trim()) {
      message = responseText
    }
  }
  throw new Error(`${message} (HTTP ${response.status})`)
}

export async function getAdminStatus(tokenHash: string): Promise<AdminStatusResponse> {
  const response = await fetch(`${API_BASE_URL}/admin/status`, {
    method: 'GET',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminStatusResponse>(response)
}

export async function startAdminDownload(tokenHash: string): Promise<AdminOperationSnapshot> {
  const response = await fetch(`${API_BASE_URL}/admin/operations/download`, {
    method: 'POST',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminOperationSnapshot>(response)
}

export async function startAdminIndex(
  tokenHash: string,
  prefix: string,
): Promise<AdminOperationSnapshot> {
  const query = new URLSearchParams({ prefix }).toString()
  const response = await fetch(`${API_BASE_URL}/admin/operations/index?${query}`, {
    method: 'POST',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminOperationSnapshot>(response)
}

export async function startAdminFull(tokenHash: string): Promise<AdminOperationSnapshot> {
  const response = await fetch(`${API_BASE_URL}/admin/operations/full`, {
    method: 'POST',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminOperationSnapshot>(response)
}

export async function fetchAdminOperations(
  tokenHash: string,
  limit = 20,
): Promise<AdminOperationsListResponse> {
  const query = new URLSearchParams({ limit: String(limit) }).toString()
  const response = await fetch(`${API_BASE_URL}/admin/operations?${query}`, {
    method: 'GET',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminOperationsListResponse>(response)
}

export async function fetchAdminOperation(
  tokenHash: string,
  operationId: string,
): Promise<AdminOperationSnapshot> {
  const response = await fetch(`${API_BASE_URL}/admin/operations/${encodeURIComponent(operationId)}`, {
    method: 'GET',
    headers: buildAuthHeaders(tokenHash),
  })
  return parseResponse<AdminOperationSnapshot>(response)
}

export async function sha256Hex(text: string): Promise<string> {
  if (!window.crypto?.subtle) {
    throw new Error('Przegladarka nie wspiera Web Crypto API (crypto.subtle).')
  }
  const bytes = new TextEncoder().encode(text)
  const digest = await window.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}
