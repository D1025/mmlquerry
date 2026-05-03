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
  Divider,
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
import { fetchItemFragment, type QueryItem } from './features/query/queryApi'
import {
  applySuggestionAtCursor,
  getQuerySuggestions,
  validateQueryText,
} from './features/query/queryAssist'
import { fetchSyntax, runQuery, setQueryText } from './features/query/querySlice'

type SortDirection = 'asc' | 'desc'

interface ColumnDef {
  key: keyof QueryItem
  label: string
}

interface RowEntry {
  item: QueryItem
  index: number
  rowKey: string
}

const TABLE_COLUMNS: ColumnDef[] = [
  { key: 'lib_id', label: 'lib_id' },
  { key: 'article_name', label: 'article_name' },
  { key: 'node_type', label: 'node_type' },
  { key: 'text_position', label: 'position' },
  { key: 'raw', label: 'raw' },
]

const SUGGESTION_LIST_ID = 'query-suggestion-list'
const SUGGESTION_OPTION_ID_PREFIX = 'query-suggestion-option'
const SUGGESTION_MENU_WIDTH = 280
const WORD_CHAR_REGEX = /[A-Za-z0-9_-]/
const QUERY_PLACEHOLDER =
  "Np. list of definition | nodes Item where redefine true and has *[spelling='Noetherian']"
const TARGET_ATTRIBUTE_DEFINITION_EXAMPLE =
  "list of definition | nodes Item[kind='Attribute-Definition'] where has Redefine[occurs='true'] and has AttributePattern[spelling='Noetherian']"
const WILDCARD_ATTRIBUTE_DEFINITION_EXAMPLE =
  "list of definition | nodes Item where has Redefine[occurs='true'] and has *[spelling='Noetherian']"
const SHORT_REDEFINE_ATTRIBUTE_DEFINITION_EXAMPLE =
  "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']"

interface SuggestionPosition {
  top: number
  left: number
}

function compareValues(left: unknown, right: unknown): number {
  const leftNumber = Number(left)
  const rightNumber = Number(right)

  if (!Number.isNaN(leftNumber) && !Number.isNaN(rightNumber)) {
    return leftNumber - rightNumber
  }

  return String(left ?? '').localeCompare(String(right ?? ''), 'pl', { sensitivity: 'base' })
}

function buildRowKey(item: QueryItem, index: number): string {
  return `${item.node_id || item.item_id || ''}|${index}`
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

  const [tab, setTab] = useState(0)
  const [filterText, setFilterText] = useState('')
  const [sortColumn, setSortColumn] = useState<keyof QueryItem>('lib_id')
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

  const queryInputRef = useRef<HTMLTextAreaElement | HTMLInputElement | null>(null)

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

  const filteredSortedRows = useMemo<RowEntry[]>(() => {
    if (!result?.items.length) {
      return []
    }

    const normalizedFilter = filterText.trim().toLocaleLowerCase()

    const rows = result.items
      .map((item, index) => ({ item, index, rowKey: buildRowKey(item, index) }))
      .filter((entry) => {
        if (!normalizedFilter) {
          return true
        }

        return TABLE_COLUMNS.some((column) =>
          String(entry.item[column.key] ?? '')
            .toLocaleLowerCase()
            .includes(normalizedFilter),
        )
      })

    return [...rows].sort((left, right) => {
      const compared = compareValues(left.item[sortColumn], right.item[sortColumn])
      if (compared !== 0) {
        return sortDirection === 'asc' ? compared : -compared
      }
      return left.index - right.index
    })
  }, [filterText, result, sortColumn, sortDirection])

  const pagedRows = useMemo(() => {
    const start = page * rowsPerPage
    return filteredSortedRows.slice(start, start + rowsPerPage)
  }, [filteredSortedRows, page, rowsPerPage])

  const querySuggestions = useMemo(
    () => getQuerySuggestions(queryText, queryCursor, syntax),
    [queryCursor, queryText, syntax],
  )

  const queryExamples = useMemo(() => {
    const examples = [
      ...(syntax?.examples ?? []),
      TARGET_ATTRIBUTE_DEFINITION_EXAMPLE,
      WILDCARD_ATTRIBUTE_DEFINITION_EXAMPLE,
      SHORT_REDEFINE_ATTRIBUTE_DEFINITION_EXAMPLE,
    ]
    const seen = new Set<string>()
    return examples.filter((example) => {
      const normalized = example.trim()
      if (!normalized || seen.has(normalized)) {
        return false
      }
      seen.add(normalized)
      return true
    })
  }, [syntax?.examples])

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

  const setQueryAndCursor = (nextQuery: string, nextCursor: number) => {
    dispatch(setQueryText(nextQuery))
    setQueryCursor(nextCursor)
    setSelectedSuggestionIndex(0)
    focusQueryAt(nextCursor)
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
    setQueryAndCursor(example, example.length)
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

  const resetResultViewState = () => {
    setPage(0)
    setExpandedRows({})
    setExpandedRawByRowKey({})
    setExpandedRawLoadingByRowKey({})
    setExpandedRawErrorByRowKey({})
  }

  const handleRunQuery = () => {
    const normalizedQuery = queryText.trim()
    if (!normalizedQuery) {
      return
    }
    resetResultViewState()
    void dispatch(runQuery(normalizedQuery))
  }

  const handleSort = (column: keyof QueryItem) => {
    if (sortColumn === column) {
      setSortDirection((current) => (current === 'asc' ? 'desc' : 'asc'))
      return
    }
    setSortColumn(column)
    setSortDirection('asc')
  }

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
                      <Typography variant="body2" color="text.secondary">
                        Pisz query, korzystaj z podpowiedzi i uruchamiaj wynik bez odrywania rak od klawiatury.
                      </Typography>
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
                    <Typography variant="body2" color="text.secondary">
                      Skrot: Ctrl+Enter. Podpowiedz zatwierdzisz Tabem.
                    </Typography>
                  </Stack>

                  {queryExamples.length > 0 && (
                    <>
                      <Divider />
                      <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
                        <AccordionSummary
                          expandIcon={<KeyboardArrowDownRoundedIcon />}
                          sx={{ minHeight: 40, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
                        >
                          <Typography variant="subtitle2" color="text.secondary">
                            Przyklady zapytan
                          </Typography>
                        </AccordionSummary>
                        <AccordionDetails sx={{ px: 0, pt: 0 }}>
                          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                            {queryExamples.map((example) => (
                              <Chip
                                key={example}
                                label={example}
                                onClick={() => handleExampleSelect(example)}
                                variant="outlined"
                              />
                            ))}
                          </Stack>
                        </AccordionDetails>
                      </Accordion>
                    </>
                  )}
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

                  <Box>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Operatory logiczne
                    </Typography>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                      {(syntax?.supportedOperators ?? []).map((operator) => (
                        <Chip
                          key={operator}
                          label={operator}
                          size="small"
                          onClick={() => appendQueryToken(operator)}
                        />
                      ))}
                    </Stack>
                  </Box>

                  <Box>
                    <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                      Pipeline
                    </Typography>
                    <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                      {(syntax?.supportedPipelineOperations ?? []).map((operation) => (
                        <Chip
                          key={operation}
                          label={operation}
                          size="small"
                          variant="outlined"
                          onClick={() => appendQueryToken(operation)}
                        />
                      ))}
                    </Stack>
                  </Box>

                  {(syntax?.supportedAttributeNames?.length ?? 0) > 0 && (
                    <Box>
                      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                        Atrybuty
                      </Typography>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        {(syntax?.supportedAttributeNames ?? []).slice(0, 18).map((attribute) => (
                          <Chip
                            key={attribute}
                            label={attribute}
                            size="small"
                            variant="outlined"
                            onClick={() => appendQueryToken(attribute)}
                          />
                        ))}
                      </Stack>
                    </Box>
                  )}

                  {(syntax?.supportedNodeNames?.length ?? 0) > 0 && (
                    <Box>
                      <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                        Popularne nody
                      </Typography>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        {(syntax?.supportedNodeNames ?? []).slice(0, 12).map((nodeName) => (
                          <Chip
                            key={nodeName}
                            label={nodeName}
                            size="small"
                            variant="outlined"
                            onClick={() => appendQueryToken(nodeName)}
                          />
                        ))}
                      </Stack>
                    </Box>
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
                      label={`${filteredSortedRows.length} po filtrze`}
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

              {executeError && <Alert severity="error">{executeError}</Alert>}

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
                      {result.items.length === 0 && (
                        <Alert severity="info">Zapytanie zwrocilo pusty wynik.</Alert>
                      )}

                      {result.items.length > 0 && (
                        <Stack spacing={1.5}>
                          <TextField
                            label="Filtruj wyniki"
                            size="small"
                            value={filterText}
                            onChange={(event) => {
                              setFilterText(event.target.value)
                              resetResultViewState()
                            }}
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
                            Pokazano {filteredSortedRows.length} z {result.items.length} rekordow
                          </Typography>

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
                                  <TableCell sx={{ width: 56 }} />
                                  {TABLE_COLUMNS.map((column) => (
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
                                {pagedRows.map(({ item, rowKey }) => {
                                  const isExpanded = Boolean(expandedRows[rowKey])
                                  const canExpand =
                                    (item.item_id ?? '').trim().length > 0 ||
                                    (item.node_id ?? '').trim().length > 0
                                  const expandedRaw = expandedRawByRowKey[rowKey] ?? ''
                                  const expandedRawLoading = Boolean(expandedRawLoadingByRowKey[rowKey])
                                  const expandedRawError = expandedRawErrorByRowKey[rowKey] ?? ''

                                  return (
                                    <Fragment key={rowKey}>
                                      <TableRow hover>
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
                                        {TABLE_COLUMNS.map((column) => (
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
                                      <TableRow>
                                        <TableCell
                                          colSpan={TABLE_COLUMNS.length + 1}
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
                                    </Fragment>
                                  )
                                })}
                              </TableBody>
                            </Table>
                          </TableContainer>

                          <TablePagination
                            component="div"
                            count={filteredSortedRows.length}
                            page={page}
                            onPageChange={(_event, nextPage) => setPage(nextPage)}
                            rowsPerPage={rowsPerPage}
                            onRowsPerPageChange={(event) => {
                              setRowsPerPage(Number(event.target.value))
                              resetResultViewState()
                            }}
                            rowsPerPageOptions={[10, 25, 50]}
                          />
                        </Stack>
                      )}
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
        </Stack>
      </Container>
    </Box>
  )
}

export default App
