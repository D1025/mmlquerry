import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { CssBaseline, ThemeProvider, createTheme } from '@mui/material'
import { Provider } from 'react-redux'
import './index.css'
import App from './App.tsx'
import { store } from './app/store'

const theme = createTheme({
  palette: {
    background: {
      default: '#f6f8fb',
      paper: '#ffffff',
    },
    primary: {
      main: '#315f98',
      dark: '#234b7a',
    },
    success: {
      main: '#2f7d5c',
    },
    warning: {
      main: '#ad6b16',
    },
  },
  shape: {
    borderRadius: 4,
  },
  typography: {
    fontFamily:
      'Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    h4: {
      fontSize: '1.5rem',
      fontWeight: 700,
    },
    h6: {
      fontSize: '1rem',
      fontWeight: 700,
    },
    button: {
      fontWeight: 700,
      textTransform: 'none',
    },
  },
  components: {
    MuiButton: {
      defaultProps: {
        disableElevation: true,
      },
      styleOverrides: {
        root: {
          minHeight: 36,
        },
      },
    },
    MuiCard: {
      defaultProps: {
        elevation: 0,
      },
      styleOverrides: {
        root: {
          border: '1px solid rgba(25, 42, 62, 0.12)',
        },
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: {
          minHeight: 32,
          minWidth: 32,
        },
      },
    },
    MuiCssBaseline: {
      styleOverrides: {
        body: {
          backgroundImage:
            'linear-gradient(180deg, rgba(255,255,255,0.86) 0, rgba(246,248,251,0) 260px)',
        },
        '*:focus-visible': {
          outline: '2px solid #315f98',
          outlineOffset: 2,
        },
      },
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Provider store={store}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <App />
      </ThemeProvider>
    </Provider>
  </StrictMode>,
)
