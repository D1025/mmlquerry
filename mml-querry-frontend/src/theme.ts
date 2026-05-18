import { createTheme } from '@mui/material'

export const appTheme = createTheme({
  palette: {
    mode: 'light',
    primary: {
      main: '#0f4c81',
      dark: '#0b3961',
      light: '#3d79ad',
    },
    secondary: {
      main: '#0a7c6b',
      dark: '#075c4f',
      light: '#41a696',
    },
    background: {
      default: '#eef3f9',
      paper: '#ffffff',
    },
    success: {
      main: '#2b7a55',
    },
    warning: {
      main: '#a86b1a',
    },
  },
  shape: {
    borderRadius: 2,
  },
  typography: {
    fontFamily:
      '"Sora", "Inter", ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h4: {
      fontSize: '1.3rem',
      fontWeight: 700,
      letterSpacing: '-0.01em',
    },
    h6: {
      fontSize: '1.02rem',
      fontWeight: 700,
    },
    button: {
      textTransform: 'none',
      fontWeight: 700,
    },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundImage:
            'radial-gradient(circle at 8% 0%, rgba(15,76,129,0.09), transparent 42%), radial-gradient(circle at 92% 8%, rgba(10,124,107,0.08), transparent 34%)',
          backgroundAttachment: 'fixed',
        },
        '*': {
          scrollbarWidth: 'thin',
          scrollbarColor: '#9aa6b2 #e3e8ef',
        },
        '*::-webkit-scrollbar': {
          width: '10px',
          height: '10px',
        },
        '*::-webkit-scrollbar-track': {
          background: '#e3e8ef',
          borderRadius: '2px',
        },
        '*::-webkit-scrollbar-thumb': {
          background: '#9aa6b2',
          borderRadius: '2px',
          border: '2px solid #e3e8ef',
        },
        '*::-webkit-scrollbar-thumb:hover': {
          background: '#7f8b98',
        },
        '*:focus-visible': {
          outline: '2px solid #0f4c81',
          outlineOffset: 2,
        },
      },
    },
    MuiCard: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          border: '1px solid rgba(15, 76, 129, 0.14)',
          boxShadow: '0 8px 22px rgba(15, 76, 129, 0.04)',
        },
      },
    },
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          minHeight: 38,
          borderRadius: 4,
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 3,
        },
      },
    },
  },
})
