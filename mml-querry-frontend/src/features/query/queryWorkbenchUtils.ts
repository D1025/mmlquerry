import type { ExecuteQueryRequest, ExecuteQueryResponse, QueryItem } from './queryApi'

export type SortDirection = 'asc' | 'desc'
export type AppPage = 'editor' | 'examples' | 'admin'

export interface ColumnDef {
  key: string
  label: string
}

export interface RowEntry {
  item: QueryItem
  index: number
  rowKey: string
}

export interface SuggestionPosition {
  top: number
  left: number
}

const DEFAULT_TABLE_COLUMNS: ColumnDef[] = [
  { key: 'lib_id', label: 'lib_id' },
  { key: 'article_name', label: 'article_name' },
  { key: 'node_type', label: 'node_type' },
  { key: 'text_position', label: 'position' },
  { key: 'raw', label: 'raw' },
]

const HIDDEN_ROW_KEYS = new Set(['item_id', 'node_id', 'node_path'])
const SYMBOL_PRIORITY_COLUMNS = ['spelling', 'occurrences']
const WORD_CHAR_REGEX = /[A-Za-z0-9_-]/

export const SUGGESTION_MENU_WIDTH = 280
export const PRELOAD_AHEAD_PAGES = 2
export const PRELOAD_BEHIND_PAGES = 2
export const MAX_CACHED_PAGES_PER_KEY = 24

export function resolvePageFromPath(pathname: string): AppPage {
  const normalized = pathname.toLowerCase()
  if (normalized === 'examples') {
    return 'examples'
  }
  if (normalized === 'admin') {
    return 'admin'
  }
  return 'editor'
}

export function normalizeRequestString(raw: string | undefined): string {
  return (raw ?? '').trim()
}

export function normalizeSortDirectionValue(raw: string | undefined): SortDirection {
  return normalizeRequestString(raw).toLowerCase() === 'desc' ? 'desc' : 'asc'
}

export function buildRequestCacheBaseKey(request: ExecuteQueryRequest): string {
  return JSON.stringify({
    query: normalizeRequestString(request.query),
    size: request.size ?? 10,
    sortBy: normalizeRequestString(request.sortBy).toLowerCase(),
    sortDirection: normalizeSortDirectionValue(request.sortDirection),
    filter: normalizeRequestString(request.filter).toLowerCase(),
  })
}

export function buildRequestCachePageKey(request: ExecuteQueryRequest): string {
  return `${buildRequestCacheBaseKey(request)}::${request.page ?? 0}`
}

export function buildCanonicalRequestFromResponse(
  request: ExecuteQueryRequest,
  response: ExecuteQueryResponse,
): ExecuteQueryRequest {
  return {
    query: normalizeRequestString(response.query || request.query),
    page: response.page ?? request.page ?? 0,
    size: response.size ?? request.size ?? 10,
    sortBy: normalizeRequestString((response.sortBy as string | undefined) ?? request.sortBy) || undefined,
    sortDirection: normalizeSortDirectionValue(
      (response.sortDirection as string | undefined) ?? request.sortDirection,
    ),
    filter:
      normalizeRequestString((response.filter as string | undefined) ?? request.filter) || undefined,
  }
}

export function buildRowKey(item: QueryItem, index: number): string {
  const primaryKey = item.node_id ?? item.item_id ?? item.spelling ?? ''
  return `${String(primaryKey)}|${index}`
}

export function hasValue(value: unknown): boolean {
  return String(value ?? '').trim().length > 0
}

export function deriveTableColumns(items: QueryItem[]): ColumnDef[] {
  if (items.length === 0) {
    return DEFAULT_TABLE_COLUMNS
  }

  const keys = new Set<string>()
  for (const item of items) {
    for (const key of Object.keys(item)) {
      if (HIDDEN_ROW_KEYS.has(key)) {
        continue
      }
      if (!hasValue(item[key])) {
        continue
      }
      keys.add(key)
    }
  }

  if (keys.size === 0) {
    return DEFAULT_TABLE_COLUMNS
  }

  const defaultPresent = DEFAULT_TABLE_COLUMNS.filter((column) => keys.has(column.key))
  if (defaultPresent.length > 0) {
    const defaultKeys = new Set(defaultPresent.map((column) => column.key))
    const extra = [...keys]
      .filter((key) => !defaultKeys.has(key))
      .sort((left, right) => left.localeCompare(right, 'pl', { sensitivity: 'base' }))
      .map((key) => ({ key, label: key }))
    return [...defaultPresent, ...extra]
  }

  const prioritized = SYMBOL_PRIORITY_COLUMNS
    .filter((key) => keys.has(key))
    .map((key) => ({ key, label: key }))
  const prioritizedKeys = new Set(prioritized.map((column) => column.key))
  const remaining = [...keys]
    .filter((key) => !prioritizedKeys.has(key))
    .sort((left, right) => left.localeCompare(right, 'pl', { sensitivity: 'base' }))
    .map((key) => ({ key, label: key }))
  return [...prioritized, ...remaining]
}

export function getResultTabProps(index: number) {
  return {
    id: `results-tab-${index}`,
    'aria-controls': `results-panel-${index}`,
  }
}

function getWordStartAtCursor(query: string, cursor: number): number {
  const safeCursor = Math.max(0, Math.min(cursor, query.length))
  let start = safeCursor

  while (start > 0 && WORD_CHAR_REGEX.test(query.charAt(start - 1))) {
    start -= 1
  }

  return start
}

function copyTextInputStyles(source: HTMLElement, target: HTMLElement) {
  const style = window.getComputedStyle(source)
  const properties = [
    'boxSizing',
    'width',
    'height',
    'paddingTop',
    'paddingRight',
    'paddingBottom',
    'paddingLeft',
    'borderTopWidth',
    'borderRightWidth',
    'borderBottomWidth',
    'borderLeftWidth',
    'fontFamily',
    'fontSize',
    'fontStyle',
    'fontVariant',
    'fontWeight',
    'letterSpacing',
    'lineHeight',
    'tabSize',
    'textAlign',
    'textIndent',
    'textTransform',
    'whiteSpace',
    'wordBreak',
    'wordSpacing',
  ]

  for (const property of properties) {
    target.style.setProperty(property, style.getPropertyValue(property))
  }

  target.style.overflowWrap = 'break-word'
}

export function getSuggestionPosition(
  input: HTMLTextAreaElement | HTMLInputElement,
  query: string,
  cursor: number,
): SuggestionPosition {
  const wordStart = getWordStartAtCursor(query, cursor)
  const inputRect = input.getBoundingClientRect()
  const mirror = document.createElement('div')
  const marker = document.createElement('span')

  copyTextInputStyles(input, mirror)
  mirror.style.position = 'fixed'
  mirror.style.top = `${inputRect.top}px`
  mirror.style.left = `${inputRect.left}px`
  mirror.style.visibility = 'hidden'
  mirror.style.pointerEvents = 'none'
  mirror.style.overflow = 'hidden'
  mirror.style.zIndex = '-1'

  marker.textContent = '\u200b'
  mirror.textContent = input.value.slice(0, wordStart)
  mirror.appendChild(marker)
  document.body.appendChild(mirror)

  const markerRect = marker.getBoundingClientRect()
  document.body.removeChild(mirror)

  const rawLeft = markerRect.left - input.scrollLeft
  const rawTop = markerRect.bottom - input.scrollTop + 4
  const maxLeft = Math.max(8, window.innerWidth - SUGGESTION_MENU_WIDTH - 8)

  return {
    left: Math.min(Math.max(8, rawLeft), maxLeft),
    top: Math.min(Math.max(8, rawTop), window.innerHeight - 48),
  }
}
