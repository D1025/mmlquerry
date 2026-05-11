import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit'
import {
  executeQuery,
  getSyntax,
  type ExecuteQueryRequest,
  type ExecuteQueryResponse,
  type SyntaxResponse,
} from './queryApi'

interface QueryState {
  queryText: string
  syntax: SyntaxResponse | null
  result: ExecuteQueryResponse | null
  syntaxStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  executeStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  syntaxError: string | null
  executeError: string | null
}

const initialState: QueryState = {
  queryText:
    "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']",
  syntax: null,
  result: null,
  syntaxStatus: 'idle',
  executeStatus: 'idle',
  syntaxError: null,
  executeError: null,
}

export const fetchSyntax = createAsyncThunk('query/fetchSyntax', async () => getSyntax())

export const runQuery = createAsyncThunk('query/runQuery', async (request: ExecuteQueryRequest) =>
  executeQuery(request),
)

const querySlice = createSlice({
  name: 'query',
  initialState,
  reducers: {
    setQueryText: (state, action: PayloadAction<string>) => {
      state.queryText = action.payload
      if (state.executeStatus === 'failed') {
        state.executeStatus = 'idle'
      }
      state.executeError = null
    },
    setQueryResult: (state, action: PayloadAction<ExecuteQueryResponse>) => {
      state.executeStatus = 'succeeded'
      state.executeError = null
      state.result = action.payload
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchSyntax.pending, (state) => {
        state.syntaxStatus = 'loading'
        state.syntaxError = null
      })
      .addCase(fetchSyntax.fulfilled, (state, action) => {
        state.syntaxStatus = 'succeeded'
        state.syntax = action.payload
      })
      .addCase(fetchSyntax.rejected, (state, action) => {
        state.syntaxStatus = 'failed'
        state.syntaxError = action.error.message ?? 'Unable to fetch syntax information.'
      })
      .addCase(runQuery.pending, (state) => {
        state.executeStatus = 'loading'
        state.executeError = null
      })
      .addCase(runQuery.fulfilled, (state, action) => {
        state.executeStatus = 'succeeded'
        state.result = action.payload
      })
      .addCase(runQuery.rejected, (state, action) => {
        state.executeStatus = 'failed'
        state.executeError = action.error.message ?? 'Unable to execute query.'
      })
  },
})

export const { setQueryText, setQueryResult } = querySlice.actions
export default querySlice.reducer
