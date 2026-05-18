import KeyboardArrowDownRoundedIcon from '@mui/icons-material/KeyboardArrowDownRounded'
import { Accordion, AccordionDetails, AccordionSummary, Alert, Button, Card, CardContent, Chip, LinearProgress, Stack, Tooltip, Typography } from '@mui/material'
import type { SyntaxResponse } from '../queryApi'

interface SyntaxGuideCardProps {
  syntax: SyntaxResponse | null
  syntaxStatus: 'idle' | 'loading' | 'succeeded' | 'failed'
  syntaxError: string | null
  showAllAttributes: boolean
  showAllNodes: boolean
  onToggleAllAttributes: () => void
  onToggleAllNodes: () => void
  onAppendToken: (token: string) => void
  describeKeyword: (token: string, type: 'operator' | 'pipeline' | 'attribute' | 'node') => string
}

function SectionHeader({ label }: { label: string }) {
  return (
    <Typography variant="subtitle2" color="text.secondary">
      {label}
    </Typography>
  )
}

export function SyntaxGuideCard({
  syntax,
  syntaxStatus,
  syntaxError,
  showAllAttributes,
  showAllNodes,
  onToggleAllAttributes,
  onToggleAllNodes,
  onAppendToken,
  describeKeyword,
}: SyntaxGuideCardProps) {
  return (
    <Card sx={{ borderRadius: 1, height: '100%' }}>
      <CardContent
        sx={{
          p: { xs: 2, md: 2.5 },
          '&:last-child': { pb: { xs: 2, md: 2.5 } },
          height: '100%',
          overflowY: 'auto',
        }}
      >
        <Stack spacing={2} sx={{ minHeight: '100%' }}>
          <Stack spacing={0.2}>
            <Typography variant="h6">Skladnia i operacje</Typography>
            <Typography variant="body2" color="text.secondary">
              Kliknij element, zeby wstawic go w miejscu kursora.
            </Typography>
          </Stack>

          {syntaxStatus === 'loading' && <LinearProgress />}
          {syntaxError && <Alert severity="error">{syntaxError}</Alert>}

          <Accordion defaultExpanded disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
            <AccordionSummary
              expandIcon={<KeyboardArrowDownRoundedIcon />}
              sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
            >
              <SectionHeader label="Operatory logiczne" />
            </AccordionSummary>
            <AccordionDetails sx={{ px: 0, pt: 0 }}>
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                {(syntax?.supportedOperators ?? []).map((operator) => (
                  <Tooltip key={operator} arrow title={describeKeyword(operator, 'operator')}>
                    <Chip label={operator} size="small" onClick={() => onAppendToken(operator)} />
                  </Tooltip>
                ))}
              </Stack>
            </AccordionDetails>
          </Accordion>

          <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
            <AccordionSummary
              expandIcon={<KeyboardArrowDownRoundedIcon />}
              sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
            >
              <SectionHeader label="Pipeline" />
            </AccordionSummary>
            <AccordionDetails sx={{ px: 0, pt: 0 }}>
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', maxHeight: 160, overflowY: 'auto', pr: 0.5 }}>
                {(syntax?.supportedPipelineOperations ?? []).map((operation) => (
                  <Tooltip key={operation} arrow title={describeKeyword(operation, 'pipeline')}>
                    <Chip
                      label={operation}
                      size="small"
                      variant="outlined"
                      onClick={() => onAppendToken(operation)}
                    />
                  </Tooltip>
                ))}
              </Stack>
            </AccordionDetails>
          </Accordion>

          {(syntax?.supportedAttributeNames?.length ?? 0) > 0 && (
            <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
              <AccordionSummary
                expandIcon={<KeyboardArrowDownRoundedIcon />}
                sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
              >
                <SectionHeader label="Atrybuty" />
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0, pt: 0 }}>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  {(showAllAttributes
                    ? (syntax?.supportedAttributeNames ?? [])
                    : (syntax?.supportedAttributeNames ?? []).slice(0, 12)
                  ).map((attribute) => (
                    <Tooltip key={attribute} arrow title={describeKeyword(attribute, 'attribute')}>
                      <Chip
                        label={attribute}
                        size="small"
                        variant="outlined"
                        onClick={() => onAppendToken(attribute)}
                      />
                    </Tooltip>
                  ))}
                </Stack>
                {(syntax?.supportedAttributeNames?.length ?? 0) > 12 && (
                  <Button size="small" sx={{ mt: 1 }} onClick={onToggleAllAttributes}>
                    {showAllAttributes ? 'Pokaz mniej' : 'Pokaz wszystkie'}
                  </Button>
                )}
              </AccordionDetails>
            </Accordion>
          )}

          {(syntax?.supportedNodeNames?.length ?? 0) > 0 && (
            <Accordion disableGutters elevation={0} sx={{ border: 0, '&::before': { display: 'none' } }}>
              <AccordionSummary
                expandIcon={<KeyboardArrowDownRoundedIcon />}
                sx={{ minHeight: 36, px: 0, '& .MuiAccordionSummary-content': { my: 0.5 } }}
              >
                <SectionHeader label="Nody XML" />
              </AccordionSummary>
              <AccordionDetails sx={{ px: 0, pt: 0 }}>
                <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
                  {(showAllNodes
                    ? (syntax?.supportedNodeNames ?? [])
                    : (syntax?.supportedNodeNames ?? []).slice(0, 14)
                  ).map((nodeName) => (
                    <Tooltip key={nodeName} arrow title={describeKeyword(nodeName, 'node')}>
                      <Chip
                        label={nodeName}
                        size="small"
                        variant="outlined"
                        onClick={() => onAppendToken(nodeName)}
                      />
                    </Tooltip>
                  ))}
                </Stack>
                {(syntax?.supportedNodeNames?.length ?? 0) > 14 && (
                  <Button size="small" sx={{ mt: 1 }} onClick={onToggleAllNodes}>
                    {showAllNodes ? 'Pokaz mniej' : 'Pokaz wszystkie'}
                  </Button>
                )}
              </AccordionDetails>
            </Accordion>
          )}
        </Stack>
      </CardContent>
    </Card>
  )
}
