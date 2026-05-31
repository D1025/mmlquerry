import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded'
import { Button, Card, CardContent, Grid, Stack, Typography } from '@mui/material'
import type { CategorizedExampleSection } from '../queryExamples'

interface ExamplesLibraryViewProps {
  sections: CategorizedExampleSection[]
  onUseQuery: (query: string) => void
  onBackToEditor: () => void
}

export function ExamplesLibraryView({
  sections,
  onUseQuery,
  onBackToEditor,
}: ExamplesLibraryViewProps) {
  return (
    <Card sx={{ borderRadius: 1 }}>
      <CardContent sx={{ p: { xs: 2, md: 2.5 }, '&:last-child': { pb: { xs: 2, md: 2.5 } } }}>
        <Stack spacing={2.25}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={1.5}
            sx={{ justifyContent: 'space-between', alignItems: { xs: 'flex-start', sm: 'center' } }}
          >
            <Stack spacing={0.2}>
              <Typography variant="h6">Biblioteka przykładowych zapytań</Typography>
              <Typography variant="body2" color="text.secondary">
                Każdy wpis ma opis i gotowe query. Użyj przycisku przy tytule elementu, aby
                przejść do edytora z uzupełnionym zapytaniem.
              </Typography>
            </Stack>
            <Button variant="outlined" startIcon={<PlayArrowRoundedIcon />} onClick={onBackToEditor}>
              Wróć do edytora
            </Button>
          </Stack>

          <Stack spacing={2}>
            {sections.map((section) => (
              <Stack key={section.category.id} spacing={1.25}>
                <Typography variant="h6">{section.category.title}</Typography>
                <Typography variant="body2" color="text.secondary">
                  {section.category.description}
                </Typography>

                <Grid container spacing={1.5}>
                  {section.examples.map((example) => (
                    <Grid key={example.id} size={{ xs: 12, md: 6 }}>
                      <Stack
                        spacing={1}
                        sx={{
                          p: 1.5,
                          height: '100%',
                          border: '1px solid',
                          borderColor: 'divider',
                          borderRadius: 1,
                          backgroundColor: 'background.paper',
                        }}
                      >
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                          <Typography variant="subtitle1" sx={{ fontWeight: 700, flexGrow: 1 }}>
                            {example.title}
                          </Typography>
                          <Button
                            size="small"
                            variant="contained"
                            endIcon={<PlayArrowRoundedIcon fontSize="small" />}
                            onClick={() => onUseQuery(example.query)}
                            sx={{ minWidth: 0, px: 1.25 }}
                          >
                            Użyj
                          </Button>
                        </Stack>
                        <Typography variant="body2" color="text.secondary">
                          {example.description}
                        </Typography>
                        <Typography
                          component="pre"
                          sx={{
                            p: 1.1,
                            m: 0,
                            fontFamily:
                              'ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace',
                            fontSize: 13,
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-word',
                            border: '1px solid',
                            borderColor: 'divider',
                            borderRadius: 1,
                            backgroundColor: 'background.default',
                          }}
                        >
                          {example.query}
                        </Typography>
                      </Stack>
                    </Grid>
                  ))}
                </Grid>
              </Stack>
            ))}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  )
}
