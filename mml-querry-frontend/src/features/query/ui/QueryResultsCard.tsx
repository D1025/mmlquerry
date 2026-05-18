import { Fragment } from 'react'
import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded'
import KeyboardArrowUpRoundedIcon from '@mui/icons-material/KeyboardArrowUpRounded'
import SearchRoundedIcon from '@mui/icons-material/SearchRounded'
import {
  Alert,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Collapse,
  InputAdornment,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TableSortLabel,
  TextField,
  Typography,
  IconButton,
} from '@mui/material'
import type { ChangeEvent } from 'react'
import type { ExecuteQueryResponse, QueryItem } from '../queryApi'
import type { ColumnDef, RowEntry, SortDirection } from '../queryWorkbenchUtils'
import { hasValue } from '../queryWorkbenchUtils'

interface QueryResultsCardProps {
  executeStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  result: ExecuteQueryResponse | null
  filterText: string
  page: number
  rowsPerPage: number
  canExpandRows: boolean
  tableColumns: ColumnDef[]
  rowEntries: RowEntry[]
  sortColumn: string
  sortDirection: SortDirection
  expandedRows: Record<string, boolean>
  expandedRawByRowKey: Record<string, string>
  expandedRawLoadingByRowKey: Record<string, boolean>
  expandedRawErrorByRowKey: Record<string, string>
  onFilterChange: (event: ChangeEvent<HTMLInputElement>) => void
  onSort: (column: string) => void
  onToggleExpanded: (rowKey: string, item: QueryItem) => void
  onPageChange: (nextPage: number) => void
  onRowsPerPageChange: (nextSize: number) => void
}

export function QueryResultsCard({
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
  onFilterChange,
  onSort,
  onToggleExpanded,
  onPageChange,
  onRowsPerPageChange,
}: QueryResultsCardProps) {
  return (
    <Card sx={{ borderRadius: 1 }}>
      {executeStatus === 'loading' && <LinearProgress />}
      <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
        <Stack spacing={2}>
          <Stack
            direction={{ xs: 'column', md: 'row' }}
            spacing={1.5}
            sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', md: 'center' } }}
          >
            <Stack spacing={0.2}>
              <Typography variant="h6">Wyniki</Typography>
            </Stack>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              <Chip size="small" variant="outlined" label={result ? `${result.count} rekordow` : '0 rekordow'} />
              {result && (
                <Chip size="small" variant="outlined" label={`${result.items.length} na stronie`} />
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
            <Stack spacing={1.5}>
              <TextField
                label="Filtruj wyniki"
                size="small"
                value={filterText}
                onChange={onFilterChange}
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
                      borderRadius: 1,
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
                                onClick={() => onSort(column.key)}
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
                                      onClick={() => onToggleExpanded(rowKey, item)}
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
                                    <Typography
                                      component="span"
                                      noWrap
                                      sx={{
                                        display: 'block',
                                        whiteSpace: 'nowrap',
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                      }}
                                    >
                                      {String(item[column.key] ?? '')}
                                    </Typography>
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
                                      <Paper
                                        variant="outlined"
                                        sx={{
                                          px: 2,
                                          py: 1.5,
                                          bgcolor: 'action.hover',
                                          borderRadius: 0.5,
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
                                          <Typography
                                            component="pre"
                                            sx={{
                                              mt: 0.75,
                                              mb: 0,
                                              fontFamily: 'monospace',
                                              whiteSpace: 'pre-wrap',
                                              wordBreak: 'break-word',
                                            }}
                                          >
                                            {expandedRaw || '-'}
                                          </Typography>
                                        )}
                                      </Paper>
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
                    onPageChange={(_event, nextPage) => onPageChange(nextPage)}
                    rowsPerPage={rowsPerPage}
                    onRowsPerPageChange={(event) => onRowsPerPageChange(Number(event.target.value))}
                    rowsPerPageOptions={[10, 25, 50]}
                  />
                </>
              )}
            </Stack>
          )}
        </Stack>
      </CardContent>
    </Card>
  )
}
