import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import type { AdminOperationSnapshot } from '../adminApi'
import {
  fetchAdminOperation,
  fetchAdminOperations,
  getAdminStatus,
  openAdminOperationsStream,
  sha256Hex,
  startAdminDownload,
  startAdminFull,
  startAdminIndex,
} from '../adminApi'

const ADMIN_TOKEN_STORAGE_KEY = 'mizar.query.adminTokenHash'
const DEFAULT_INDEX_PREFIX = 'mizar-esx/releases'
const STREAM_RECONNECT_BASE_DELAY_MS = 1_000
const STREAM_RECONNECT_MAX_DELAY_MS = 10_000
const OPERATIONS_LIST_LIMIT = 30

function isOperationRunning(status: AdminOperationSnapshot['status'] | undefined): boolean {
  return status === 'QUEUED' || status === 'RUNNING'
}

function formatTimestamp(value?: string | null): string {
  if (!value) {
    return '-'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('pl-PL')
}

function statusColor(
  status: AdminOperationSnapshot['status'] | undefined,
): 'default' | 'warning' | 'success' | 'error' | 'info' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'RUNNING') return 'warning'
  if (status === 'QUEUED') return 'info'
  return 'default'
}

function isAuthFailureMessage(message: string): boolean {
  const normalized = message.toLowerCase()
  return (
    normalized.includes('invalid admin authorization') ||
    normalized.includes('admin password is not configured') ||
    normalized.includes('unauthorized') ||
    normalized.includes('http 401')
  )
}

function toOperationSummary(operation: AdminOperationSnapshot): AdminOperationSnapshot {
  return {
    ...operation,
    logs: [],
  }
}

function upsertOperationSummary(
  current: AdminOperationSnapshot[],
  operation: AdminOperationSnapshot,
): AdminOperationSnapshot[] {
  const normalizedOperation = toOperationSummary(operation)
  const withoutPrevious = current.filter((item) => item.id !== normalizedOperation.id)
  const merged = [normalizedOperation, ...withoutPrevious]
  merged.sort((a, b) => {
    const left = Date.parse(a.queuedAt ?? '')
    const right = Date.parse(b.queuedAt ?? '')
    return (Number.isFinite(right) ? right : 0) - (Number.isFinite(left) ? left : 0)
  })
  return merged.slice(0, OPERATIONS_LIST_LIMIT)
}

export function AdminPanelView() {
  const [password, setPassword] = useState('')
  const [tokenHash, setTokenHash] = useState<string>(() => {
    try {
      return window.localStorage.getItem(ADMIN_TOKEN_STORAGE_KEY) ?? ''
    } catch {
      return ''
    }
  })
  const [authLoading, setAuthLoading] = useState(false)
  const [authError, setAuthError] = useState<string | null>(null)
  const [operations, setOperations] = useState<AdminOperationSnapshot[]>([])
  const [selectedOperationId, setSelectedOperationId] = useState('')
  const [selectedOperation, setSelectedOperation] = useState<AdminOperationSnapshot | null>(null)
  const [operationsLoading, setOperationsLoading] = useState(false)
  const [operationsError, setOperationsError] = useState<string | null>(null)
  const [streamConnected, setStreamConnected] = useState(false)
  const [actionLoading, setActionLoading] = useState<'download' | 'index' | 'full' | null>(null)
  const [indexPrefix, setIndexPrefix] = useState(DEFAULT_INDEX_PREFIX)
  const [indexPrefixError, setIndexPrefixError] = useState<string | null>(null)
  const selectedOperationIdRef = useRef(selectedOperationId)

  const isAuthenticated = tokenHash.trim().length > 0
  const hasRunningOperation = isOperationRunning(selectedOperation?.status)

  const selectedOperationTitle = useMemo(() => {
    if (!selectedOperation) {
      return 'Brak wybranej operacji'
    }
    return `${selectedOperation.type.toUpperCase()} | ${selectedOperation.id}`
  }, [selectedOperation])

  useEffect(() => {
    selectedOperationIdRef.current = selectedOperationId
  }, [selectedOperationId])

  const persistToken = (nextToken: string) => {
    try {
      if (nextToken) {
        window.localStorage.setItem(ADMIN_TOKEN_STORAGE_KEY, nextToken)
      } else {
        window.localStorage.removeItem(ADMIN_TOKEN_STORAGE_KEY)
      }
    } catch {
      // Ignore storage failures.
    }
  }

  const loadOperationDetails = useCallback(
    async (operationId: string, authToken: string) => {
      if (!operationId || !authToken) {
        setSelectedOperation(null)
        return
      }
      try {
        const details = await fetchAdminOperation(authToken, operationId)
        setSelectedOperation(details)
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Nie udało się pobrać szczegółów operacji.'
        setOperationsError(message)
        if (isAuthFailureMessage(message)) {
          setTokenHash('')
          persistToken('')
          setSelectedOperation(null)
          setOperations([])
          selectedOperationIdRef.current = ''
          setSelectedOperationId('')
          setAuthError('Sesja administratora wygasła. Zaloguj się ponownie.')
        }
      }
    },
    [],
  )

  const loadOperations = useCallback(
    async (authToken: string, keepSelection = true) => {
      if (!authToken) {
        setOperations([])
        setSelectedOperationId('')
        setSelectedOperation(null)
        return
      }

      setOperationsLoading(true)
      setOperationsError(null)
      try {
        const response = await fetchAdminOperations(authToken, OPERATIONS_LIST_LIMIT)
        setOperations(response.items)
        const currentSelectedId = selectedOperationIdRef.current
        const nextSelectedId =
          keepSelection && currentSelectedId && response.items.some((item) => item.id === currentSelectedId)
            ? currentSelectedId
            : response.items[0]?.id ?? ''
        selectedOperationIdRef.current = nextSelectedId
        setSelectedOperationId(nextSelectedId)
        if (nextSelectedId) {
          await loadOperationDetails(nextSelectedId, authToken)
        } else {
          setSelectedOperation(null)
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Nie udało się pobrać listy operacji.'
        setOperationsError(message)
        if (isAuthFailureMessage(message)) {
          setTokenHash('')
          persistToken('')
          setSelectedOperation(null)
          setOperations([])
          selectedOperationIdRef.current = ''
          setSelectedOperationId('')
          setAuthError('Sesja administratora wygasła. Zaloguj się ponownie.')
        }
      } finally {
        setOperationsLoading(false)
      }
    },
    [loadOperationDetails],
  )

  useEffect(() => {
    if (!isAuthenticated) {
      setStreamConnected(false)
      return
    }

    void loadOperations(tokenHash, false)
    let closed = false
    let reconnectTimeoutId: number | null = null
    let reconnectAttempt = 0
    let closeStream: (() => void) | null = null

    const scheduleReconnect = () => {
      if (closed) {
        return
      }
      reconnectAttempt += 1
      const delay = Math.min(
        STREAM_RECONNECT_BASE_DELAY_MS * 2 ** Math.max(0, reconnectAttempt - 1),
        STREAM_RECONNECT_MAX_DELAY_MS,
      )
      reconnectTimeoutId = window.setTimeout(() => {
        reconnectTimeoutId = null
        connectStream()
      }, delay)
    }

    const connectStream = () => {
      if (closed) {
        return
      }
      closeStream?.()
      closeStream = openAdminOperationsStream(tokenHash, {
        limit: OPERATIONS_LIST_LIMIT,
        onOpen: () => {
          reconnectAttempt = 0
          setStreamConnected(true)
        },
        onEvent: (event) => {
          if (event.type === 'bootstrap') {
            const incomingOperations = Array.isArray(event.operations) ? event.operations : []
            setOperations(incomingOperations)
            if (!selectedOperationIdRef.current && incomingOperations.length > 0) {
              const firstOperation = incomingOperations[0]
              selectedOperationIdRef.current = firstOperation.id
              setSelectedOperationId(firstOperation.id)
              void loadOperationDetails(firstOperation.id, tokenHash)
            }
            return
          }

          if (event.type === 'operation' && event.operation) {
            const updatedOperation = event.operation
            setOperations((previous) => upsertOperationSummary(previous, updatedOperation))

            if (!selectedOperationIdRef.current) {
              selectedOperationIdRef.current = updatedOperation.id
              setSelectedOperationId(updatedOperation.id)
            }

            if (selectedOperationIdRef.current === updatedOperation.id) {
              setSelectedOperation(updatedOperation)
            }
          }
        },
        onError: (error) => {
          setStreamConnected(false)
          const message = error.message ?? 'Połączenie stream zostało przerwane.'
          if (isAuthFailureMessage(message)) {
            setTokenHash('')
            persistToken('')
            setSelectedOperation(null)
            setOperations([])
            selectedOperationIdRef.current = ''
            setSelectedOperationId('')
            setAuthError('Sesja administratora wygasła. Zaloguj się ponownie.')
            return
          }
          setOperationsError(message)
          scheduleReconnect()
        },
      })
    }

    connectStream()

    return () => {
      closed = true
      setStreamConnected(false)
      if (reconnectTimeoutId !== null) {
        window.clearTimeout(reconnectTimeoutId)
      }
      closeStream?.()
    }
  }, [isAuthenticated, loadOperationDetails, loadOperations, tokenHash])

  const handleLogin = async () => {
    const trimmed = password.trim()
    if (!trimmed) {
      setAuthError('Podaj hasło administratora.')
      return
    }

    setAuthLoading(true)
    setAuthError(null)
    try {
      const hash = await sha256Hex(trimmed)
      const status = await getAdminStatus(hash)
      if (!status.configured) {
        throw new Error('Hasło administratora nie jest skonfigurowane na backendzie.')
      }
      setTokenHash(hash)
      persistToken(hash)
      setPassword('')
      await loadOperations(hash, false)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Logowanie nie powiodło się.'
      setAuthError(message)
      setTokenHash('')
      persistToken('')
    } finally {
      setAuthLoading(false)
    }
  }

  const handleLogout = () => {
    setTokenHash('')
    persistToken('')
    setOperations([])
    selectedOperationIdRef.current = ''
    setSelectedOperationId('')
    setSelectedOperation(null)
    setOperationsError(null)
    setAuthError(null)
    setStreamConnected(false)
  }

  const runAction = async (
    actionName: 'download' | 'index' | 'full',
    action: () => Promise<AdminOperationSnapshot>,
  ) => {
    if (!tokenHash) {
      return
    }
    setActionLoading(actionName)
    setOperationsError(null)
    try {
      const operation = await action()
      setSelectedOperationId(operation.id)
      setSelectedOperation(operation)
      await loadOperations(tokenHash)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Operacja zakończona błędem.'
      setOperationsError(message)
      if (isAuthFailureMessage(message)) {
        handleLogout()
      }
    } finally {
      setActionLoading(null)
    }
  }

  const handleStartIndex = async () => {
    const trimmedPrefix = indexPrefix.trim()
    if (!trimmedPrefix) {
      setIndexPrefixError('Podaj prefix S3 do indeksowania.')
      return
    }
    setIndexPrefixError(null)
    await runAction('index', () => startAdminIndex(tokenHash, trimmedPrefix))
  }

  return (
    <Stack spacing={2.25}>
      <Card sx={{ borderRadius: 1 }}>
        <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
          <Stack spacing={2}>
            <Stack
              direction={{ xs: 'column', md: 'row' }}
              spacing={1.5}
              sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', md: 'center' } }}
            >
              <Typography variant="h6">Panel administratora</Typography>
              {isAuthenticated && (
                <Button
                  size="small"
                  variant="outlined"
                  color="inherit"
                  onClick={handleLogout}
                  sx={{ minHeight: 40, px: 2, whiteSpace: 'nowrap' }}
                >
                  Wyloguj
                </Button>
              )}
            </Stack>

            {!isAuthenticated && (
              <Stack spacing={1.5}>
                <Typography variant="body2" color="text.secondary">
                  Zaloguj się hasłem administratora, aby uruchamiać pobieranie do S3 i indeksowanie.
                </Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
                  <TextField
                    label="Hasło admina"
                    type="password"
                    size="small"
                    fullWidth
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault()
                        void handleLogin()
                      }
                    }}
                  />
                  <Button
                    size="small"
                    variant="contained"
                    onClick={() => void handleLogin()}
                    disabled={authLoading}
                    sx={{ minWidth: 140, minHeight: 40, px: 2, whiteSpace: 'nowrap' }}
                  >
                    {authLoading ? <CircularProgress size={18} color="inherit" /> : 'Zaloguj'}
                  </Button>
                </Stack>
                {authError && <Alert severity="error">{authError}</Alert>}
              </Stack>
            )}

            {isAuthenticated && (
              <Stack spacing={2}>
                <Stack
                  direction={{ xs: 'column', lg: 'row' }}
                  spacing={1.25}
                  sx={{ alignItems: { xs: 'stretch', lg: 'flex-start' } }}
                >
                  <Button
                    size="small"
                    variant="contained"
                    disabled={Boolean(actionLoading) || hasRunningOperation}
                    onClick={() =>
                      void runAction('download', () => startAdminDownload(tokenHash))
                    }
                    sx={{ minHeight: 40, px: 2, whiteSpace: 'nowrap' }}
                  >
                    {actionLoading === 'download' ? 'Uruchamianie...' : 'Pobierz zasoby do S3'}
                  </Button>
                  <Stack
                    direction={{ xs: 'column', sm: 'row' }}
                    spacing={1.25}
                    sx={{ flexGrow: 1, alignItems: { xs: 'stretch', sm: 'flex-start' } }}
                  >
                    <TextField
                      label="Prefix S3 do indeksowania"
                      size="small"
                      fullWidth
                      value={indexPrefix}
                      onChange={(event) => setIndexPrefix(event.target.value)}
                      error={Boolean(indexPrefixError)}
                      helperText={indexPrefixError ?? "Np. mizar-esx/releases/<tag>/esx_mml"}
                    />
                    <Button
                      size="small"
                      variant="contained"
                      disabled={Boolean(actionLoading) || hasRunningOperation}
                      onClick={() => void handleStartIndex()}
                      sx={{ minWidth: 170, minHeight: 40, px: 2, whiteSpace: 'nowrap' }}
                    >
                      {actionLoading === 'index' ? 'Uruchamianie...' : 'Uruchom indeksowanie'}
                    </Button>
                  </Stack>
                  <Button
                    size="small"
                    variant="contained"
                    color="secondary"
                    disabled={Boolean(actionLoading) || hasRunningOperation}
                    onClick={() => void runAction('full', () => startAdminFull(tokenHash))}
                    sx={{ minHeight: 40, px: 2, whiteSpace: 'nowrap' }}
                  >
                    {actionLoading === 'full' ? 'Uruchamianie...' : 'Pełny ingest'}
                  </Button>
                </Stack>

                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Operacje: ${operations.length}`}
                  />
                  <Chip
                    size="small"
                    color={streamConnected ? 'success' : 'default'}
                    variant={streamConnected ? 'filled' : 'outlined'}
                    label={streamConnected ? 'Stream: online' : 'Stream: reconnecting'}
                  />
                  {selectedOperation && (
                    <Chip
                      size="small"
                      color={statusColor(selectedOperation.status)}
                      label={`Status: ${selectedOperation.status}`}
                    />
                  )}
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={() => void loadOperations(tokenHash)}
                    disabled={operationsLoading}
                    sx={{ minHeight: 32, whiteSpace: 'nowrap' }}
                  >
                    Odśwież
                  </Button>
                </Stack>

                {operationsError && <Alert severity="error">{operationsError}</Alert>}

                <Divider />

                <Stack spacing={1.25}>
                  <TextField
                    select
                    size="small"
                    label="Wybierz operację"
                    value={selectedOperationId}
                    onChange={(event) => {
                      const nextId = event.target.value
                      setSelectedOperationId(nextId)
                      if (nextId) {
                        void loadOperationDetails(nextId, tokenHash)
                      } else {
                        setSelectedOperation(null)
                      }
                    }}
                    fullWidth
                    disabled={operations.length === 0}
                  >
                    {operations.length === 0 && (
                      <MenuItem value="">Brak operacji</MenuItem>
                    )}
                    {operations.map((operation) => (
                      <MenuItem key={operation.id} value={operation.id}>
                        {`${operation.type.toUpperCase()} | ${operation.status} | ${formatTimestamp(operation.queuedAt)}`}
                      </MenuItem>
                    ))}
                  </TextField>

                  {selectedOperation && (
                    <Stack spacing={1}>
                      <Typography variant="subtitle2">{selectedOperationTitle}</Typography>
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        <Chip size="small" label={`Queued: ${formatTimestamp(selectedOperation.queuedAt)}`} />
                        <Chip size="small" label={`Start: ${formatTimestamp(selectedOperation.startedAt)}`} />
                        <Chip size="small" label={`Koniec: ${formatTimestamp(selectedOperation.finishedAt)}`} />
                      </Stack>

                      {selectedOperation.error && (
                        <Alert severity="error">{selectedOperation.error}</Alert>
                      )}

                      <Box
                        component="pre"
                        sx={{
                          m: 0,
                          p: 1.5,
                          border: 1,
                          borderColor: 'divider',
                          bgcolor: 'background.default',
                          fontFamily: 'monospace',
                          fontSize: '0.79rem',
                          lineHeight: 1.45,
                          maxHeight: 360,
                          overflow: 'auto',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                        }}
                      >
                        {selectedOperation.logs.length > 0
                          ? selectedOperation.logs.join('\n')
                          : 'Brak logów dla tej operacji.'}
                      </Box>

                      <Box
                        component="pre"
                        sx={{
                          m: 0,
                          p: 1.5,
                          border: 1,
                          borderColor: 'divider',
                          bgcolor: 'background.paper',
                          fontFamily: 'monospace',
                          fontSize: '0.78rem',
                          whiteSpace: 'pre-wrap',
                          wordBreak: 'break-word',
                        }}
                      >
                        {Object.keys(selectedOperation.result ?? {}).length > 0
                          ? JSON.stringify(selectedOperation.result, null, 2)
                          : '{}'}
                      </Box>
                    </Stack>
                  )}

                  {!selectedOperation && !operationsLoading && (
                    <Alert severity="info">Brak uruchomionych operacji administratora.</Alert>
                  )}
                </Stack>
              </Stack>
            )}
          </Stack>
        </CardContent>
      </Card>
    </Stack>
  )
}
