import { useCallback, useEffect, useMemo, useState } from 'react'
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
  sha256Hex,
  startAdminDownload,
  startAdminFull,
  startAdminIndex,
} from '../adminApi'

const ADMIN_TOKEN_STORAGE_KEY = 'mizar.query.adminTokenHash'
const DEFAULT_INDEX_PREFIX = 'mizar-esx/releases'

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
    normalized.includes('forbidden') ||
    normalized.includes('http 401') ||
    normalized.includes('http 403')
  )
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
  const [actionLoading, setActionLoading] = useState<'download' | 'index' | 'full' | null>(null)
  const [indexPrefix, setIndexPrefix] = useState(DEFAULT_INDEX_PREFIX)
  const [indexPrefixError, setIndexPrefixError] = useState<string | null>(null)

  const isAuthenticated = tokenHash.trim().length > 0
  const hasRunningOperation = isOperationRunning(selectedOperation?.status)

  const selectedOperationTitle = useMemo(() => {
    if (!selectedOperation) {
      return 'Brak wybranej operacji'
    }
    return `${selectedOperation.type.toUpperCase()} • ${selectedOperation.id}`
  }, [selectedOperation])

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
        const message = error instanceof Error ? error.message : 'Nie udalo sie pobrac szczegolow operacji.'
        setOperationsError(message)
        if (isAuthFailureMessage(message)) {
          setTokenHash('')
          persistToken('')
          setSelectedOperation(null)
          setOperations([])
          setSelectedOperationId('')
          setAuthError('Sesja administratora wygasla. Zaloguj sie ponownie.')
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
        const response = await fetchAdminOperations(authToken, 30)
        setOperations(response.items)
        const nextSelectedId =
          keepSelection && selectedOperationId && response.items.some((item) => item.id === selectedOperationId)
            ? selectedOperationId
            : response.items[0]?.id ?? ''
        setSelectedOperationId(nextSelectedId)
        if (nextSelectedId) {
          await loadOperationDetails(nextSelectedId, authToken)
        } else {
          setSelectedOperation(null)
        }
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Nie udalo sie pobrac listy operacji.'
        setOperationsError(message)
        if (isAuthFailureMessage(message)) {
          setTokenHash('')
          persistToken('')
          setSelectedOperation(null)
          setOperations([])
          setSelectedOperationId('')
          setAuthError('Sesja administratora wygasla. Zaloguj sie ponownie.')
        }
      } finally {
        setOperationsLoading(false)
      }
    },
    [loadOperationDetails, selectedOperationId],
  )

  useEffect(() => {
    if (!isAuthenticated) {
      return
    }
    void loadOperations(tokenHash, false)
  }, [isAuthenticated, loadOperations, tokenHash])

  useEffect(() => {
    if (!isAuthenticated || !selectedOperationId) {
      return
    }

    const intervalId = window.setInterval(() => {
      void loadOperationDetails(selectedOperationId, tokenHash)
    }, 1500)

    return () => window.clearInterval(intervalId)
  }, [isAuthenticated, loadOperationDetails, selectedOperationId, tokenHash])

  const handleLogin = async () => {
    const trimmed = password.trim()
    if (!trimmed) {
      setAuthError('Podaj haslo administratora.')
      return
    }

    setAuthLoading(true)
    setAuthError(null)
    try {
      const hash = await sha256Hex(trimmed)
      const status = await getAdminStatus(hash)
      if (!status.configured) {
        throw new Error('Haslo administratora nie jest skonfigurowane na backendzie.')
      }
      setTokenHash(hash)
      persistToken(hash)
      setPassword('')
      await loadOperations(hash, false)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Logowanie nie powiodlo sie.'
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
    setSelectedOperationId('')
    setSelectedOperation(null)
    setOperationsError(null)
    setAuthError(null)
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
      await loadOperationDetails(operation.id, tokenHash)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Operacja zakonczona bledem.'
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
                  Zaloguj sie haslem administratora, aby uruchamiac pobieranie do S3 i indeksowanie.
                </Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
                  <TextField
                    label="Haslo admina"
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
                    {actionLoading === 'full' ? 'Uruchamianie...' : 'Pelny ingest'}
                  </Button>
                </Stack>

                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Operacje: ${operations.length}`}
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
                    Odswiez
                  </Button>
                </Stack>

                {operationsError && <Alert severity="error">{operationsError}</Alert>}

                <Divider />

                <Stack spacing={1.25}>
                  <TextField
                    select
                    size="small"
                    label="Wybierz operacje"
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
                          : 'Brak logow dla tej operacji.'}
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
