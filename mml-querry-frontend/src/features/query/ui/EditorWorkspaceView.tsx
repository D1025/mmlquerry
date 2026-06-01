import ChevronLeftRoundedIcon from '@mui/icons-material/ChevronLeftRounded'
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded'
import { IconButton, Paper, Stack } from '@mui/material'
import type { ComponentProps } from 'react'
import { QueryEditorCard } from './QueryEditorCard'
import { QueryResultsCard } from './QueryResultsCard'
import { SyntaxGuideCard } from './SyntaxGuideCard'

interface EditorWorkspaceViewProps {
  queryEditorProps: ComponentProps<typeof QueryEditorCard>
  syntaxGuideProps: ComponentProps<typeof SyntaxGuideCard>
  resultsProps: ComponentProps<typeof QueryResultsCard>
  isSyntaxPanelCollapsed: boolean
  onToggleSyntaxPanel: () => void
}

export function EditorWorkspaceView({
  queryEditorProps,
  syntaxGuideProps,
  resultsProps,
  isSyntaxPanelCollapsed,
  onToggleSyntaxPanel,
}: EditorWorkspaceViewProps) {
  return (
    <Stack spacing={2.25} sx={{ position: 'relative' }}>
      <QueryEditorCard {...queryEditorProps} />
      <QueryResultsCard {...resultsProps} />

      <Stack
        sx={{
          position: 'fixed',
          right: 8,
          top: { xs: 76, md: 84 },
          bottom: 8,
          width: { xs: 'min(92vw, 420px)', lg: 420 },
          transform: isSyntaxPanelCollapsed ? 'translateX(calc(100% + 16px))' : 'translateX(0)',
          transition: 'transform 240ms ease',
          zIndex: (theme) => theme.zIndex.drawer + 1,
          pointerEvents: isSyntaxPanelCollapsed ? 'none' : 'auto',
        }}
      >
        <Stack direction="row" sx={{ justifyContent: 'flex-end', mb: 0.75 }}>
          <Paper variant="outlined">
            <IconButton
              size="small"
              onClick={onToggleSyntaxPanel}
              aria-label="Zwiń panel składni"
              title="Zwiń panel składni"
            >
              <ChevronRightRoundedIcon fontSize="small" />
            </IconButton>
          </Paper>
        </Stack>
        <Stack sx={{ flexGrow: 1, minHeight: 0 }}>
          <SyntaxGuideCard {...syntaxGuideProps} />
        </Stack>
      </Stack>

      {isSyntaxPanelCollapsed && (
        <Paper
          variant="outlined"
          sx={{
            position: 'fixed',
            right: 8,
            top: '50%',
            transform: 'translateY(-50%)',
            zIndex: (theme) => theme.zIndex.drawer + 2,
          }}
        >
          <IconButton
            size="small"
            onClick={onToggleSyntaxPanel}
            aria-label="Rozwiń panel składni"
            title="Rozwiń panel składni"
          >
            <ChevronLeftRoundedIcon fontSize="small" />
          </IconButton>
        </Paper>
      )}
    </Stack>
  )
}
