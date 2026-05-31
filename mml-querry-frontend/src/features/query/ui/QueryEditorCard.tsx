import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import StopRoundedIcon from '@mui/icons-material/StopRounded'
import {
  Alert,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  List,
  ListItemButton,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import type { ChangeEvent, KeyboardEvent, RefObject } from 'react'
import type { QueryValidationResult } from '../queryAssist'
import type { SuggestionPosition } from '../queryWorkbenchUtils'

interface QueryEditorCardProps {
  queryText: string
  queryPlaceholder: string
  validation: QueryValidationResult
  executeError: string | null
  executeStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  canExecute: boolean
  canCancel: boolean
  queryInputRef: RefObject<HTMLTextAreaElement | HTMLInputElement | null>
  isSuggestionListOpen: boolean
  suggestionPosition: SuggestionPosition | null
  querySuggestions: string[]
  activeSuggestionIndex: number
  activeSuggestionId: string
  suggestionListId: string
  suggestionOptionIdPrefix: string
  onSelectSuggestion: (suggestion: string) => void
  onSuggestionHover: (index: number) => void
  onQueryChange: (event: ChangeEvent<HTMLTextAreaElement | HTMLInputElement>) => void
  onFocus: (event: { target: EventTarget | null }) => void
  onBlur: () => void
  onCursorChange: (event: { target: EventTarget | null }) => void
  onKeyDown: (event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => void
  onKeyUp: (event: KeyboardEvent<HTMLTextAreaElement | HTMLInputElement>) => void
  onScrollInput: () => void
  onRunQuery: () => void
  onCancelQuery: () => void
  onOpenExamples: () => void
}

export function QueryEditorCard({
  queryText,
  queryPlaceholder,
  validation,
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
  suggestionListId,
  suggestionOptionIdPrefix,
  onSelectSuggestion,
  onSuggestionHover,
  onQueryChange,
  onFocus,
  onBlur,
  onCursorChange,
  onKeyDown,
  onKeyUp,
  onScrollInput,
  onRunQuery,
  onCancelQuery,
  onOpenExamples,
}: QueryEditorCardProps) {
  const hasErrors = validation.errors.length > 0

  return (
    <Card sx={{ borderRadius: 1 }}>
      <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
        <Stack spacing={2.25}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1.5}
            sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' } }}
          >
            <Typography variant="h6">Query editor</Typography>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              <Chip
                size="small"
                color={hasErrors ? 'error' : 'success'}
                variant={hasErrors ? 'filled' : 'outlined'}
                label={hasErrors ? `${validation.errors.length} błędów` : 'Query OK'}
              />
              {validation.warnings.length > 0 && (
                <Chip
                  size="small"
                  color="warning"
                  variant="outlined"
                  label={`${validation.warnings.length} ostrzeżeń`}
                />
              )}
            </Stack>
          </Stack>

          <Stack sx={{ position: 'relative' }}>
            <TextField
              label="MML Query"
              value={queryText}
              onChange={onQueryChange}
              onFocus={onFocus}
              onBlur={onBlur}
              inputRef={queryInputRef}
              slotProps={{
                htmlInput: {
                  'aria-activedescendant': isSuggestionListOpen ? activeSuggestionId : undefined,
                  'aria-autocomplete': 'list',
                  'aria-controls': isSuggestionListOpen ? suggestionListId : undefined,
                  'aria-expanded': isSuggestionListOpen,
                  onClick: onCursorChange,
                  onKeyDown,
                  onKeyUp,
                  onScroll: onScrollInput,
                  onSelect: onCursorChange,
                },
              }}
              fullWidth
              multiline
              minRows={7}
              placeholder={queryPlaceholder}
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
                id={suggestionListId}
                elevation={8}
                role="listbox"
                sx={{
                  position: 'fixed',
                  top: suggestionPosition.top,
                  left: suggestionPosition.left,
                  zIndex: (theme) => theme.zIndex.tooltip,
                  width: 280,
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
                      id={`${suggestionOptionIdPrefix}-${index}`}
                      key={suggestion}
                      role="option"
                      selected={index === activeSuggestionIndex}
                      onMouseDown={(event) => event.preventDefault()}
                      onMouseEnter={() => onSuggestionHover(index)}
                      onClick={() => onSelectSuggestion(suggestion)}
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
          </Stack>

          {validation.errors.map((message) => (
            <Alert key={`query-error-${message}`} severity="error">
              {message}
            </Alert>
          ))}
          {validation.warnings.map((message) => (
            <Alert key={`query-warning-${message}`} severity="warning">
              {message}
            </Alert>
          ))}
          {executeError && <Alert severity="error">{executeError}</Alert>}

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
              onClick={onRunQuery}
              disabled={!canExecute}
            >
              Uruchom zapytanie
            </Button>
            <Button
              variant="outlined"
              color="warning"
              startIcon={<StopRoundedIcon />}
              onClick={onCancelQuery}
              disabled={!canCancel}
            >
              Przerwij zapytanie
            </Button>
            <Button variant="outlined" startIcon={<MenuBookRoundedIcon />} onClick={onOpenExamples}>
              Biblioteka przykładów
            </Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}
