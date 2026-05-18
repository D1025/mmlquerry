const KEYWORD_HELP: Record<string, string> = {
  and: 'Operator logiczny: wynik wspólny dwóch zapytań.',
  or: 'Operator logiczny: suma wyników dwóch zapytań.',
  butnot: 'Operator logiczny: elementy z lewego zapytania, których nie ma w prawym.',
  not: 'Negacja zapytania: usuwa elementy spełniające warunek.',
  ref: 'Pipeline: przechodzi do elementów referencjonowanych.',
  occur: 'Pipeline: przechodzi do wystąpień/powiązań occurrence.',
  definition: 'Pipeline: przechodzi do definicji powiązanych z wynikiem.',
  notation: 'Pipeline: przechodzi po relacjach notacyjnych.',
  redef: 'Pipeline: filtr/przejście po relacji redefinicji.',
  origin: 'Pipeline: wraca do oryginalnego elementu (origin).',
  copy: 'Pipeline: przechodzi do kopii elementu.',
  'termtype ref': 'Pipeline: relacja termtype + ref.',
  'deftype ref': 'Pipeline: relacja deftype + ref.',
  'main mode': 'Pipeline: przechodzi do głównego mode.',
  'main functor': 'Pipeline: przechodzi do głównego funktora.',
  nodes:
    'Pipeline: wybiera nody XML i pozwala filtrować potomków przez where/has/redefine. Sciezki: A/B (dziecko), A//B (dowolna glebia), A/3/B (dokladna glebia).',
  reverse: 'Pipeline: odwraca kolejność wyników.',
  invert: 'Pipeline: odwraca kolejność wyników (alias reverse).',
  whereeq: 'Pipeline: filtruje po dokładnej liczbie wyników operacji, np. whereeq(ref,2).',
  wherege: 'Pipeline: filtruje po liczbie >= N, np. wherege(ref,2).',
  wherele: 'Pipeline: filtruje po liczbie <= N.',
  wheregt: 'Pipeline: filtruje po liczbie > N.',
  wherelt: 'Pipeline: filtruje po liczbie < N.',
  numeq: 'Pipeline: filtruje po wartości liczbowej równej N (analiza wyrażeń +, *, |^, nawiasy).',
  numge: 'Pipeline: filtruje po wartości liczbowej >= N.',
  numle: 'Pipeline: filtruje po wartości liczbowej <= N.',
  numgt: 'Pipeline: filtruje po wartości liczbowej > N.',
  numlt: 'Pipeline: filtruje po wartości liczbowej < N.',
  spelling: 'Atrybut XML: tekstowy zapis symbolu/patternu.',
  negated:
    "Słowo kluczowe skrótu 'negated adjective': oznacza zaprzeczony przymiotnik (nonocc='true').",
  adjective:
    "Słowo kluczowe skrótu 'negated adjective': łączy się ze spelling i mapuje na Attribute nonocc='true'.",
  occurs: 'Atrybut XML: flaga, czy element występuje/redefiniuje.',
  xmlid: 'Atrybut XML: identyfikator elementu w dokumencie.',
  position: 'Atrybut XML: pozycja elementu w tekście źródłowym.',
}

function normalizeKeywordToken(token: string): string {
  return token.trim().toLowerCase().replace(/\s+/g, ' ')
}

export function describeKeyword(
  token: string,
  type: 'operator' | 'pipeline' | 'attribute' | 'node',
): string {
  const normalized = normalizeKeywordToken(token)
  const known = KEYWORD_HELP[normalized]
  if (known) {
    return known
  }

  if (type === 'node') {
    return `Nod XML "${token}". Wstawia nazwę noda do zapytania (has/nodes).`
  }
  if (type === 'attribute') {
    return `Atrybut XML "${token}". Użyj np. w filtrze [${token}='wartość'].`
  }
  if (type === 'pipeline') {
    return `Operacja pipeline "${token}". Wstaw po znaku |, aby przekształcić bieżący wynik.`
  }
  return `Słowo kluczowe "${token}" używane w składni zapytań.`
}
