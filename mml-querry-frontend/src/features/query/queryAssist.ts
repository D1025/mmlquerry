import type { SyntaxResponse } from './queryApi'

const START_KEYWORDS = ['list', 'article']
const SCOPE_KEYWORDS = ['item', 'proposition']
const CLAUSE_KEYWORDS = ['of', 'in', 'where', 'has', 'and', 'or', 'butnot', 'not']
const LIST_TYPES = ['theorem', 'definition', 'statement', 'registration', 'constructor', 'all']
const PIPELINE_KEYWORDS = [
  'ref',
  'occur',
  'definition',
  'notation',
  'redef',
  'origin',
  'copy',
  'termtype',
  'deftype',
  'main',
  'mode',
  'functor',
  'filter',
  'grep',
  'reverse',
  'invert',
  'whereeq',
  'wherege',
  'wherele',
  'wheregt',
  'wherelt',
]
const NODE_HINTS = [
  'InfixTerm',
  'Thesis',
  'Redefine',
  'AttributePattern',
  'ModePattern',
  'PredicatePattern',
  'FunctorPattern',
]
const ATTRIBUTE_HINTS = ['spelling', 'occurs', 'absolutepatternmmlid']

const VALID_KEYWORDS = new Set(
  [
    ...START_KEYWORDS,
    ...SCOPE_KEYWORDS,
    ...CLAUSE_KEYWORDS,
    ...LIST_TYPES,
    ...PIPELINE_KEYWORDS,
    ...ATTRIBUTE_HINTS,
  ].map((token) => token.toLowerCase()),
)

const WORD_REGEX = /[A-Za-z0-9_-]/
const HAS_NODE_REGEX =
  /\b(?:item|proposition)\s+has\s+("[^"\\]*(?:\\.[^"\\]*)*"|'[^'\\]*(?:\\.[^'\\]*)*'|[A-Za-z][A-Za-z0-9_-]*)/gi

export interface QueryValidationResult {
  errors: string[]
  warnings: string[]
}

interface TokenRange {
  start: number
  end: number
  text: string
}

function uniqueCaseInsensitive(values: string[]): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const value of values) {
    const trimmed = value.trim()
    if (!trimmed) continue
    const key = trimmed.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    out.push(trimmed)
  }
  return out
}

function extractLeadingWord(operation: string): string {
  const match = operation.match(/[A-Za-z][A-Za-z0-9_-]*/)
  return match ? match[0] : ''
}

function buildSuggestionDictionary(syntax: SyntaxResponse | null): string[] {
  const fromOperators = syntax?.supportedOperators ?? []
  const fromPipeline = (syntax?.supportedPipelineOperations ?? [])
    .map(extractLeadingWord)
    .filter(Boolean)
  const nodeNames = syntax?.supportedNodeNames ?? []
  const attributeNames = syntax?.supportedAttributeNames ?? []
  const nodeAliases = nodeNames.map(toClassLikeNodeName)
  const fromExamples: string[] = []
  for (const example of syntax?.examples ?? []) {
    const matches = example.match(/[A-Za-z][A-Za-z0-9_-]*/g)
    if (!matches) continue
    for (const token of matches) {
      if (token.length >= 3) {
        fromExamples.push(token)
      }
    }
  }
  return uniqueCaseInsensitive([
    ...START_KEYWORDS,
    ...CLAUSE_KEYWORDS,
    ...LIST_TYPES,
    ...SCOPE_KEYWORDS,
    ...PIPELINE_KEYWORDS,
    ...NODE_HINTS,
    ...ATTRIBUTE_HINTS,
    ...nodeNames,
    ...nodeAliases,
    ...attributeNames,
    ...fromOperators,
    ...fromPipeline,
    ...fromExamples,
  ])
}

function toClassLikeNodeName(nodeName: string): string {
  const raw = nodeName.trim()
  if (!raw) return ''
  return raw
    .split('-')
    .map((part) => (part ? part.charAt(0).toUpperCase() + part.slice(1) : ''))
    .join('')
}

function getWordRangeAtCursor(query: string, cursor: number): TokenRange {
  const safeCursor = Math.max(0, Math.min(cursor, query.length))
  let start = safeCursor
  let end = safeCursor

  while (start > 0 && WORD_REGEX.test(query.charAt(start - 1))) {
    start -= 1
  }
  while (end < query.length && WORD_REGEX.test(query.charAt(end))) {
    end += 1
  }

  return {
    start,
    end,
    text: query.slice(start, end),
  }
}

export function getQuerySuggestions(
  query: string,
  cursor: number,
  syntax: SyntaxResponse | null,
): string[] {
  const dictionary = buildSuggestionDictionary(syntax)
  const wordRange = getWordRangeAtCursor(query, cursor)
  const prefix = wordRange.text.trim()
  const lowerPrefix = prefix.toLowerCase()

  if (lowerPrefix) {
    return dictionary
      .filter((candidate) => candidate.toLowerCase().startsWith(lowerPrefix))
      .filter((candidate) => candidate.toLowerCase() !== lowerPrefix)
      .slice(0, 8)
  }

  return []
}

export function applySuggestionAtCursor(
  query: string,
  cursor: number,
  suggestion: string,
): { query: string; nextCursor: number } {
  const range = getWordRangeAtCursor(query, cursor)
  const before = query.slice(0, range.start)
  const after = query.slice(range.end)
  const needsLeadingSpace = before.length > 0 && !/\s|\(|\[/.test(before.charAt(before.length - 1))
  const needsTrailingSpace = after.length > 0 && !/\s|\)|\]|=|,|\|/.test(after.charAt(0))

  const insertValue = `${needsLeadingSpace ? ' ' : ''}${suggestion}${needsTrailingSpace ? ' ' : ''}`
  const nextQuery = `${before}${insertValue}${after}`
  const nextCursor = before.length + insertValue.length
  return { query: nextQuery, nextCursor }
}

function findClosestKeyword(token: string): string | null {
  let best: string | null = null
  let bestDistance = Number.MAX_SAFE_INTEGER
  for (const keyword of VALID_KEYWORDS) {
    if (Math.abs(keyword.length - token.length) > 2) continue
    const distance = levenshtein(token, keyword)
    if (distance < bestDistance) {
      bestDistance = distance
      best = keyword
    }
  }
  return bestDistance <= 2 ? best : null
}

function levenshtein(left: string, right: string): number {
  const rows = left.length + 1
  const cols = right.length + 1
  const matrix: number[][] = Array.from({ length: rows }, () => Array(cols).fill(0))
  for (let i = 0; i < rows; i += 1) matrix[i][0] = i
  for (let j = 0; j < cols; j += 1) matrix[0][j] = j

  for (let i = 1; i < rows; i += 1) {
    for (let j = 1; j < cols; j += 1) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1
      matrix[i][j] = Math.min(
        matrix[i - 1][j] + 1,
        matrix[i][j - 1] + 1,
        matrix[i - 1][j - 1] + cost,
      )
    }
  }
  return matrix[rows - 1][cols - 1]
}

function validatePairs(query: string): string[] {
  let singleQuoteOpen = false
  let doubleQuoteOpen = false
  let bracketDepth = 0
  let parenDepth = 0

  for (let i = 0; i < query.length; i += 1) {
    const ch = query.charAt(i)
    if (ch === "'" && !doubleQuoteOpen) {
      singleQuoteOpen = !singleQuoteOpen
      continue
    }
    if (ch === '"' && !singleQuoteOpen) {
      doubleQuoteOpen = !doubleQuoteOpen
      continue
    }
    if (singleQuoteOpen || doubleQuoteOpen) continue

    if (ch === '[') bracketDepth += 1
    else if (ch === ']') bracketDepth -= 1
    else if (ch === '(') parenDepth += 1
    else if (ch === ')') parenDepth -= 1
  }

  const errors: string[] = []
  if (singleQuoteOpen || doubleQuoteOpen) {
    errors.push('Niedomkniety cudzyslow lub apostrof w zapytaniu.')
  }
  if (bracketDepth !== 0) {
    errors.push('Niedomkniety nawias kwadratowy [] w zapytaniu.')
  }
  if (parenDepth !== 0) {
    errors.push('Niedomkniety nawias okragly () w zapytaniu.')
  }
  return errors
}

function buildKnownValidationTokens(syntax: SyntaxResponse | null): Set<string> {
  const supportedPipelineOperations = (syntax?.supportedPipelineOperations ?? [])
    .map(extractLeadingWord)
    .filter(Boolean)
  const supportedNodeNames = syntax?.supportedNodeNames ?? []

  return new Set(
    [
      ...VALID_KEYWORDS,
      ...NODE_HINTS,
      ...supportedNodeNames,
      ...supportedNodeNames.map(toClassLikeNodeName),
      ...(syntax?.supportedAttributeNames ?? []),
      ...(syntax?.supportedOperators ?? []),
      ...supportedPipelineOperations,
    ].map((token) => token.toLowerCase()),
  )
}

function validateKeywordTypos(query: string, syntax: SyntaxResponse | null): string[] {
  const warnings: string[] = []
  const matches = query.match(/[A-Za-z][A-Za-z0-9_-]*/g) ?? []
  const seen = new Set<string>()
  const knownTokens = buildKnownValidationTokens(syntax)

  for (const rawToken of matches) {
    const token = rawToken.toLowerCase()
    if (seen.has(token) || knownTokens.has(token)) {
      continue
    }
    // Skip likely node/attribute names and article identifiers.
    if (rawToken.includes('-') || /[A-Z]/.test(rawToken.slice(1)) || /[0-9_]/.test(rawToken)) {
      continue
    }
    if (token.length < 4) {
      continue
    }
    const closest = findClosestKeyword(token)
    if (!closest) {
      continue
    }
    seen.add(token)
    warnings.push(`Mozliwa literowka: "${rawToken}" (czy chodzilo o "${closest}"?)`)
    if (warnings.length >= 4) {
      break
    }
  }
  return warnings
}

function stripOuterQuotes(raw: string): string {
  const text = raw.trim()
  if (text.length < 2) return text
  const first = text.charAt(0)
  const last = text.charAt(text.length - 1)
  if ((first === "'" && last === "'") || (first === '"' && last === '"')) {
    return text
      .slice(1, -1)
      .replace(/\\'/g, "'")
      .replace(/\\"/g, '"')
      .replace(/\\\\/g, '\\')
  }
  return text
}

function normalizeNodeNameLikeBackend(raw: string): string {
  let normalized = stripOuterQuotes(raw).trim()
  if (!normalized) return ''
  normalized = normalized.replaceAll('_', '-')
  if (!normalized.includes('-')) {
    normalized = normalized
      .replaceAll(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
      .replaceAll(/([a-z0-9])([A-Z])/g, '$1-$2')
  }
  return normalized.toLowerCase()
}

function extractNodeNamesAfterHas(query: string): string[] {
  const out: string[] = []
  for (const match of query.matchAll(HAS_NODE_REGEX)) {
    const candidate = match[1]
    if (candidate) {
      out.push(candidate)
    }
  }
  return out
}

function findClosestNodeName(rawNodeName: string, supportedNodeNames: string[]): string | null {
  const normalizedRaw = normalizeNodeNameLikeBackend(rawNodeName)
  let best: string | null = null
  let bestDistance = Number.MAX_SAFE_INTEGER
  for (const nodeName of supportedNodeNames) {
    const normalizedCandidate = normalizeNodeNameLikeBackend(nodeName)
    if (Math.abs(normalizedCandidate.length - normalizedRaw.length) > 3) continue
    const distance = levenshtein(normalizedRaw, normalizedCandidate)
    if (distance < bestDistance) {
      bestDistance = distance
      best = nodeName
    }
  }
  return bestDistance <= 3 ? best : null
}

function validateKnownNodeNames(query: string, syntax: SyntaxResponse | null): string[] {
  const supportedNodeNames = syntax?.supportedNodeNames ?? []
  if (supportedNodeNames.length === 0) {
    return []
  }

  const normalizedSupported = new Set<string>(
    supportedNodeNames.map((name) => normalizeNodeNameLikeBackend(name)),
  )
  const errors: string[] = []
  const seen = new Set<string>()

  for (const rawNodeName of extractNodeNamesAfterHas(query)) {
    const normalizedNode = normalizeNodeNameLikeBackend(rawNodeName)
    if (!normalizedNode || seen.has(normalizedNode)) {
      continue
    }
    seen.add(normalizedNode)
    if (normalizedSupported.has(normalizedNode)) {
      continue
    }
    const closest = findClosestNodeName(rawNodeName, supportedNodeNames)
    if (closest) {
      errors.push(`Nieznana nazwa noda: "${rawNodeName}". Mozliwe, ze chodzilo o "${closest}".`)
    } else {
      errors.push(`Nieznana nazwa noda: "${rawNodeName}".`)
    }
    if (errors.length >= 4) {
      break
    }
  }

  return errors
}

export function validateQueryText(
  query: string,
  syntax: SyntaxResponse | null,
): QueryValidationResult {
  if (!query.trim()) {
    return { errors: [], warnings: [] }
  }
  const structuralErrors = validatePairs(query)
  const nodeErrors = validateKnownNodeNames(query, syntax)
  return {
    errors: [...structuralErrors, ...nodeErrors],
    warnings: validateKeywordTypos(query, syntax),
  }
}
