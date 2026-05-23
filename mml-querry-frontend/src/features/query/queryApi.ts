export interface SyntaxResponse {
  examples: string[]
  supportedOperators: string[]
  supportedPipelineOperations: string[]
  supportedNodeNames?: string[]
  supportedAttributeNames?: string[]
}

export interface ExecuteQueryResponse {
  query: string
  ast: unknown
  description: string
  count: number
  page?: number
  size?: number
  sortBy?: string | null
  sortDirection?: 'asc' | 'desc' | string | null
  filter?: string | null
  returnedCount?: number
  items: QueryItem[]
  timing?: QueryTiming
}

export interface ExecuteQueryRequest {
  query: string
  page?: number
  size?: number
  sortBy?: string
  sortDirection?: 'asc' | 'desc'
  filter?: string
}

export interface ExecuteQueryOptions {
  signal?: AbortSignal
}

export interface QueryTiming {
  parseMs: number
  executeMs: number
  projectionMs: number
  totalMs: number
}

export interface QueryItem {
  [key: string]: string | number | boolean | null | undefined
  item_id?: string
  node_id?: string
  node_path?: string
  lib_id?: string
  article_name?: string
  node_type?: string
  text_position?: string
  raw?: string
  spelling?: string
  occurrences?: number
}

export interface ItemFragmentResponse {
  item_id: string
  lib_id: string
  article_name: string
  source: string
  raw: string
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') ?? 'http://localhost:8080'

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
    if (typeof errorBody?.message === 'string') {
      message = errorBody.message
    }
  } catch {
    if (responseText.trim()) {
      message = responseText
    }
  }

  throw new Error(`${message} (HTTP ${response.status})`)
}

export async function getSyntax(): Promise<SyntaxResponse> {
  const response = await fetch(`${API_BASE_URL}/query/syntax`)
  return parseResponse<SyntaxResponse>(response)
}

export async function executeQuery(
  request: ExecuteQueryRequest,
  options?: ExecuteQueryOptions,
): Promise<ExecuteQueryResponse> {
  const response = await fetch(`${API_BASE_URL}/query/execute`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
    signal: options?.signal,
  })

  return parseResponse<ExecuteQueryResponse>(response)
}

export async function fetchItemFragment(itemId: string): Promise<ItemFragmentResponse> {
  const response = await fetch(`${API_BASE_URL}/query/items/${encodeURIComponent(itemId)}/fragment`)
  return parseResponse<ItemFragmentResponse>(response)
}
