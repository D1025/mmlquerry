import type { SyntaxResponse } from './queryApi'

const START_KEYWORDS = ['list', 'article']
const SCOPE_KEYWORDS = ['item', 'proposition']
const CLAUSE_KEYWORDS = ['of', 'in', 'where', 'has', 'and', 'or', 'butnot', 'not']
const LIST_TYPES = ['theorem', 'definition', 'statement', 'registration', 'symbol', 'all']
const SYMBOL_QUERY_KEYWORDS = ['occurrences', 'symbols']
const REDEFINE_KEYWORDS = ['redefine', 'redefined', 'true', 'false', 'both']
const NEGATED_ADJECTIVE_KEYWORDS = ['negated', 'adjective']
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
  'nodes',
  'reverse',
  'invert',
  'whereeq',
  'wherege',
  'wherele',
  'wheregt',
  'wherelt',
  'numeq',
  'numge',
  'numle',
  'numgt',
  'numlt',
  'number',
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
    ...SYMBOL_QUERY_KEYWORDS,
    ...REDEFINE_KEYWORDS,
    ...NEGATED_ADJECTIVE_KEYWORDS,
    ...PIPELINE_KEYWORDS,
    ...ATTRIBUTE_HINTS,
  ].map((token) => token.toLowerCase()),
)

const WORD_REGEX = /[A-Za-z0-9_-]/
const UNQUOTED_NODE_WITH_PATH_REGEX =
  '[A-Za-z][A-Za-z0-9_-]*(?:(?:\\/\\/|\\/[0-9]+\\/|\\/)[A-Za-z][A-Za-z0-9_-]*)*'
const HAS_NODE_REGEX =
  new RegExp(
    `\\bhas\\s+(?:not\\s+)?("[^"\\\\]*(?:\\\\.[^"\\\\]*)*"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|${UNQUOTED_NODE_WITH_PATH_REGEX})`,
    'gi',
  )
const NODES_SELECTOR_REGEX =
  new RegExp(
    `\\bnodes\\s+("[^"\\\\]*(?:\\\\.[^"\\\\]*)*"|'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'|${UNQUOTED_NODE_WITH_PATH_REGEX})`,
    'gi',
  )
const LIST_TYPE_KEYWORDS = new Set(['theorem', 'theorems', 'definition', 'definitions', 'statement', 'statements', 'registration', 'registrations', 'symbol', 'symbols', 'all'])
const CONNECTOR_KEYWORDS = new Set(['and', 'or', 'butnot'])
const DISALLOWED_AFTER_KEYWORD = new Set(['and', 'or', 'butnot', 'where', 'in', 'of', 'has', '|'])
const DISALLOWED_AFTER_WHERE = new Set(['and', 'or', 'butnot', 'where', 'in', 'of', '|'])
const SYNTAX_STRING_PLACEHOLDER = 'stringliteral'

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
    ...SYMBOL_QUERY_KEYWORDS,
    ...REDEFINE_KEYWORDS,
    ...NEGATED_ADJECTIVE_KEYWORDS,
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
    errors.push('Niedomknięty cudzysłów lub apostrof w zapytaniu.')
  }
  if (bracketDepth !== 0) {
    errors.push('Niedomknięty nawias kwadratowy [] w zapytaniu.')
  }
  if (parenDepth !== 0) {
    errors.push('Niedomknięty nawias okrągły () w zapytaniu.')
  }
  return errors
}

function extractWordTokens(query: string): string[] {
  const sanitized = query.replace(
    /'([^'\\]|\\.)*'|"([^"\\]|\\.)*"/g,
    ' ',
  )
  return sanitized.match(/[A-Za-z][A-Za-z0-9_-]*/g) ?? []
}

function extractSyntaxShapeTokens(query: string): string[] {
  const sanitized = query.replace(
    /'([^'\\]|\\.)*'|"([^"\\]|\\.)*"/g,
    ` ${SYNTAX_STRING_PLACEHOLDER} `,
  )
  return sanitized.match(/[A-Za-z][A-Za-z0-9_-]*/g) ?? []
}

function validateSyntaxShape(query: string): string[] {
  const errors: string[] = []
  const trimmed = query.trim()
  if (!trimmed) {
    return errors
  }

  if (/^\|/.test(trimmed) || /\|$/.test(trimmed)) {
    errors.push('Znak "|" nie może być na początku ani na końcu zapytania.')
  }
  if (/\|\s*\|/.test(trimmed)) {
    errors.push('Wykryto podwojony operator pipeline "||".')
  }

  const tokens = extractSyntaxShapeTokens(trimmed).map((token) => token.toLowerCase())
  if (tokens.length === 0) {
    return errors
  }

  const first = tokens[0]
  if (first === 'list') {
    if (tokens[1] !== 'of') {
      errors.push('Po "list" oczekiwano słowa "of".')
    }
    if (!tokens[2]) {
      errors.push('Brakuje typu listy po "list of".')
    } else if (!LIST_TYPE_KEYWORDS.has(tokens[2])) {
      errors.push(`Nieznany typ listy: "${tokens[2]}".`)
    }
  } else if (first === 'occurrences') {
    if (tokens[1] !== 'of') {
      errors.push('Po "occurrences" oczekiwano słowa "of".')
    }
    if (!tokens[2] || !['symbol', 'symbols'].includes(tokens[2])) {
      errors.push('Zapytanie "occurrences" wymaga frazy "occurrences of symbols".')
    }
  } else if (first !== 'article') {
    errors.push('Zapytanie powinno zaczynać się od "list", "occurrences" albo "article".')
  }

  for (let i = 0; i < tokens.length; i += 1) {
    const token = tokens[i]
    const next = tokens[i + 1]
    const prev = tokens[i - 1]

    if (CONNECTOR_KEYWORDS.has(token)) {
      if (!next || !prev || CONNECTOR_KEYWORDS.has(next) || CONNECTOR_KEYWORDS.has(prev)) {
        errors.push(`Operator "${token}" jest użyty w niepoprawnym miejscu.`)
      }
    }

    if (token === 'where' || token === 'has' || token === 'nodes' || token === 'in') {
      if (!next) {
        errors.push(`Po "${token}" brakuje dalszej części zapytania.`)
      } else if (
        (token === 'where' && DISALLOWED_AFTER_WHERE.has(next))
        || (token !== 'where' && DISALLOWED_AFTER_KEYWORD.has(next))
      ) {
        errors.push(`Po "${token}" oczekiwano wyrażenia, a znaleziono "${next}".`)
      }
    }
  }

  return Array.from(new Set(errors)).slice(0, 6)
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
  const matches = extractWordTokens(query)
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
    warnings.push(`Możliwa literówka: "${rawToken}" (czy chodziło o "${closest}"?)`)
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

function hasUnescapedWildcard(raw: string): boolean {
  let escaped = false
  for (const ch of raw) {
    if (escaped) {
      escaped = false
      continue
    }
    if (ch === '\\') {
      escaped = true
      continue
    }
    if (ch === '*' || ch === '_') {
      return true
    }
  }
  return false
}

function normalizeNodeNameLikeBackend(raw: string): string {
  const stripped = stripOuterQuotes(raw).trim()
  if (!stripped) return ''

  const parsedPath = parseNodePathExpression(stripped)
  if (parsedPath) {
    let out = normalizeSingleNodeNameLikeBackend(parsedPath.segments[0] ?? '')
    for (let i = 0; i < parsedPath.steps.length; i += 1) {
      out += formatPathStep(parsedPath.steps[i] ?? { kind: 'direct' })
      out += normalizeSingleNodeNameLikeBackend(parsedPath.segments[i + 1] ?? '')
    }
    return out
  }

  return normalizeSingleNodeNameLikeBackend(stripped)
}

function normalizeSingleNodeNameLikeBackend(raw: string): string {
  let normalized = raw.trim()
  if (!normalized) return ''
  const containsWildcard = hasUnescapedWildcard(normalized)
  if (!containsWildcard) {
    normalized = normalized.replaceAll('_', '-')
  }
  if (!containsWildcard && !normalized.includes('-')) {
    normalized = normalized
      .replaceAll(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
      .replaceAll(/([a-z0-9])([A-Z])/g, '$1-$2')
  }
  if (
    !containsWildcard
    && !normalized.includes('-')
    && normalized === normalized.toLowerCase()
  ) {
    normalized = insertLowercaseNodeSuffixHyphen(normalized)
  }
  return normalized.toLowerCase()
}

interface ParsedNodePath {
  segments: string[]
  steps: Array<{ kind: 'direct' | 'any' | 'exact'; depth?: number }>
}

function parseNodePathExpression(raw: string): ParsedNodePath | null {
  const text = raw.trim()
  if (!text || !text.includes('/')) {
    return null
  }

  const segments: string[] = []
  const steps: Array<{ kind: 'direct' | 'any' | 'exact'; depth?: number }> = []
  let index = 0

  while (index < text.length) {
    const slashIndex = text.indexOf('/', index)
    if (slashIndex < 0) {
      const tail = text.slice(index).trim()
      if (!tail) return null
      segments.push(tail)
      break
    }

    const segment = text.slice(index, slashIndex).trim()
    if (!segment) return null
    segments.push(segment)

    if (slashIndex + 1 < text.length && text.charAt(slashIndex + 1) === '/') {
      steps.push({ kind: 'any' })
      index = slashIndex + 2
      continue
    }

    let cursor = slashIndex + 1
    const digitsStart = cursor
    while (cursor < text.length && /[0-9]/.test(text.charAt(cursor))) {
      cursor += 1
    }
    if (cursor > digitsStart && cursor < text.length && text.charAt(cursor) === '/') {
      const depth = Number.parseInt(text.slice(digitsStart, cursor), 10)
      if (!Number.isFinite(depth) || depth <= 0) return null
      steps.push({ kind: 'exact', depth })
      index = cursor + 1
      continue
    }

    steps.push({ kind: 'direct' })
    index = slashIndex + 1
  }

  if (segments.length < 2 || steps.length !== segments.length - 1) {
    return null
  }
  return { segments, steps }
}

function formatPathStep(step: { kind: 'direct' | 'any' | 'exact'; depth?: number }): string {
  if (step.kind === 'any') return '//'
  if (step.kind === 'exact') return `/${step.depth ?? 1}/`
  return '/'
}

function insertLowercaseNodeSuffixHyphen(value: string): string {
  if (!value || value.includes('-')) return value
  const suffixes = [
    'proposition',
    'registration',
    'definition',
    'reference',
    'statement',
    'structure',
    'attribute',
    'relation',
    'function',
    'arguments',
    'argument',
    'selector',
    'adjective',
    'variable',
    'variables',
    'pattern',
    'formula',
    'cluster',
    'functor',
    'numeral',
    'term',
    'type',
    'mode',
    'item',
    'block',
  ]
  for (const suffix of suffixes) {
    if (value.length <= suffix.length + 1) continue
    if (value.endsWith(suffix)) {
      const prefix = value.slice(0, value.length - suffix.length)
      if (prefix) {
        return `${prefix}-${suffix}`
      }
    }
  }
  return value
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

function extractNodeNamesAfterNodes(query: string): string[] {
  const out: string[] = []
  for (const match of query.matchAll(NODES_SELECTOR_REGEX)) {
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

  const normalizedSupported = new Set<string>(supportedNodeNames.map((name) => normalizeNodeNameLikeBackend(name)))
  const errors: string[] = []
  const seen = new Set<string>()
  const rawCandidates = [...extractNodeNamesAfterHas(query), ...extractNodeNamesAfterNodes(query)]

  for (const rawNodeName of rawCandidates) {
    const stripped = stripOuterQuotes(rawNodeName)
    const path = parseNodePathExpression(stripped)
    const parts = path ? path.segments : [stripped]

    for (const part of parts) {
      const lowered = part.toLowerCase()
      if (lowered === 'negated') {
        continue
      }
      if (hasUnescapedWildcard(part)) {
        continue
      }
      const normalizedNode = normalizeNodeNameLikeBackend(part)
      if (!normalizedNode || seen.has(normalizedNode)) {
        continue
      }
      seen.add(normalizedNode)
      if (normalizedSupported.has(normalizedNode)) {
        continue
      }
      const closest = findClosestNodeName(part, supportedNodeNames)
      if (closest) {
        errors.push(`Nieznana nazwa noda: "${part}". Możliwe, że chodziło o "${closest}".`)
      } else {
        errors.push(`Nieznana nazwa noda: "${part}".`)
      }
      if (errors.length >= 4) {
        break
      }
    }
    if (errors.length >= 4) break
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
  const syntaxShapeErrors = validateSyntaxShape(query)
  const nodeErrors = validateKnownNodeNames(query, syntax)
  return {
    errors: [...structuralErrors, ...syntaxShapeErrors, ...nodeErrors],
    warnings: validateKeywordTypos(query, syntax),
  }
}
