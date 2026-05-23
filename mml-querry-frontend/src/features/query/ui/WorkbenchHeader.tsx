import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import ManageAccountsRoundedIcon from '@mui/icons-material/ManageAccountsRounded'
import TerminalRoundedIcon from '@mui/icons-material/TerminalRounded'
import { AppBar, Button, Stack, Toolbar, Typography } from '@mui/material'
import type { AppPage } from '../queryWorkbenchUtils'

interface WorkbenchHeaderProps {
  pageRoute: AppPage
  onNavigate: (page: AppPage) => void
}

export function WorkbenchHeader({
  pageRoute,
  onNavigate,
}: WorkbenchHeaderProps) {
  return (
    <AppBar
      position="sticky"
      color="inherit"
      elevation={0}
      sx={{
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: 'rgba(255,255,255,0.9)',
        backdropFilter: 'blur(12px)',
      }}
    >
      <Toolbar
        sx={{
          gap: 2,
          minHeight: { xs: 68, md: 60 },
          px: { xs: 2, md: 3 },
          flexWrap: 'wrap',
        }}
      >
        <Stack direction="row" spacing={1.25} sx={{ alignItems: 'center', minWidth: 260 }}>
          <Stack
            sx={{
              width: 38,
              height: 38,
              display: 'grid',
              placeItems: 'center',
              borderRadius: 1,
              background:
                'linear-gradient(145deg, rgba(15,76,129,1) 0%, rgba(10,124,107,1) 100%)',
              color: 'primary.contrastText',
            }}
          >
            <TerminalRoundedIcon fontSize="small" />
          </Stack>
          <Stack spacing={0.1}>
            <Typography variant="h4">Mizar Query Workbench</Typography>
            <Typography variant="caption" color="text.secondary">
              Edytor zapytan i analiza wynikow MML
            </Typography>
          </Stack>
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
            onClick={() => onNavigate('editor')}
          >
            Edytor
          </Button>
          <Button
            size="small"
            variant={pageRoute === 'examples' ? 'contained' : 'outlined'}
            startIcon={<MenuBookRoundedIcon fontSize="small" />}
            onClick={() => onNavigate('examples')}
          >
            Przyklady
          </Button>
          <Button
            size="small"
            variant={pageRoute === 'admin' ? 'contained' : 'outlined'}
            startIcon={<ManageAccountsRoundedIcon fontSize="small" />}
            onClick={() => onNavigate('admin')}
          >
            Admin
          </Button>
        </Stack>
      </Toolbar>
    </AppBar>
  )
}
