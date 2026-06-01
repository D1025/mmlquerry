import MenuBookRoundedIcon from '@mui/icons-material/MenuBookRounded'
import ManageAccountsRoundedIcon from '@mui/icons-material/ManageAccountsRounded'
import TerminalRoundedIcon from '@mui/icons-material/TerminalRounded'
import {
  AppBar,
  Button,
  FormControl,
  InputLabel,
  MenuItem,
  Select,
  Stack,
  Toolbar,
  Typography,
} from '@mui/material'
import type { QueryVersionOption } from '../queryApi'
import type { AppPage } from '../queryWorkbenchUtils'

interface WorkbenchHeaderProps {
  pageRoute: AppPage
  onNavigate: (page: AppPage) => void
  versionOptions: QueryVersionOption[]
  selectedVersion: string
  versionStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  versionError: string | null
  onVersionChange: (version: string) => void
}

export function WorkbenchHeader({
  pageRoute,
  onNavigate,
  versionOptions,
  selectedVersion,
  versionStatus,
  versionError,
  onVersionChange,
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
              Edytor zapytań i analiza wyników MML
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
            Przykłady
          </Button>
          <Button
            size="small"
            variant={pageRoute === 'admin' ? 'contained' : 'outlined'}
            startIcon={<ManageAccountsRoundedIcon fontSize="small" />}
            onClick={() => onNavigate('admin')}
          >
            Admin
          </Button>
          <FormControl size="small" sx={{ minWidth: 220 }}>
            <InputLabel id="header-version-select-label">Wersja danych</InputLabel>
            <Select
              labelId="header-version-select-label"
              label="Wersja danych"
              value={selectedVersion || ''}
              onChange={(event) => onVersionChange(event.target.value)}
              disabled={versionStatus === 'loading' || versionOptions.length === 0}
            >
              {!selectedVersion && (
                <MenuItem value="">
                  <em>Domyślna</em>
                </MenuItem>
              )}
              {versionOptions.map((versionOption) => (
                <MenuItem key={versionOption.version} value={versionOption.version}>
                  {versionOption.version} ({versionOption.itemCount})
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          {versionError && (
            <Typography variant="caption" color="error.main" sx={{ maxWidth: 240 }}>
              Błąd wersji: {versionError}
            </Typography>
          )}
        </Stack>
      </Toolbar>
    </AppBar>
  )
}
