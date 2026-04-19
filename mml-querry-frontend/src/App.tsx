import { Fragment, useEffect, useMemo, useState } from 'react'
import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded'
import KeyboardArrowUpRoundedIcon from '@mui/icons-material/KeyboardArrowUpRounded'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import {
  Alert,
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
  LinearProgress,
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
  Typography,
} from '@mui/material'
import { useAppDispatch, useAppSelector } from './app/hooks'
import { fetchItemFragment, type QueryItem } from './features/query/queryApi'
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

function compareValues(left: unknown, right: unknown): number {
  const leftNumber = Number(left)
  const rightNumber = Number(right)

  if (!Number.isNaN(leftNumber) && !Number.isNaN(rightNumber)) {
    return leftNumber - rightNumber
  }

  return String(left ?? '').localeCompare(String(right ?? ''), 'pl', { sensitivity: 'base' })
}

function buildRowKey(item: QueryItem, index: number): string {
  return `${item.item_id ?? ''}|${index}`
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
    setPage(0)
    setExpandedRows({})
    setExpandedRawByRowKey({})
    setExpandedRawLoadingByRowKey({})
    setExpandedRawErrorByRowKey({})
  }, [filterText, rowsPerPage, result])

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

  const canExecute = queryText.trim().length > 0 && executeStatus !== 'loading'

  const handleRunQuery = () => {
    const normalizedQuery = queryText.trim()
    if (!normalizedQuery) {
      return
    }
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

    if (
      willExpand &&
      !expandedRawByRowKey[rowKey] &&
      !expandedRawLoadingByRowKey[rowKey]
    ) {
      void loadExpandedRaw(rowKey, item.item_id ?? '')
    }
  }

  return (
    <Container maxWidth="xl" sx={{ py: 4 }}>
      <Stack spacing={3}>
        <Box>
          <Typography variant="h4" sx={{ fontWeight: 700 }} gutterBottom>
            Mizar Query Workbench
          </Typography>
        </Box>

        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 8 }}>
            <Card sx={{ borderRadius: 3 }}>
              <CardContent>
                <Stack spacing={2.5}>
                  <Typography variant="h6">Query editor</Typography>
                  <TextField
                    label="MML Query"
                    value={queryText}
                    onChange={(event) => dispatch(setQueryText(event.target.value))}
                    fullWidth
                    multiline
                    minRows={5}
                    placeholder="Np. list of theorem in ABCMIZ_0"
                  />
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.5}
                    sx={{ alignItems: 'center' }}
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
                      API: {import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'}
                    </Typography>
                  </Stack>

                  {syntaxStatus === 'loading' && <LinearProgress />}
                  {syntaxError && <Alert severity="error">{syntaxError}</Alert>}
                  {syntax?.examples && (
                    <>
                      <Divider />
                      <Typography variant="subtitle2" color="text.secondary">
                        Przyklady zapytan
                      </Typography>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        {syntax.examples.map((example) => (
                          <Chip
                            key={example}
                            label={example}
                            onClick={() => dispatch(setQueryText(example))}
                            variant="outlined"
                          />
                        ))}
                      </Stack>
                    </>
                  )}
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, md: 4 }}>
            <Card sx={{ borderRadius: 3, height: '100%' }}>
              <CardContent>
                <Stack spacing={2}>
                  <Typography variant="h6">Skladnia i operacje</Typography>
                  <Typography variant="subtitle2" color="text.secondary">
                    Operatory logiczne
                  </Typography>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    {syntax?.supportedOperators.map((operator) => (
                      <Chip key={operator} label={operator} size="small" />
                    ))}
                  </Stack>

                  <Typography variant="subtitle2" color="text.secondary">
                    Pipeline
                  </Typography>
                  <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    {syntax?.supportedPipelineOperations.map((operation) => (
                      <Chip key={operation} label={operation} size="small" variant="outlined" />
                    ))}
                  </Stack>
                </Stack>
              </CardContent>
            </Card>
          </Grid>
        </Grid>

        <Card sx={{ borderRadius: 3 }}>
          {executeStatus === 'loading' && <LinearProgress />}
          <CardContent>
            <Stack spacing={2}>
              <Box>
                <Typography variant="h6">Wyniki</Typography>
                <Typography variant="body2" color="text.secondary">
                  {result
                    ? `${result.description} - ${result.count} elementow`
                    : 'Uruchom zapytanie, aby zobaczyc wyniki.'}
                </Typography>
              </Box>

              {executeError && <Alert severity="error">{executeError}</Alert>}

              {!result && executeStatus !== 'loading' && (
                <Alert severity="info">Brak danych do wyswietlenia.</Alert>
              )}

              {result && (
                <>
                  <Tabs
                    value={tab}
                    onChange={(_event, newValue: number) => setTab(newValue)}
                    variant="scrollable"
                    scrollButtons="auto"
                  >
                    <Tab label="Items" />
                    <Tab label="AST" />
                    <Tab label="Response JSON" />
                  </Tabs>

                  {tab === 0 && (
                    <Box>
                      {result.items.length === 0 && (
                        <Alert severity="info">Zapytanie zwrocilo pusty wynik.</Alert>
                      )}

                      {result.items.length > 0 && (
                        <Stack spacing={1.5}>
                          <TextField
                            label="Filtruj wyniki"
                            size="small"
                            value={filterText}
                            onChange={(event) => setFilterText(event.target.value)}
                            placeholder="Szukaj we wszystkich kolumnach"
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
                                  const canExpand = (item.item_id ?? '').trim().length > 0
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
                              setPage(0)
                            }}
                            rowsPerPageOptions={[10, 25, 50]}
                          />
                        </Stack>
                      )}
                    </Box>
                  )}

                  {tab === 1 && (
                    <Box
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
  )
}

export default App
