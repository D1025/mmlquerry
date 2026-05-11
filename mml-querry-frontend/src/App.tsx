import {
  Fragment,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from 'react'
import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded'
import KeyboardArrowUpRoundedIcon from '@mui/icons-material/KeyboardArrowUpRounded'
import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import SearchRoundedIcon from '@mui/icons-material/SearchRounded'
import TerminalRoundedIcon from '@mui/icons-material/TerminalRounded'
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  AppBar,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Collapse,
  Container,
  Grid,
  IconButton,
  InputAdornment,
  LinearProgress,
  List,
  ListItemButton,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  Tabs,
  TextField,
  Toolbar,
  Tooltip,
  Typography,
} from '@mui/material'
import { useAppDispatch, useAppSelector } from './app/hooks'
import {
  executeQuery,
  fetchItemFragment,
  type ExecuteQueryRequest,
  type ExecuteQueryResponse,
  type QueryItem,
} from './features/query/queryApi'
import {
  applySuggestionAtCursor,
  getQuerySuggestions,
  validateQueryText,
} from './features/query/queryAssist'
import { fetchSyntax, runQuery, setQueryResult, setQueryText } from './features/query/querySlice'

type SortDirection = 'asc' | 'desc'

interface ColumnDef {
  key: string
  label: string
}

interface RowEntry {
  item: QueryItem
  index: number
  rowKey: string
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

const SUGGESTION_LIST_ID = 'query-suggestion-list'
const SUGGESTION_OPTION_ID_PREFIX = 'query-suggestion-option'
const SUGGESTION_MENU_WIDTH = 280
const PRELOAD_AHEAD_PAGES = 2
const PRELOAD_BEHIND_PAGES = 2
const MAX_CACHED_PAGES_PER_KEY = 24
const WORD_CHAR_REGEX = /[A-Za-z0-9_-]/
const QUERY_PLACEHOLDER =
  "Np. list of definition | nodes Item where redefine true and has *[spelling='Noetherian']"

interface SuggestionPosition {
  top: number
  left: number
}

type AppPage = 'editor' | 'examples'

interface ExampleQueryDefinition {
  id: string
  title: string
  description: string
  query: string
}

const EXAMPLE_QUERY_LIBRARY: ExampleQueryDefinition[] = [
  {
    id: 'thesis-in-theorem',
    title: 'Tezy w twierdzeniach',
    description: 'Wyszukuje twierdzenia, których propozycja zawiera nod Thesis.',
    query: 'list of theorem where proposition has Thesis',
  },
  {
    id: 'infix-term-by-spelling',
    title: 'InfixTerm po spelling',
    description: "Filtruje twierdzenia po konkretnym spelling w nodzie InfixTerm.",
    query: "list of theorem where proposition has InfixTerm[spelling='Element']",
  },
  {
    id: 'infix-term-by-spelling-shorthand',
    title: 'InfixTerm po spelling (skrot)',
    description:
      "Skrot zapisu: proposition has InfixTerm spelling 'Element' zamiast [spelling='Element'].",
    query: "list of theorem where proposition has InfixTerm spelling 'Element'",
  },
  {
    id: 'infix-term-negated',
    title: 'Negacja noda w where',
    description:
      "Negacja typu noda XML w warunku where: has not InfixTerm[spelling='Element'].",
    query: "list of theorem where proposition has not InfixTerm[spelling='Element']",
  },
  {
    id: 'infix-term-two-patterns',
    title: 'Dwa wzorce w jednej propozycji',
    description:
      'Szuka twierdzeń, gdzie w tej samej propozycji występują dwa różne absolutepatternmmlid.',
    query:
      "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm[absolutepatternmmlid='XBOOLE_0:2']",
  },
  {
    id: 'statements-plus-and-mul-by-spelling',
    title: 'Stwierdzenia z + i * (spelling)',
    description:
      "Wyszukuje stwierdzenia, ktore jednoczesnie zawieraja dodawanie '+' i mnozenie '*' po skrocie spelling (dla '*' uzyte jest escape: \\*).",
    query:
      "list of statement where proposition has InfixTerm spelling '+' and proposition has InfixTerm spelling '\\*'",
  },
  {
    id: 'statements-plus-and-mul-by-patternid',
    title: 'Stwierdzenia z + i * (pattern id)',
    description:
      "Wyszukuje stwierdzenia jednoczesnie po absolutepatternmmlid dla '+' i '*' (XCMPLX_0:4 / XCMPLX_0:5).",
    query:
      "list of statement where proposition has InfixTerm[absolutepatternmmlid='XCMPLX_0:4'] and proposition has InfixTerm[absolutepatternmmlid='XCMPLX_0:5']",
  },
  {
    id: 'negation-butnot',
    title: 'Negacja przez butnot',
    description:
      'Różnica zbiorów: bierze wynik lewy i usuwa z niego wszystko, co zwraca prawa strona.',
    query: 'list of theorem butnot list of definition',
  },
  {
    id: 'negation-partial',
    title: 'Negacja fragmentu (and not)',
    description:
      'Neguje tylko prawą część warunku: element musi spełniać pierwszy warunek i nie spełniać drugiego.',
    query: "list of theorem and not (list of theorem where proposition has Thesis)",
  },
  {
    id: 'attribute-redefine',
    title: 'Attribute-Definition z Redefine',
    description:
      'Wyszukuje definicje, w których item ma Redefine[occurs=true] oraz pattern z określonym spelling.',
    query:
      "list of definition where item has Redefine[occurs='true'] and item has AttributePattern[spelling='Noetherian']",
  },
  {
    id: 'nodes-redefine-shorthand',
    title: 'Pipeline nodes + redefine',
    description:
      'Wersja pipeline: wybiera nody Item i filtruje potomków przez skrót redefine true.',
    query: "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']",
  },
  {
    id: 'nodes-spelling-shorthand',
    title: 'Pipeline nodes + spelling (skrot)',
    description:
      "Skrot zapisu spelling: has * spelling 'Noetherian' oraz wariant globalny: spelling 'Noetherian'.",
    query: "list of definition | nodes Item where has * spelling 'Noetherian'",
  },
  {
    id: 'nodes-has-any-not-spelling',
    title: 'Pipeline nodes + has * not spelling',
    description:
      "Negacja spelling przypieta do predykatu has: has * not spelling 'Noetherian'.",
    query: "list of definition | nodes Item where has * not spelling 'Noetherian'",
  },
  {
    id: 'nodes-not-spelling',
    title: 'Pipeline nodes + not spelling',
    description:
      "Negacja spelling w where: odfiltrowuje nody, dla ktorych istnieje spelling 'Noetherian'.",
    query: "list of definition | nodes Item where not spelling 'Noetherian'",
  },
  {
    id: 'nodes-not-has',
    title: 'Pipeline nodes + not has',
    description:
      "Negacja typu noda: not has / has not, np. brak Redefine[occurs='true'] wsrod potomkow.",
    query: "list of definition | nodes Item where not has Redefine[occurs='true']",
  },
  {
    id: 'symbols-unique',
    title: 'Lista symboli',
    description: 'Zwraca listę nodów symboli (*-Pattern) jako rekordy, analogicznie do innych list.',
    query: 'list of symbols',
  },
  {
    id: 'symbols-by-spelling-plus',
    title: 'Symbole po spelling',
    description: "Filtruje listę symboli po spelling, np. tylko dodawanie '+'.",
    query: "list of symbols where spelling '+'",
  },
  {
    id: 'symbols-occurrences',
    title: 'Wystąpienia symboli',
    description:
      'Grupuje symbole po spelling i liczy ile razy wystąpiły (niezależnie od pochodzenia noda).',
    query: 'occurrences of symbols',
  },
  {
    id: 'symbols-literal-wildcards',
    title: 'Literalne * i _',
    description:
      'Wildcard to * oraz _. Aby szukac doslownie tych znakow, uzyj escape: \\* oraz \\_.',
    query: "occurrences of symbols | filter('spelling=A\\*B\\_C')",
  },
  {
    id: 'cardinality-ref',
    title: 'Minimalna liczba referencji',
    description: 'Filtruje listę twierdzeń do rekordów z co najmniej 2 wynikami operacji ref.',
    query: 'list of theorem | wherege(ref,2)',
  },
]

function normalizeKeywordToken(token: string): string {
  return token.trim().toLowerCase().replace(/\s+/g, ' ')
}

const KEYWORD_HELP: Record<string, string> = {
  and: 'Operator logiczny: wynik wspólny dwóch zapytań.',
  or: 'Operator logiczny: suma wyników dwóch zapytań.',
  butnot: 'Operator logiczny: elementy z lewego zapytania, których nie ma w prawym.',
  not: 'Negacja zapytania: usuwa elementy spełniające warunek.',
  ref: 'Pipeline: przechodzi do elementów referencjonowanych.',
  occur: 'Pipeline: przechodzi do wystąpień/powiązań occurrence.',
  definition: 'Pipeline: przechodzi do definicji powiązanych z wynikiem.',
  notation: 'Pipeline: przechodzi po relacjach notacyjnych.',
  redef: 'Pipeline: filtr/przejście po relacji redefinicji.',
  origin: 'Pipeline: wraca do oryginalnego elementu (origin).',
  copy: 'Pipeline: przechodzi do kopii elementu.',
  'termtype ref': 'Pipeline: relacja termtype + ref.',
  'deftype ref': 'Pipeline: relacja deftype + ref.',
  'main mode': 'Pipeline: przechodzi do głównego mode.',
  'main functor': 'Pipeline: przechodzi do głównego funktora.',
  nodes: 'Pipeline: wybiera nody XML i pozwala filtrować potomków przez where/has/redefine.',
  reverse: 'Pipeline: odwraca kolejność wyników.',
  invert: 'Pipeline: odwraca kolejność wyników (alias reverse).',
  whereeq: 'Pipeline: filtruje po dokładnej liczbie wyników operacji, np. whereeq(ref,2).',
  wherege: 'Pipeline: filtruje po liczbie >= N, np. wherege(ref,2).',
  wherele: 'Pipeline: filtruje po liczbie <= N.',
  wheregt: 'Pipeline: filtruje po liczbie > N.',
  wherelt: 'Pipeline: filtruje po liczbie < N.',
  spelling: 'Atrybut XML: tekstowy zapis symbolu/patternu.',
  occurs: 'Atrybut XML: flaga, czy element występuje/redefiniuje.',
  xmlid: 'Atrybut XML: identyfikator elementu w dokumencie.',
  position: 'Atrybut XML: pozycja elementu w tekście źródłowym.',
}

function describeKeyword(token: string, type: 'operator' | 'pipeline' | 'attribute' | 'node'): string {
  const normalized = normalizeKeywordToken(token)
  const known = KEYWORD_HELP[normalized]
  if (known) {
    return known
  }

  if (type === 'node') {
    return `Nod XML "${token}". Wstawia nazwę noda do zapytania (has/nodes).`
  }
  if (type === 'attribute') {
    return `Atrybut XML "${token}". Użyj np. w filtrze [${token}='wartość'].`
  }
  if (type === 'pipeline') {
    return `Operacja pipeline "${token}". Wstaw po znaku |, aby przekształcić bieżący wynik.`
  }
  return `Słowo kluczowe "${token}" używane w składni zapytań.`
}

function resolvePageFromPath(pathname: string): AppPage {
  return pathname.toLowerCase() === 'examples' ? 'examples' : 'editor'
}

function normalizeRequestString(raw: string | undefined): string {
  return (raw ?? '').trim()
}

function normalizeSortDirectionValue(raw: string | undefined): SortDirection {
  return normalizeRequestString(raw).toLowerCase() === 'desc' ? 'desc' : 'asc'
}

function buildRequestCacheBaseKey(request: ExecuteQueryRequest): string {
  return JSON.stringify({
    query: normalizeRequestString(request.query),
    size: request.size ?? 10,
    sortBy: normalizeRequestString(request.sortBy).toLowerCase(),
    sortDirection: normalizeSortDirectionValue(request.sortDirection),
    filter: normalizeRequestString(request.filter).toLowerCase(),
  })
}

function buildRequestCachePageKey(request: ExecuteQueryRequest): string {
  return `${buildRequestCacheBaseKey(request)}::${request.page ?? 0}`
}

function buildCanonicalRequestFromResponse(
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

function buildRowKey(item: QueryItem, index: number): string {
  const primaryKey = item.node_id ?? item.item_id ?? item.spelling ?? ''
  return `${String(primaryKey)}|${index}`
}

function hasValue(value: unknown): boolean {
  return String(value ?? '').trim().length > 0
}

function deriveTableColumns(items: QueryItem[]): ColumnDef[] {
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

function getResultTabProps(index: number) {
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

function getSuggestionPosition(
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

function App() {
  const dispatch = useAppDispatch()

  const [pageRoute, setPageRoute] = useState<AppPage>(() =>
    resolvePageFromPath(window.location.hash.replace(/^#\/?/, '')),
  )
  const [tab, setTab] = useState(0)
  const [filterText, setFilterText] = useState('')
  const [sortColumn, setSortColumn] = useState<string>('lib_id')
  const [sortDirection, setSortDirection] = useState<SortDirection>('asc')
  const [page, setPage] = useState(0)
  const [rowsPerPage, setRowsPerPage] = useState(10)
  const [expandedRows, setExpandedRows] = useState<Record<string, boolean>>({})
  const [expandedRawByRowKey, setExpandedRawByRowKey] = useState<Record<string, string>>({})
  const [expandedRawLoadingByRowKey, setExpandedRawLoadingByRowKey] = useState<Record<string, boolean>>({})
  const [expandedRawErrorByRowKey, setExpandedRawErrorByRowKey] = useState<Record<string, string>>({})
  const [queryCursor, setQueryCursor] = useState(0)
  const [queryFocused, setQueryFocused] = useState(false)
  const [selectedSuggestionIndex, setSelectedSuggestionIndex] = useState(0)
  const [suggestionPosition, setSuggestionPosition] = useState<SuggestionPosition | null>(null)
  const [pendingEditorCursor, setPendingEditorCursor] = useState<number | null>(null)
  const [showAllAttributes, setShowAllAttributes] = useState(false)
  const [showAllNodes, setShowAllNodes] = useState(false)

  const queryInputRef = useRef<HTMLTextAreaElement | HTMLInputElement | null>(null)
  const cachedPageResponsesRef = useRef<Record<string, Record<number, ExecuteQueryResponse>>>({})
  const prefetchInFlightRef = useRef<Set<string>>(new Set())

  const {
    queryText,
    syntax,
    result,
    syntaxStatus,
    executeStatus,
    syntaxError,
    executeError,
  } = useAppSelector((state) => state.query)

  useEffect(() => {
    if (syntaxStatus === 'idle') {
      void dispatch(fetchSyntax())
    }
  }, [dispatch, syntaxStatus])

  useEffect(() => {
    const onHashChange = () => {
      setPageRoute(resolvePageFromPath(window.location.hash.replace(/^#\/?/, '')))
    }
    window.addEventListener('hashchange', onHashChange)
    return () => {
      window.removeEventListener('hashchange', onHashChange)
    }
  }, [])

  const tableColumns = useMemo<ColumnDef[]>(
    () => deriveTableColumns(result?.items ?? []),
    [result?.items],
  )

  const canExpandRows = useMemo(
    () =>
      (result?.items ?? []).some((item) => hasValue(item.item_id) || hasValue(item.node_id)),
    [result?.items],
  )

  useEffect(() => {
    if (tableColumns.length === 0) {
      return
    }
    if (!tableColumns.some((column) => column.key === sortColumn)) {
      setSortColumn(tableColumns[0].key)
      setSortDirection('asc')
    }
  }, [sortColumn, tableColumns])

  useEffect(() => {
    if (!result) {
      return
    }
    if (typeof result.page === 'number' && result.page !== page) {
      setPage(result.page)
    }
    if (typeof result.size === 'number' && result.size !== rowsPerPage) {
      setRowsPerPage(result.size)
    }
  }, [result?.page, result?.size])

  const rowEntries = useMemo<RowEntry[]>(
    () => (result?.items ?? []).map((item, index) => ({ item, index, rowKey: buildRowKey(item, index) })),
    [result?.items],
  )

  const querySuggestions = useMemo(
    () => getQuerySuggestions(queryText, queryCursor, syntax),
    [queryCursor, queryText, syntax],
  )

  const queryValidation = useMemo(() => validateQueryText(queryText, syntax), [queryText, syntax])

  const canExecute = queryText.trim().length > 0 && executeStatus !== 'loading'
  const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'
  const hasQueryErrors = queryValidation.errors.length > 0
  const syntaxStatusLabel =
    syntaxStatus === 'loading'
      ? 'Ladowanie skladni'
      : syntaxStatus === 'failed'
        ? 'API niedostepne'
        : 'Skladnia gotowa'
  const resultStatusLabel = result
    ? `${result.count} wynikow`
    : executeStatus === 'loading'
      ? 'Wykonywanie query'
      : 'Brak wyniku'
  const activeSuggestionIndex =
    querySuggestions.length === 0
      ? 0
      : Math.min(selectedSuggestionIndex, querySuggestions.length - 1)
  const activeSuggestionId = `${SUGGESTION_OPTION_ID_PREFIX}-${activeSuggestionIndex}`
  const isSuggestionListOpen = queryFocused && querySuggestions.length > 0 && Boolean(suggestionPosition)

  const updateSuggestionPosition = useCallback(() => {
    const input = queryInputRef.current
    if (!queryFocused || !input || querySuggestions.length === 0) {
      setSuggestionPosition(null)
      return
    }

    setSuggestionPosition(getSuggestionPosition(input, queryText, queryCursor))
  }, [queryCursor, queryFocused, querySuggestions.length, queryText])

  useEffect(() => {
    updateSuggestionPosition()
  }, [updateSuggestionPosition])

  useEffect(() => {
    if (!queryFocused) {
      return undefined
    }

    window.addEventListener('resize', updateSuggestionPosition)
    window.addEventListener('scroll', updateSuggestionPosition, true)

    return () => {
      window.removeEventListener('resize', updateSuggestionPosition)
      window.removeEventListener('scroll', updateSuggestionPosition, true)
    }
  }, [queryFocused, updateSuggestionPosition])

  const handleQueryCursorUpdate = (event: { target: EventTarget | null }) => {
    const target = event.target as HTMLTextAreaElement | HTMLInputElement | null
    if (!target) {
      return
    }
    setQueryCursor(target.selectionStart ?? target.value.length)
    setSelectedSuggestionIndex(0)
  }

  const handleQueryTextChange = (event: ChangeEvent<HTMLTextAreaElement | HTMLInputElement>) => {
    const nextValue = event.target.value
    const nextCursor = event.target.selectionStart ?? nextValue.length
    dispatch(setQueryText(nextValue))
    setQueryCursor(nextCursor)
    setSelectedSuggestionIndex(0)
  }

  const focusQueryAt = (cursor: number) => {
    requestAnimationFrame(() => {
      const input = queryInputRef.current
      if (!input) return
      input.focus()
      input.setSelectionRange(cursor, cursor)
    })
  }

  const navigateToPage = useCallback((nextPage: AppPage) => {
    const hash = nextPage === 'examples' ? '#/examples' : '#/'
    if (window.location.hash !== hash) {
      window.location.hash = hash
    }
    setPageRoute(nextPage)
  }, [])

  const setQueryAndCursor = (
    nextQuery: string,
    nextCursor: number,
    options?: { focus?: boolean },
  ) => {
    dispatch(setQueryText(nextQuery))
    setQueryCursor(nextCursor)
    setSelectedSuggestionIndex(0)
    if (options?.focus !== false) {
      focusQueryAt(nextCursor)
    }
  }

  const acceptSuggestion = (suggestion: string) => {
    const { query, nextCursor } = applySuggestionAtCursor(queryText, queryCursor, suggestion)
    setQueryAndCursor(query, nextCursor)
  }

  const appendQueryToken = (token: string) => {
    const input = queryInputRef.current
    const cursor = input?.selectionStart ?? queryCursor
    const before = queryText.slice(0, cursor)
    const after = queryText.slice(cursor)
    const needsLeadingSpace = before.length > 0 && !/\s|\(|\[|\|/.test(before.charAt(before.length - 1))
    const needsTrailingSpace = after.length > 0 && !/\s|\)|\]|\||,/.test(after.charAt(0))
    const insertValue = `${needsLeadingSpace ? ' ' : ''}${token}`
    const nextQuery = `${before}${insertValue}${needsTrailingSpace ? ' ' : ''}${after}`
    setQueryAndCursor(nextQuery, before.length + insertValue.length)
  }

  const handleExampleSelect = (example: string) => {
    setQueryAndCursor(example, example.length, { focus: false })
    setPendingEditorCursor(example.length)
    navigateToPage('editor')
  }

  const handleQueryKeyDown = (event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault()
      if (canExecute) {
        handleRunQuery()
      }
      return
    }

    if (querySuggestions.length === 0) {
      return
    }

    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setSelectedSuggestionIndex((current) => (current + 1) % querySuggestions.length)
      return
    }

    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setSelectedSuggestionIndex((current) =>
        current === 0 ? querySuggestions.length - 1 : current - 1,
      )
      return
    }

    if (event.key === 'Tab' && !event.shiftKey) {
      event.preventDefault()
      acceptSuggestion(querySuggestions[activeSuggestionIndex] ?? querySuggestions[0])
    }
  }

  const handleQueryKeyUp = (event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      return
    }
    handleQueryCursorUpdate(event)
  }

  const resetExpandedState = () => {
    setExpandedRows({})
    setExpandedRawByRowKey({})
    setExpandedRawLoadingByRowKey({})
    setExpandedRawErrorByRowKey({})
  }

  const buildExecuteRequest = useCallback(
    (
      query: string,
      overrides?: Partial<{
        page: number
        size: number
        sortBy: string
        sortDirection: SortDirection
        filter: string
      }>,
    ): ExecuteQueryRequest => {
      const normalizedQuery = query.trim()
      const isSameAsCurrentResult =
        normalizeRequestString(result?.query) === normalizeRequestString(normalizedQuery)
      const fallbackSize = isSameAsCurrentResult ? result?.size : undefined
      const fallbackSortBy = isSameAsCurrentResult
        ? normalizeRequestString(result?.sortBy as string | undefined) || undefined
        : undefined
      const fallbackSortDirection = isSameAsCurrentResult
        ? normalizeSortDirectionValue(result?.sortDirection as string | undefined)
        : undefined
      const fallbackFilter = isSameAsCurrentResult
        ? normalizeRequestString(result?.filter as string | undefined)
        : ''
      const rawFilterCandidate = overrides?.filter ?? (fallbackFilter || filterText)
      const filterValue = normalizeRequestString(rawFilterCandidate)

      return {
        query: normalizedQuery,
        page: overrides?.page ?? page,
        size: overrides?.size ?? fallbackSize ?? rowsPerPage,
        sortBy: overrides?.sortBy ?? fallbackSortBy ?? sortColumn,
        sortDirection:
          overrides?.sortDirection ?? fallbackSortDirection ?? sortDirection,
        filter: filterValue || undefined,
      }
    },
    [filterText, page, result?.filter, result?.query, result?.size, result?.sortBy, result?.sortDirection, rowsPerPage, sortColumn, sortDirection],
  )

  const readCachedResponse = useCallback((request: ExecuteQueryRequest): ExecuteQueryResponse | null => {
    const pageNumber = request.page ?? 0
    const baseKey = buildRequestCacheBaseKey(request)
    return cachedPageResponsesRef.current[baseKey]?.[pageNumber] ?? null
  }, [])

  const saveCachedResponse = useCallback((request: ExecuteQueryRequest, response: ExecuteQueryResponse) => {
    const pageNumber = response.page ?? request.page ?? 0
    const canonicalRequest = buildCanonicalRequestFromResponse(request, response)
    const baseKeys = new Set<string>([
      buildRequestCacheBaseKey(request),
      buildRequestCacheBaseKey(canonicalRequest),
    ])

    for (const baseKey of baseKeys) {
      const existingByPage = cachedPageResponsesRef.current[baseKey] ?? {}
      existingByPage[pageNumber] = response

      const sortedPageNumbers = Object.keys(existingByPage)
        .map((raw) => Number(raw))
        .filter((value) => Number.isInteger(value))
        .sort((left, right) => left - right)
      if (sortedPageNumbers.length > MAX_CACHED_PAGES_PER_KEY) {
        const pagesToDrop = sortedPageNumbers.slice(0, sortedPageNumbers.length - MAX_CACHED_PAGES_PER_KEY)
        for (const pageToDrop of pagesToDrop) {
          delete existingByPage[pageToDrop]
        }
      }

      cachedPageResponsesRef.current[baseKey] = existingByPage
    }
  }, [])

  const preloadForwardPages = useCallback(
    (request: ExecuteQueryRequest, response: ExecuteQueryResponse) => {
      const totalCount = response.count ?? 0
      const pageSize = response.size ?? request.size ?? rowsPerPage
      const currentPage = response.page ?? request.page ?? 0
      if (pageSize <= 0 || totalCount <= 0) {
        return
      }

      const totalPages = Math.ceil(totalCount / pageSize)
      if (totalPages <= 1) {
        return
      }

      for (let offset = 1; offset <= PRELOAD_AHEAD_PAGES; offset += 1) {
        const targetPage = currentPage + offset
        if (targetPage >= totalPages) {
          break
        }

        const preloadRequest: ExecuteQueryRequest = {
          ...request,
          page: targetPage,
          size: pageSize,
        }
        if (readCachedResponse(preloadRequest)) {
          continue
        }

        const inflightKey = buildRequestCachePageKey(preloadRequest)
        if (prefetchInFlightRef.current.has(inflightKey)) {
          continue
        }
        prefetchInFlightRef.current.add(inflightKey)

        void executeQuery(preloadRequest)
          .then((preloadedResponse) => {
            saveCachedResponse(preloadRequest, preloadedResponse)
          })
          .finally(() => {
            prefetchInFlightRef.current.delete(inflightKey)
          })
      }

      for (let offset = 1; offset <= PRELOAD_BEHIND_PAGES; offset += 1) {
        const targetPage = currentPage - offset
        if (targetPage < 0) {
          break
        }

        const preloadRequest: ExecuteQueryRequest = {
          ...request,
          page: targetPage,
          size: pageSize,
        }
        if (readCachedResponse(preloadRequest)) {
          continue
        }

        const inflightKey = buildRequestCachePageKey(preloadRequest)
        if (prefetchInFlightRef.current.has(inflightKey)) {
          continue
        }
        prefetchInFlightRef.current.add(inflightKey)

        void executeQuery(preloadRequest)
          .then((preloadedResponse) => {
            saveCachedResponse(preloadRequest, preloadedResponse)
          })
          .finally(() => {
            prefetchInFlightRef.current.delete(inflightKey)
          })
      }
    },
    [readCachedResponse, rowsPerPage, saveCachedResponse],
  )

  const dispatchPagedQuery = useCallback(
    (
      query: string,
      overrides?: Partial<{
        page: number
        size: number
        sortBy: string
        sortDirection: SortDirection
        filter: string
      }>,
    ) => {
      const request = buildExecuteRequest(query, overrides)
      const cachedResponse = readCachedResponse(request)
      if (cachedResponse) {
        dispatch(setQueryResult(cachedResponse))
        preloadForwardPages(request, cachedResponse)
        return
      }

      void dispatch(runQuery(request))
        .unwrap()
        .then((response) => {
          saveCachedResponse(request, response)
          preloadForwardPages(request, response)
        })
        .catch(() => {
          // Error state is handled by redux slice; suppress unhandled promise warnings here.
        })
    },
    [buildExecuteRequest, dispatch, preloadForwardPages, readCachedResponse, saveCachedResponse],
  )

  const handleRunQuery = () => {
    const normalizedQuery = queryText.trim()
    if (!normalizedQuery) {
      return
    }
    setPage(0)
    resetExpandedState()
    dispatchPagedQuery(normalizedQuery, { page: 0 })
  }

  const handleSort = (column: string) => {
    const activeQuery = result?.query
    if (!activeQuery) {
      return
    }
    if (sortColumn === column) {
      const nextDirection: SortDirection = sortDirection === 'asc' ? 'desc' : 'asc'
      setSortDirection(nextDirection)
      setPage(0)
      resetExpandedState()
      dispatchPagedQuery(activeQuery, { page: 0, sortDirection: nextDirection, sortBy: column })
      return
    }
    setSortColumn(column)
    setSortDirection('asc')
    setPage(0)
    resetExpandedState()
    dispatchPagedQuery(activeQuery, { page: 0, sortBy: column, sortDirection: 'asc' })
  }

  useEffect(() => {
    const activeQuery = result?.query
    if (!activeQuery) {
      return
    }
    const timeout = window.setTimeout(() => {
      setPage(0)
      resetExpandedState()
      dispatchPagedQuery(activeQuery, { page: 0, filter: filterText })
    }, 300)
    return () => window.clearTimeout(timeout)
  }, [filterText])

  useEffect(() => {
    if (pageRoute !== 'editor' || pendingEditorCursor === null) {
      return
    }
    focusQueryAt(pendingEditorCursor)
    setPendingEditorCursor(null)
  }, [pageRoute, pendingEditorCursor])

  const loadExpandedRaw = async (rowKey: string, itemId: string) => {
    if (!itemId.trim()) {
      setExpandedRawErrorByRowKey((current) => ({
        ...current,
        [rowKey]: 'Brak item_id dla rekordu.',
      }))
      return
    }

    setExpandedRawLoadingByRowKey((current) => ({ ...current, [rowKey]: true }))
    setExpandedRawErrorByRowKey((current) => ({ ...current, [rowKey]: '' }))

    try {
      const response = await fetchItemFragment(itemId)
      setExpandedRawByRowKey((current) => ({ ...current, [rowKey]: response.raw ?? '' }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Nie udalo sie pobrac pelnego fragmentu XML.'
      setExpandedRawErrorByRowKey((current) => ({ ...current, [rowKey]: message }))
    } finally {
      setExpandedRawLoadingByRowKey((current) => ({ ...current, [rowKey]: false }))
    }
  }

  const toggleExpanded = (rowKey: string, item: QueryItem) => {
    const willExpand = !expandedRows[rowKey]
    setExpandedRows((current) => ({
      ...current,
      [rowKey]: willExpand,
    }))

    if (willExpand && item.node_id?.trim() && !expandedRawByRowKey[rowKey]) {
      setExpandedRawByRowKey((current) => ({ ...current, [rowKey]: item.raw ?? '' }))
      return
    }

    if (
      willExpand &&
      !expandedRawByRowKey[rowKey] &&
      !expandedRawLoadingByRowKey[rowKey]
    ) {
      void loadExpandedRaw(rowKey, item.item_id ?? '')
    }
  }

  return (
    <Box sx={{ minHeight: '100vh' }}>
      <AppBar
        position="sticky"
        color="inherit"
        elevation={0}
        sx={{
          borderBottom: 1,
          borderColor: 'divider',
          bgcolor: 'rgba(255,255,255,0.92)',
          backdropFilter: 'blur(10px)',
        }}
      >
        <Toolbar
          sx={{
            gap: 2,
            minHeight: { xs: 64, md: 56 },
            px: { xs: 2, md: 3 },
            flexWrap: 'wrap',
          }}
        >
          <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', minWidth: 260 }}>
            <Box
              sx={{
                width: 36,
                height: 36,
                display: 'grid',
                placeItems: 'center',
                borderRadius: 1.5,
                bgcolor: 'primary.main',
                color: 'primary.contrastText',
              }}
            >
              <TerminalRoundedIcon fontSize="small" />
            </Box>
            <Box>
              <Typography variant="h4">Mizar Query Workbench</Typography>
              <Typography variant="caption" color="text.secondary">
                Edytor zapytan i analiza wynikow MML
              </Typography>
            </Box>
          </Stack>

          <Stack
            direction="row"
            spacing={1}
            useFlexGap
            sx={{ ml: { md: 'auto' }, flexWrap: 'wrap', alignItems: 'center' }}
          >
            <Button
              size="small"
              variant={pageRoute === 'editor' ? 'contained' : 'outlined'}
              onClick={() => navigateToPage('editor')}
            >
              Edytor
            </Button>
            <Button
              size="small"
              variant={pageRoute === 'examples' ? 'contained' : 'outlined'}
              startIcon={<MenuBookRoundedIcon fontSize="small" />}
              onClick={() => navigateToPage('examples')}
            >
              Przyklady
            </Button>
            <Chip
              size="small"
              color={
                syntaxStatus === 'failed'
                  ? 'error'
                  : syntaxStatus === 'loading'
                    ? 'warning'
                    : 'success'
              }
              variant="outlined"
              label={syntaxStatusLabel}
            />
            <Tooltip title={apiBaseUrl}>
              <Chip size="small" variant="outlined" label="API" />
            </Tooltip>
            <Chip size="small" variant="outlined" label={resultStatusLabel} />
          </Stack>
        </Toolbar>
      </AppBar>

      <Container
        maxWidth={false}
        sx={{
          maxWidth: 1800,
          py: { xs: 2, md: 3 },
          px: { xs: 2, md: 3 },
        }}
      >
        <Stack spacing={2.5}>
        {pageRoute === 'editor' && (
          <>
        <Grid container spacing={2.5} sx={{ alignItems: 'stretch' }}>
          <Grid size={{ xs: 12, lg: 8 }}>
            <Card sx={{ borderRadius: 2, height: '100%' }}>
              <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
                <Stack spacing={2.5}>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' } }}
                  >
                    <Box>
                      <Typography variant="h6">Query editor</Typography>
                    </Box>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                      <Chip
                        size="small"
                        color={hasQueryErrors ? 'error' : 'success'}
                        variant={hasQueryErrors ? 'filled' : 'outlined'}
                        label={hasQueryErrors ? `${queryValidation.errors.length} bledow` : 'Query OK'}
                      />
                      {queryValidation.warnings.length > 0 && (
                        <Chip
                          size="small"
                          color="warning"
                          variant="outlined"
                          label={`${queryValidation.warnings.length} ostrzezen`}
                        />
                      )}
                    </Stack>
                  </Stack>
                  <Box>
                    <TextField
                      label="MML Query"
                      value={queryText}
                      onChange={handleQueryTextChange}
                      onFocus={(event) => {
                        setQueryFocused(true)
                        handleQueryCursorUpdate(event)
                      }}
                      onBlur={() => setQueryFocused(false)}
                      inputRef={queryInputRef}
                      slotProps={{
                        htmlInput: {
                          'aria-activedescendant': isSuggestionListOpen ? activeSuggestionId : undefined,
                          'aria-autocomplete': 'list',
                          'aria-controls': isSuggestionListOpen ? SUGGESTION_LIST_ID : undefined,
                          'aria-expanded': isSuggestionListOpen,
                          onClick: handleQueryCursorUpdate,
                          onKeyDown: handleQueryKeyDown,
                          onKeyUp: handleQueryKeyUp,
                          onScroll: updateSuggestionPosition,
                          onSelect: handleQueryCursorUpdate,
                        },
                      }}
                      fullWidth
                      multiline
                      minRows={7}
                      placeholder={QUERY_PLACEHOLDER}
                      sx={{
                        '& textarea': {
                          fontFamily:
                            'ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace',
                          lineHeight: 1.55,
                        },
                      }}
                    />
                    {isSuggestionListOpen && suggestionPosition && (
                      <Paper
                        id={SUGGESTION_LIST_ID}
                        elevation={8}
                        role="listbox"
                        sx={{
                          position: 'fixed',
                          top: suggestionPosition.top,
                          left: suggestionPosition.left,
                          zIndex: (theme) => theme.zIndex.tooltip,
                          width: SUGGESTION_MENU_WIDTH,
                          maxHeight: 240,
                          overflowY: 'auto',
                          border: 1,
                          borderColor: 'divider',
                          borderRadius: 1,
                        }}
                      >
                        <List dense disablePadding>
                          {querySuggestions.map((suggestion, index) => (
                            <ListItemButton
                              id={`${SUGGESTION_OPTION_ID_PREFIX}-${index}`}
                              key={suggestion}
                              role="option"
                              selected={index === activeSuggestionIndex}
                              onMouseDown={(event) => event.preventDefault()}
                              onMouseEnter={() => setSelectedSuggestionIndex(index)}
                              onClick={() => acceptSuggestion(suggestion)}
                              sx={{ py: 0.5 }}
                            >
                              <Typography
                                component="span"
                                noWrap
                                sx={{ display: 'block', fontFamily: 'monospace', fontSize: 14 }}
                              >
                                {suggestion}
                              </Typography>
                            </ListItemButton>
                          ))}
                        </List>
                      </Paper>
                    )}
                  </Box>
                  {queryValidation.errors.map((message) => (
                    <Alert key={`query-error-${message}`} severity="error">
                      {message}
                    </Alert>
                  ))}
                  {queryValidation.warnings.map((message) => (
                    <Alert key={`query-warning-${message}`} severity="warning">
                      {message}
                    </Alert>
                  ))}
                  {executeError && (
                    <Alert severity="error">
                      {executeError}
                    </Alert>
                  )}
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    sx={{ alignItems: { xs: 'stretch', sm: 'center' } }}
                  >
                    <Button
                      variant="contained"
                      startIcon={
                        executeStatus === 'loading' ? (
                          <CircularProgress size={18} color="inherit" />
                        ) : (
                          <PlayArrowRoundedIcon />
                        )
                      }
                      onClick={handleRunQuery}
                      disabled={!canExecute}
                    >
                      Uruchom zapytanie
                    </Button>
                    <Button
                      variant="outlined"
                      startIcon={<MenuBookRoundedIcon />}
                      onClick={() => navigateToPage('examples')}
                    >
                      Biblioteka przykladow
                    </Button>
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, lg: 4 }}>
            <Card sx={{ borderRadius: 2, height: '100%' }}>
              <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
                <Stack spacing={2}>
                  <Box>
                    <Typography variant="h6">Skladnia i operacje</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Kliknij element, zeby wstawic go w miejscu kursora.
                    </Typography>
                  </Box>

                  {syntaxStatus === 'loading' && <LinearProgress />}
                  {syntaxError && <Alert severity="error">{syntaxError}</Alert>}
                  <Accordion defaultExpanded disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
                    <AccordionSummary
                      expandIcon={<KeyboardArrowDownRoundedIcon />}
                      sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
                    >
                      <Typography variant="subtitle2" color="text.secondary">
                        Operatory logiczne
                      </Typography>
                    </AccordionSummary>
                    <AccordionDetails sx={{ px: 0, pt: 0 }}>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        {(syntax?.supportedOperators ?? []).map((operator) => (
                          <Tooltip key={operator} arrow title={describeKeyword(operator, 'operator')}>
                            <Chip
                              label={operator}
                              size="small"
                              onClick={() => appendQueryToken(operator)}
                            />
                          </Tooltip>
                        ))}
                      </Stack>
                    </AccordionDetails>
                  </Accordion>

                  <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
                    <AccordionSummary
                      expandIcon={<KeyboardArrowDownRoundedIcon />}
                      sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
                    >
                      <Typography variant="subtitle2" color="text.secondary">
                        Pipeline
                      </Typography>
                    </AccordionSummary>
                    <AccordionDetails sx={{ px: 0, pt: 0 }}>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', maxHeight: 160, overflowY: 'auto', pr: 0.5 }}>
                        {(syntax?.supportedPipelineOperations ?? []).map((operation) => (
                          <Tooltip key={operation} arrow title={describeKeyword(operation, 'pipeline')}>
                            <Chip
                              label={operation}
                              size="small"
                              variant="outlined"
                              onClick={() => appendQueryToken(operation)}
                            />
                          </Tooltip>
                        ))}
                      </Stack>
                    </AccordionDetails>
                  </Accordion>

                  {(syntax?.supportedAttributeNames?.length ?? 0) > 0 && (
                    <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
                      <AccordionSummary
                        expandIcon={<KeyboardArrowDownRoundedIcon />}
                        sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
                      >
                        <Typography variant="subtitle2" color="text.secondary">
                          Atrybuty
                        </Typography>
                      </AccordionSummary>
                      <AccordionDetails sx={{ px: 0, pt: 0 }}>
                        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                          {(showAllAttributes
                            ? (syntax?.supportedAttributeNames ?? [])
                            : (syntax?.supportedAttributeNames ?? []).slice(0, 12)
                          ).map((attribute) => (
                            <Tooltip key={attribute} arrow title={describeKeyword(attribute, 'attribute')}>
                              <Chip
                                label={attribute}
                                size="small"
                                variant="outlined"
                                onClick={() => appendQueryToken(attribute)}
                              />
                            </Tooltip>
                          ))}
                        </Stack>
                        {(syntax?.supportedAttributeNames?.length ?? 0) > 12 && (
                          <Button
                            size="small"
                            sx={{ mt: 1 }}
                            onClick={() => setShowAllAttributes((current) => !current)}
                          >
                            {showAllAttributes ? 'Pokaz mniej' : 'Pokaz wszystkie'}
                          </Button>
                        )}
                      </AccordionDetails>
                    </Accordion>
                  )}

                  {(syntax?.supportedNodeNames?.length ?? 0) > 0 && (
                    <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
                      <AccordionSummary
                        expandIcon={<KeyboardArrowDownRoundedIcon />}
                        sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
                      >
                        <Typography variant="subtitle2" color="text.secondary">
                          Nody XML
                        </Typography>
                      </AccordionSummary>
                      <AccordionDetails sx={{ px: 0, pt: 0 }}>
                        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                          {(showAllNodes
                            ? (syntax?.supportedNodeNames ?? [])
                            : (syntax?.supportedNodeNames ?? []).slice(0, 14)
                          ).map((nodeName) => (
                            <Tooltip key={nodeName} arrow title={describeKeyword(nodeName, 'node')}>
                              <Chip
                                label={nodeName}
                                size="small"
                                variant="outlined"
                                onClick={() => appendQueryToken(nodeName)}
                              />
                            </Tooltip>
                          ))}
                        </Stack>
                        {(syntax?.supportedNodeNames?.length ?? 0) > 14 && (
                          <Button
                            size="small"
                            sx={{ mt: 1 }}
                            onClick={() => setShowAllNodes((current) => !current)}
                          >
                            {showAllNodes ? 'Pokaz mniej' : 'Pokaz wszystkie'}
                          </Button>
                        )}
                      </AccordionDetails>
                    </Accordion>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>
        </Grid>

        <Card sx={{ borderRadius: 2 }}>
          {executeStatus === 'loading' && <LinearProgress />}
          <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
            <Stack spacing={2}>
              <Stack
                direction={{ xs: 'column', md: 'row' }}
                spacing={1.5}
                sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', md: 'center' } }}
              >
                <Box>
                  <Typography variant="h6">Wyniki</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {result
                      ? result.description
                      : 'Uruchom zapytanie, aby zobaczyc wyniki i szczegoly rekordow.'}
                  </Typography>
                </Box>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  <Chip size="small" variant="outlined" label={result ? `${result.count} rekordow` : '0 rekordow'} />
                  {result && (
                    <Chip
                      size="small"
                      variant="outlined"
                      label={`${result.items.length} na stronie`}
                    />
                  )}
                  {result?.timing && (
                    <>
                      <Chip size="small" variant="outlined" label={`total ${result.timing.totalMs} ms`} />
                      <Chip size="small" variant="outlined" label={`SQL ${result.timing.executeMs} ms`} />
                      <Chip size="small" variant="outlined" label={`XML ${result.timing.projectionMs} ms`} />
                    </>
                  )}
                </Stack>
              </Stack>

              {!result && executeStatus !== 'loading' && (
                <Paper
                  variant="outlined"
                  sx={{
                    p: { xs: 2, md: 3 },
                    borderStyle: 'dashed',
                    bgcolor: 'background.default',
                  }}
                >
                  <Stack spacing={1}>
                    <Typography variant="subtitle2">Brak wyniku</Typography>
                    <Typography variant="body2" color="text.secondary">
                      Wpisz query albo wybierz przyklad, a potem uruchom zapytanie.
                    </Typography>
                  </Stack>
                </Paper>
              )}

              {result && (
                <>
                  <Tabs
                    value={tab}
                    onChange={(_event, newValue: number) => setTab(newValue)}
                    variant="scrollable"
                    scrollButtons="auto"
                    aria-label="Widoki wynikow"
                    sx={{ borderBottom: 1, borderColor: 'divider' }}
                  >
                    <Tab label="Items" {...getResultTabProps(0)} />
                    <Tab label="AST" {...getResultTabProps(1)} />
                    <Tab label="Response JSON" {...getResultTabProps(2)} />
                  </Tabs>

                  {tab === 0 && (
                    <Box
                      id="results-panel-0"
                      role="tabpanel"
                      aria-labelledby="results-tab-0"
                      sx={{ pt: 1 }}
                    >
                      <Stack spacing={1.5}>
                        <TextField
                          label="Filtruj wyniki"
                          size="small"
                          value={filterText}
                          onChange={(event) => setFilterText(event.target.value)}
                          placeholder="Szukaj we wszystkich kolumnach"
                          slotProps={{
                            input: {
                              startAdornment: (
                                <InputAdornment position="start">
                                  <SearchRoundedIcon fontSize="small" />
                                </InputAdornment>
                              ),
                            },
                          }}
                        />

                        <Typography variant="caption" color="text.secondary">
                          Pokazano {result.items.length} z {result.count} rekordow
                        </Typography>

                        {result.items.length === 0 && (
                          <Alert severity="info">Zapytanie zwrocilo pusty wynik.</Alert>
                        )}

                        {result.items.length > 0 && (
                          <>
                            <TableContainer
                              sx={{
                                maxHeight: 520,
                                border: 1,
                                borderColor: 'divider',
                                borderRadius: 2,
                              }}
                            >
                              <Table stickyHeader size="small">
                                <TableHead>
                                  <TableRow>
                                    {canExpandRows && <TableCell sx={{ width: 56 }} />}
                                    {tableColumns.map((column) => (
                                      <TableCell
                                        key={column.key}
                                        sortDirection={sortColumn === column.key ? sortDirection : false}
                                      >
                                        <TableSortLabel
                                          active={sortColumn === column.key}
                                          direction={sortColumn === column.key ? sortDirection : 'asc'}
                                          onClick={() => handleSort(column.key)}
                                        >
                                          {column.label}
                                        </TableSortLabel>
                                      </TableCell>
                                    ))}
                                  </TableRow>
                                </TableHead>
                                <TableBody>
                                  {rowEntries.map(({ item, rowKey }) => {
                                    const isExpanded = Boolean(expandedRows[rowKey])
                                    const canExpand =
                                      canExpandRows && (hasValue(item.item_id) || hasValue(item.node_id))
                                    const expandedRaw = expandedRawByRowKey[rowKey] ?? ''
                                    const expandedRawLoading = Boolean(expandedRawLoadingByRowKey[rowKey])
                                    const expandedRawError = expandedRawErrorByRowKey[rowKey] ?? ''

                                    return (
                                      <Fragment key={rowKey}>
                                        <TableRow hover>
                                          {canExpandRows && (
                                            <TableCell>
                                              <IconButton
                                                size="small"
                                                disabled={!canExpand}
                                                onClick={() => toggleExpanded(rowKey, item)}
                                                aria-label={isExpanded ? 'Zwin' : 'Rozwin'}
                                              >
                                                {isExpanded ? (
                                                  <KeyboardArrowUpRoundedIcon fontSize="small" />
                                                ) : (
                                                  <KeyboardArrowDownRoundedIcon fontSize="small" />
                                                )}
                                              </IconButton>
                                            </TableCell>
                                          )}
                                          {tableColumns.map((column) => (
                                            <TableCell
                                              key={`${rowKey}-${column.key}`}
                                              sx={column.key === 'raw' ? { maxWidth: 540 } : undefined}
                                            >
                                              <Box
                                                sx={{
                                                  whiteSpace: 'nowrap',
                                                  overflow: 'hidden',
                                                  textOverflow: 'ellipsis',
                                                }}
                                              >
                                                {String(item[column.key] ?? '')}
                                              </Box>
                                            </TableCell>
                                          ))}
                                        </TableRow>
                                        {canExpandRows && (
                                          <TableRow>
                                            <TableCell
                                              colSpan={tableColumns.length + 1}
                                              sx={{ py: 0, borderBottom: isExpanded ? undefined : 0 }}
                                            >
                                              <Collapse in={isExpanded} timeout="auto" unmountOnExit>
                                                <Box
                                                  sx={{
                                                    px: 2,
                                                    py: 1.5,
                                                    bgcolor: 'action.hover',
                                                    borderRadius: 1,
                                                    my: 1,
                                                  }}
                                                >
                                                  <Typography variant="caption" color="text.secondary">
                                                    Pelny fragment XML
                                                  </Typography>
                                                  {expandedRawLoading && <CircularProgress size={18} sx={{ mt: 1 }} />}
                                                  {!expandedRawLoading && expandedRawError && (
                                                    <Alert severity="error" sx={{ mt: 1 }}>
                                                      {expandedRawError}
                                                    </Alert>
                                                  )}
                                                  {!expandedRawLoading && !expandedRawError && (
                                                    <Box
                                                      sx={{
                                                        mt: 0.75,
                                                        fontFamily: 'monospace',
                                                        whiteSpace: 'pre-wrap',
                                                        wordBreak: 'break-word',
                                                      }}
                                                    >
                                                      {expandedRaw || '-'}
                                                    </Box>
                                                  )}
                                                </Box>
                                              </Collapse>
                                            </TableCell>
                                          </TableRow>
                                        )}
                                      </Fragment>
                                    )
                                  })}
                                </TableBody>
                              </Table>
                            </TableContainer>

                            <TablePagination
                              component="div"
                              count={result.count}
                              page={page}
                              onPageChange={(_event, nextPage) => {
                                const activeQuery = result.query
                                setPage(nextPage)
                                resetExpandedState()
                                dispatchPagedQuery(activeQuery, { page: nextPage })
                              }}
                              rowsPerPage={rowsPerPage}
                              onRowsPerPageChange={(event) => {
                                const activeQuery = result.query
                                const nextSize = Number(event.target.value)
                                setRowsPerPage(nextSize)
                                setPage(0)
                                resetExpandedState()
                                dispatchPagedQuery(activeQuery, { page: 0, size: nextSize })
                              }}
                              rowsPerPageOptions={[10, 25, 50]}
                            />
                          </>
                        )}
                      </Stack>
                    </Box>
                  )}

                  {tab === 1 && (
                    <Box
                      id="results-panel-1"
                      role="tabpanel"
                      aria-labelledby="results-tab-1"
                      sx={{
                        p: 2,
                        border: 1,
                        borderColor: 'divider',
                        borderRadius: 2,
                        fontFamily: 'monospace',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {JSON.stringify(result.ast, null, 2)}
                    </Box>
                  )}

                  {tab === 2 && (
                    <Box
                      id="results-panel-2"
                      role="tabpanel"
                      aria-labelledby="results-tab-2"
                      sx={{
                        p: 2,
                        border: 1,
                        borderColor: 'divider',
                        borderRadius: 2,
                        fontFamily: 'monospace',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                      }}
                    >
                      {JSON.stringify(result, null, 2)}
                    </Box>
                  )}
                </>
              )}
            </Stack>
          </CardContent>
        </Card>
          </>
        )}

        {pageRoute === 'examples' && (
          <Card sx={{ borderRadius: 2 }}>
            <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
              <Stack spacing={2}>
                <Box>
                  <Typography variant="h6">Biblioteka przykladowych zapytan</Typography>
                  <Typography variant="body2" color="text.secondary">
                    Kazdy wpis ma opis i gotowe query. Kliknij "Uzyj", aby przejsc do edytora z
                    uzupelnionym zapytaniem.
                  </Typography>
                </Box>

                <Grid container spacing={2}>
                  {EXAMPLE_QUERY_LIBRARY.map((example) => (
                    <Grid key={example.id} size={{ xs: 12, md: 6 }}>
                      <Paper variant="outlined" sx={{ p: 2, height: '100%' }}>
                        <Stack spacing={1.25} sx={{ height: '100%' }}>
                          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
                            {example.title}
                          </Typography>
                          <Typography variant="body2" color="text.secondary">
                            {example.description}
                          </Typography>
                          <Paper
                            variant="outlined"
                            sx={{
                              p: 1.25,
                              fontFamily:
                                'ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace',
                              fontSize: 13,
                              whiteSpace: 'pre-wrap',
                              wordBreak: 'break-word',
                              bgcolor: 'background.default',
                            }}
                          >
                            {example.query}
                          </Paper>
                          <Box sx={{ mt: 'auto' }}>
                            <Button variant="contained" onClick={() => handleExampleSelect(example.query)}>
                              Uzyj tego zapytania
                            </Button>
                          </Box>
                        </Stack>
                      </Paper>
                    </Grid>
                  ))}
                </Grid>
              </Stack>
            </CardContent>
          </Card>
        )}
        </Stack>
      </Container>
    </Box>
  )
}

export default App

