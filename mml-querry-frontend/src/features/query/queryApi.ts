export interface SyntaxResponse {
  examples: string[]
  supportedOperators: string[]
  supportedPipelineOperations: string[]
}

export interface ExecuteQueryResponse {
  query: string
  ast: unknown
  description: string
  count: number
  items: QueryItem[]
}

export interface QueryItem {
  item_id: string
  lib_id: string
  article_name: string
  node_type: string
  text_position: string
  raw: string
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
  if (response.ok) {
    return (await response.json()) as T
  }

  let message = `Request failed with status ${response.status}`

  try {
    const errorBody = await response.json()
    if (typeof errorBody?.message === 'string') {
      message = errorBody.message
    }
  } catch {
    const errorText = await response.text()
    if (errorText) {
      message = errorText
    }
  }

  throw new Error(message)
}

export async function getSyntax(): Promise<SyntaxResponse> {
  const response = await fetch(`${API_BASE_URL}/query/syntax`)
  return parseResponse<SyntaxResponse>(response)
}

export async function executeQuery(query: string): Promise<ExecuteQueryResponse> {
  const response = await fetch(`${API_BASE_URL}/query/execute`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ query }),
  })

  return parseResponse<ExecuteQueryResponse>(response)
}

export async function fetchItemFragment(itemId: string): Promise<ItemFragmentResponse> {
  const response = await fetch(`${API_BASE_URL}/query/items/${encodeURIComponent(itemId)}/fragment`)
  return parseResponse<ItemFragmentResponse>(response)
}
