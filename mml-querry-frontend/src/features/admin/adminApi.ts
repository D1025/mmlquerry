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

export interface AdminOperationsStreamEvent {
  type: string
  serverTime?: string
  operation?: AdminOperationSnapshot
  operations?: AdminOperationSnapshot[]
}

interface AdminStreamListeners {
  onOpen?: () => void
  onEvent: (event: AdminOperationsStreamEvent) => void
  onError?: (error: Error) => void
  signal?: AbortSignal
  limit?: number
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

function toErrorMessage(response: Response, bodyText: string): string {
  let message = `Request failed with status ${response.status}`
  try {
    const errorBody = bodyText ? (JSON.parse(bodyText) as { message?: unknown }) : null
    if (typeof errorBody?.message === 'string' && errorBody.message.trim()) {
      message = errorBody.message
    }
  } catch {
    if (bodyText.trim()) {
      message = bodyText
    }
  }
  return `${message} (HTTP ${response.status})`
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
  throw new Error(toErrorMessage(response, responseText))
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

export function openAdminOperationsStream(
  tokenHash: string,
  listeners: AdminStreamListeners,
): () => void {
  const abortController = new AbortController()
  const limit = Math.max(1, Math.min(listeners.limit ?? 30, 60))

  if (listeners.signal) {
    const sourceSignal = listeners.signal
    if (sourceSignal.aborted) {
      abortController.abort()
    } else {
      sourceSignal.addEventListener(
        'abort',
        () => {
          abortController.abort()
        },
        { once: true },
      )
    }
  }

  const streamUrl = `${API_BASE_URL}/admin/operations/stream?limit=${limit}`
  void consumeAdminOperationsStream(streamUrl, tokenHash, listeners, abortController.signal)

  return () => {
    abortController.abort()
  }
}

async function consumeAdminOperationsStream(
  streamUrl: string,
  tokenHash: string,
  listeners: AdminStreamListeners,
  signal: AbortSignal,
): Promise<void> {
  try {
    const response = await fetch(streamUrl, {
      method: 'GET',
      headers: {
        ...buildAuthHeaders(tokenHash),
        Accept: 'text/event-stream',
        'Cache-Control': 'no-cache',
      },
      signal,
    })

    if (!response.ok) {
      const responseText = await response.text()
      throw new Error(toErrorMessage(response, responseText))
    }

    if (!response.body) {
      throw new Error('Połączenie stream nie zwróciło treści odpowiedzi.')
    }

    listeners.onOpen?.()

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true }).replace(/\r/g, '')
      const parts = buffer.split('\n\n')
      buffer = parts.pop() ?? ''

      for (const part of parts) {
        const parsed = parseSseMessage(part)
        if (!parsed) {
          continue
        }
        listeners.onEvent(parsed)
      }
    }
  } catch (error) {
    if (signal.aborted) {
      return
    }
    const normalizedError = error instanceof Error ? error : new Error('Połączenie stream zostało przerwane.')
    listeners.onError?.(normalizedError)
  }
}

function parseSseMessage(rawMessage: string): AdminOperationsStreamEvent | null {
  if (!rawMessage.trim()) {
    return null
  }

  const dataLines: string[] = []
  for (const line of rawMessage.split('\n')) {
    if (!line || line.startsWith(':')) {
      continue
    }
    if (!line.startsWith('data:')) {
      continue
    }
    const content = line.slice(5).trimStart()
    dataLines.push(content)
  }

  if (dataLines.length === 0) {
    return null
  }

  const payloadText = dataLines.join('\n')
  try {
    return JSON.parse(payloadText) as AdminOperationsStreamEvent
  } catch {
    return null
  }
}

export async function sha256Hex(text: string): Promise<string> {
  if (!window.crypto?.subtle) {
    throw new Error('Przeglądarka nie wspiera Web Crypto API (crypto.subtle).')
  }
  const bytes = new TextEncoder().encode(text)
  const digest = await window.crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}
