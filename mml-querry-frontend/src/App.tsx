import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from 'react'
import { Container, Stack } from '@mui/material'
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
import { describeKeyword } from './features/query/keywordHelp'
import { getCategorizedExamples } from './features/query/queryExamples'
import { fetchSyntax, runQuery, setQueryResult, setQueryText } from './features/query/querySlice'
import {
  buildCanonicalRequestFromResponse,
  buildRequestCacheBaseKey,
  buildRequestCachePageKey,
  buildRowKey,
  deriveTableColumns,
  getSuggestionPosition,
  hasValue,
  MAX_CACHED_PAGES_PER_KEY,
  normalizeRequestString,
  normalizeSortDirectionValue,
  PRELOAD_AHEAD_PAGES,
  PRELOAD_BEHIND_PAGES,
  resolvePageFromPath,
} from './features/query/queryWorkbenchUtils'
import type {
  AppPage,
  ColumnDef,
  RowEntry,
  SortDirection,
  SuggestionPosition,
} from './features/query/queryWorkbenchUtils'
import { EditorWorkspaceView } from './features/query/ui/EditorWorkspaceView'
import { ExamplesLibraryView } from './features/query/ui/ExamplesLibraryView'
import { WorkbenchHeader } from './features/query/ui/WorkbenchHeader'

const SUGGESTION_LIST_ID = 'query-suggestion-list'
const SUGGESTION_OPTION_ID_PREFIX = 'query-suggestion-option'
const SYNTAX_PANEL_COLLAPSED_STORAGE_KEY = 'mizar.query.syntaxPanelCollapsed'

const QUERY_PLACEHOLDER =
  "Np. list of definition | nodes Item where redefine true and has *[spelling='Noetherian']"

function App() {
  const dispatch = useAppDispatch()

  const [pageRoute, setPageRoute] = useState<AppPage>(() =>
    resolvePageFromPath(window.location.hash.replace(/^#\/?/, '')),
  )
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
  const [isSyntaxPanelCollapsed, setIsSyntaxPanelCollapsed] = useState<boolean>(() => {
    if (typeof window === 'undefined') {
      return true
    }
    try {
      const storedValue = window.localStorage.getItem(SYNTAX_PANEL_COLLAPSED_STORAGE_KEY)
      if (storedValue === null) {
        return true
      }
      return storedValue === '1' || storedValue === 'true'
    } catch {
      return true
    }
  })

  const queryInputRef = useRef<HTMLTextAreaElement | HTMLInputElement | null>(null)
  const cachedPageResponsesRef = useRef<Record<string, Record<number, ExecuteQueryResponse>>>({})
  const prefetchInFlightRef = useRef<Set<string>>(new Set())
  const activeQueryRequestRef = useRef<{ abort: () => void } | null>(null)

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

  useEffect(() => {
    try {
      window.localStorage.setItem(
        SYNTAX_PANEL_COLLAPSED_STORAGE_KEY,
        isSyntaxPanelCollapsed ? '1' : '0',
      )
    } catch {
      // Ignore localStorage errors (e.g. private mode restrictions).
    }
  }, [isSyntaxPanelCollapsed])

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
  }, [page, result, rowsPerPage])

  const rowEntries = useMemo<RowEntry[]>(
    () => (result?.items ?? []).map((item, index) => ({ item, index, rowKey: buildRowKey(item, index) })),
    [result?.items],
  )

  const querySuggestions = useMemo(
    () => getQuerySuggestions(queryText, queryCursor, syntax),
    [queryCursor, queryText, syntax],
  )

  const queryValidation = useMemo(() => validateQueryText(queryText, syntax), [queryText, syntax])
  const categorizedExamples = useMemo(() => getCategorizedExamples(), [])

  const canExecute = queryText.trim().length > 0 && executeStatus !== 'loading'
  const canCancel = executeStatus === 'loading'
  const activeSuggestionIndex =
    querySuggestions.length === 0
      ? 0
      : Math.min(selectedSuggestionIndex, querySuggestions.length - 1)
  const activeSuggestionId = `${SUGGESTION_OPTION_ID_PREFIX}-${activeSuggestionIndex}`
  const isSuggestionListOpen = queryFocused && querySuggestions.length > 0 && Boolean(suggestionPosition)

  useEffect(() => {
    const appName = 'Mizar Query Workbench'

    if (pageRoute === 'examples') {
      document.title = `Przyklady | ${appName}`
      return
    }

    if (executeStatus === 'loading') {
      document.title = `Wyszukiwanie... | ${appName}`
      return
    }

    if (typeof result?.count === 'number') {
      const pageSize = result.size ?? rowsPerPage
      const totalPages = pageSize > 0 ? Math.max(1, Math.ceil(result.count / pageSize)) : 1
      const currentPage = Math.min(totalPages, Math.max(1, (result.page ?? page) + 1))
      document.title = `Wyniki: ${result.count} | Strona ${currentPage}/${totalPages} | ${appName}`
      return
    }

    document.title = `Edytor zapytan | ${appName}`
  }, [executeStatus, page, pageRoute, result?.count, result?.page, result?.size, rowsPerPage])

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
    [
      filterText,
      page,
      result?.filter,
      result?.query,
      result?.size,
      result?.sortBy,
      result?.sortDirection,
      rowsPerPage,
      sortColumn,
      sortDirection,
    ],
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

      activeQueryRequestRef.current?.abort()
      const pendingRequest = dispatch(runQuery(request))
      activeQueryRequestRef.current = pendingRequest

      void pendingRequest
        .unwrap()
        .then((response) => {
          saveCachedResponse(request, response)
          preloadForwardPages(request, response)
        })
        .catch(() => {
          // Error state is handled by redux slice; suppress unhandled promise warnings here.
        })
        .finally(() => {
          if (activeQueryRequestRef.current === pendingRequest) {
            activeQueryRequestRef.current = null
          }
        })
    },
    [buildExecuteRequest, dispatch, preloadForwardPages, readCachedResponse, saveCachedResponse],
  )

  const handleCancelQuery = useCallback(() => {
    activeQueryRequestRef.current?.abort()
    activeQueryRequestRef.current = null
  }, [])

  useEffect(() => () => {
    activeQueryRequestRef.current?.abort()
  }, [])

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
  }, [dispatchPagedQuery, filterText, result?.query])

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
    <Stack sx={{ minHeight: '100vh' }}>
      <WorkbenchHeader pageRoute={pageRoute} onNavigate={navigateToPage} />

      <Container
        maxWidth={false}
        sx={{
          maxWidth: 1680,
          py: { xs: 2, md: 3 },
          px: { xs: 2, md: 3 },
        }}
      >
        {pageRoute === 'editor' ? (
          <EditorWorkspaceView
            queryEditorProps={{
              queryText,
              queryPlaceholder: QUERY_PLACEHOLDER,
              validation: queryValidation,
              executeError,
              executeStatus,
              canExecute,
              canCancel,
              queryInputRef,
              isSuggestionListOpen,
              suggestionPosition,
              querySuggestions,
              activeSuggestionIndex,
              activeSuggestionId,
              suggestionListId: SUGGESTION_LIST_ID,
              suggestionOptionIdPrefix: SUGGESTION_OPTION_ID_PREFIX,
              onSelectSuggestion: acceptSuggestion,
              onSuggestionHover: setSelectedSuggestionIndex,
              onQueryChange: handleQueryTextChange,
              onFocus: (event) => {
                setQueryFocused(true)
                handleQueryCursorUpdate(event)
              },
              onBlur: () => setQueryFocused(false),
              onCursorChange: handleQueryCursorUpdate,
              onKeyDown: handleQueryKeyDown,
              onKeyUp: handleQueryKeyUp,
              onScrollInput: updateSuggestionPosition,
              onRunQuery: handleRunQuery,
              onCancelQuery: handleCancelQuery,
              onOpenExamples: () => navigateToPage('examples'),
            }}
            syntaxGuideProps={{
              syntax,
              syntaxStatus,
              syntaxError,
              showAllAttributes,
              showAllNodes,
              onToggleAllAttributes: () => setShowAllAttributes((current) => !current),
              onToggleAllNodes: () => setShowAllNodes((current) => !current),
              onAppendToken: appendQueryToken,
              describeKeyword,
            }}
            resultsProps={{
              executeStatus,
              result,
              filterText,
              page,
              rowsPerPage,
              canExpandRows,
              tableColumns,
              rowEntries,
              sortColumn,
              sortDirection,
              expandedRows,
              expandedRawByRowKey,
              expandedRawLoadingByRowKey,
              expandedRawErrorByRowKey,
              onFilterChange: (event) => setFilterText(event.target.value),
              onSort: handleSort,
              onToggleExpanded: toggleExpanded,
              onPageChange: (nextPage) => {
                const activeQuery = result?.query
                if (!activeQuery) {
                  return
                }
                setPage(nextPage)
                resetExpandedState()
                dispatchPagedQuery(activeQuery, { page: nextPage })
              },
              onRowsPerPageChange: (nextSize) => {
                const activeQuery = result?.query
                if (!activeQuery) {
                  return
                }
                setRowsPerPage(nextSize)
                setPage(0)
                resetExpandedState()
                dispatchPagedQuery(activeQuery, { page: 0, size: nextSize })
              },
            }}
            isSyntaxPanelCollapsed={isSyntaxPanelCollapsed}
            onToggleSyntaxPanel={() =>
              setIsSyntaxPanelCollapsed((current) => !current)
            }
          />
        ) : (
          <ExamplesLibraryView
            sections={categorizedExamples}
            onUseQuery={handleExampleSelect}
            onBackToEditor={() => navigateToPage('editor')}
          />
        )}
      </Container>
    </Stack>
  )
}

export default App
