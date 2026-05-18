export interface ExampleQueryDefinition {
  id: string
  title: string
  description: string
  query: string
}

export interface ExampleCategoryDefinition {
  id: string
  title: string
  description: string
}

export interface CategorizedExampleSection {
  category: ExampleCategoryDefinition
  examples: ExampleQueryDefinition[]
}

const EXAMPLE_CATEGORY_FLOW: ExampleCategoryDefinition[] = [
  {
    id: 'foundations',
    title: 'Podstawy i filtrowanie nodow',
    description:
      'Od prostych zapytan where proposition has ... do filtrowania po spelling i patternach.',
  },
  {
    id: 'negation',
    title: 'Negacja i logika',
    description:
      'Operatory not/and/or/butnot oraz negacja warunkow has/spelling/redefine.',
  },
  {
    id: 'nodes-redefine',
    title: 'Nodes, redefine i atrybuty',
    description:
      'Praca na pipeline nodes, skrotach redefine oraz predykatach opartych o atrybuty XML.',
  },
  {
    id: 'path-depth',
    title: 'Sciezki i glebokosc',
    description:
      'Nowa mechanika relacji A/B, A//B i A/N/B dla potomkow o roznej glebokosci.',
  },
  {
    id: 'symbols',
    title: 'Symbole i wystapienia',
    description:
      'Lista symboli, grupowanie occurrences oraz wyszukiwanie z wildcard i escapowaniem.',
  },
  {
    id: 'cardinality-numeric',
    title: 'Licznosc i porownania liczbowe',
    description:
      'Operatory where* i num* do analizy liczby wystapien i wartosci liczbowych.',
  },
  {
    id: 'composed',
    title: 'Zlozone scenariusze',
    description:
      'Laczenie wielu warunkow i operatorow w praktycznych zapytaniach analitycznych.',
  },
]

const EXAMPLE_CATEGORY_BY_ID: Record<string, string> = {
  'thesis-in-theorem': 'foundations',
  'infix-term-by-spelling': 'foundations',
  'infix-term-by-spelling-shorthand': 'foundations',
  'infix-term-two-patterns': 'foundations',
  'statements-plus-and-mul-by-spelling': 'foundations',
  'statements-plus-and-mul-by-patternid': 'foundations',
  'infix-term-negated': 'negation',
  'negation-partial': 'negation',
  'negation-butnot': 'negation',
  'nodes-has-any-not-spelling': 'negation',
  'nodes-not-spelling': 'negation',
  'nodes-not-has': 'negation',
  'nodes-not-redefine-shorthand': 'negation',
  'attribute-redefine': 'nodes-redefine',
  'nodes-redefine-shorthand': 'nodes-redefine',
  'nodes-redefine-false-shorthand': 'nodes-redefine',
  'nodes-spelling-shorthand': 'nodes-redefine',
  'negated-adjective-empty': 'nodes-redefine',
  'nodes-path-direct-child': 'path-depth',
  'nodes-path-any-depth': 'path-depth',
  'nodes-path-exact-depth': 'path-depth',
  'nodes-path-exact-depth-miss': 'path-depth',
  'scoped-path-any-depth': 'path-depth',
  'scoped-path-exact-depth': 'path-depth',
  'symbols-unique': 'symbols',
  'symbols-by-spelling-plus': 'symbols',
  'symbols-occurrences': 'symbols',
  'symbols-literal-wildcards': 'symbols',
  'cardinality-ref': 'cardinality-numeric',
  'node-cardinality-3-numerals': 'cardinality-numeric',
  'node-cardinality-3-numerals-proposition': 'cardinality-numeric',
  'numeric-gt-threshold': 'cardinality-numeric',
  'numeric-weird-encoding': 'cardinality-numeric',
  'general-3-numbers-and-symbols': 'composed',
  'general-3-numbers-2-infix-numgt200': 'composed',
}

export const EXAMPLE_QUERY_LIBRARY: ExampleQueryDefinition[] = [
  {
    id: 'thesis-in-theorem',
    title: 'Tezy w twierdzeniach',
    description: 'Wyszukuje twierdzenia, których propozycja zawiera nod Thesis.',
    query: 'list of theorem where proposition has Thesis',
  },
  {
    id: 'infix-term-by-spelling',
    title: 'InfixTerm po spelling',
    description: "Filtruje twierdzenia po konkretnym spelling w nodzie InfixTerm.",
    query: "list of theorem where proposition has InfixTerm[spelling='Element']",
  },
  {
    id: 'infix-term-by-spelling-shorthand',
    title: 'InfixTerm po spelling (skrot)',
    description:
      "Skrot zapisu: proposition has InfixTerm spelling 'Element' zamiast [spelling='Element'].",
    query: "list of theorem where proposition has InfixTerm spelling 'Element'",
  },
  {
    id: 'infix-term-negated',
    title: 'Negacja noda w where',
    description:
      "Negacja typu noda XML w warunku where: has not InfixTerm[spelling='Element'].",
    query: "list of theorem where proposition has not InfixTerm[spelling='Element']",
  },
  {
    id: 'infix-term-two-patterns',
    title: 'Dwa wzorce w jednej propozycji',
    description:
      'Szuka twierdzeń, gdzie w tej samej propozycji występują dwa różne absolutepatternmmlid.',
    query:
      "list of theorem where proposition has InfixTerm[absolutepatternmmlid='RELAT_1:3'] and proposition has InfixTerm[absolutepatternmmlid='XBOOLE_0:2']",
  },
  {
    id: 'statements-plus-and-mul-by-spelling',
    title: 'Stwierdzenia z + oraz = (ABCMIZ_0)',
    description:
      "Dla tego artykulu '+' jest kodowane jako NAT_1:1, a '=' jako XBOOLE_0:4. Zapytanie szuka obu w tej samej propozycji.",
    query:
      "list of statement where proposition has InfixTerm[absolutepatternmmlid='NAT_1:1'] and proposition has RelationFormula[absolutepatternmmlid='XBOOLE_0:4']",
  },
  {
    id: 'statements-plus-and-mul-by-patternid',
    title: 'Stwierdzenia z + oraz < (ABCMIZ_0)',
    description:
      "Wariant z porownaniem: '+' (NAT_1:1) i '<' (ORDERS_2:3) w tej samej propozycji.",
    query:
      "list of statement where proposition has InfixTerm[absolutepatternmmlid='NAT_1:1'] and proposition has RelationFormula[absolutepatternmmlid='ORDERS_2:3']",
  },
  {
    id: 'negation-butnot',
    title: 'Negacja przez butnot',
    description:
      'Różnica zbiorów: bierze wynik lewy i usuwa z niego wszystko, co zwraca prawa strona.',
    query: 'list of theorem butnot list of definition',
  },
  {
    id: 'negation-partial',
    title: 'Negacja fragmentu (and not)',
    description:
      'Neguje tylko prawą część warunku: element musi spełniać pierwszy warunek i nie spełniać drugiego.',
    query: "list of theorem and not (list of theorem where proposition has Thesis)",
  },
  {
    id: 'attribute-redefine',
    title: 'Attribute-Definition z Redefine',
    description:
      'Wyszukuje definicje, w których item ma Redefine[occurs=true] oraz pattern z określonym spelling.',
    query:
      "list of definition where item has Redefine[occurs='true'] and item has AttributePattern[spelling='Noetherian']",
  },
  {
    id: 'nodes-redefine-shorthand',
    title: 'Pipeline nodes + redefine',
    description:
      'Wersja pipeline: wybiera nody Item i filtruje potomków przez skrót redefine true.',
    query: "list of definition | nodes Item where redefine true and has *[spelling='Noetherian']",
  },
  {
    id: 'nodes-spelling-shorthand',
    title: 'Pipeline nodes + spelling (skrot)',
    description:
      "Skrot zapisu spelling: has * spelling 'Noetherian' oraz wariant globalny: spelling 'Noetherian'.",
    query: "list of definition | nodes Item where has * spelling 'Noetherian'",
  },
  {
    id: 'nodes-path-direct-child',
    title: 'Sciezka nodow A/B',
    description:
      'A/B oznacza relacje dziecko. Ten przyklad ma trafienia i pozwala szybko sprawdzic mechanike.',
    query: 'list of definition in ABCMIZ_0 | nodes Item where has Loci-Declaration/Qualified-Segments',
  },
  {
    id: 'nodes-path-any-depth',
    title: 'Sciezka nodow A//B',
    description:
      'A//B oznacza dowolna glebokosc potomka. W praktyce powinno zwrocic wyniki dla tego samego obszaru.',
    query: 'list of definition in ABCMIZ_0 | nodes Item where has Loci-Declaration//Variable',
  },
  {
    id: 'nodes-path-exact-depth',
    title: 'Sciezka nodow A/4/B',
    description:
      'A/N/B oznacza dokladna glebokosc. Tutaj Variable jest dokladnie 4 poziomy pod Loci-Declaration.',
    query: 'list of definition in ABCMIZ_0 | nodes Item where has Loci-Declaration/4/Variable',
  },
  {
    id: 'nodes-path-exact-depth-miss',
    title: 'Sciezka nodow A/3/B (kontrola)',
    description:
      'Wariant kontrolny: zla glebokosc powinna zwrocic 0 wynikow, co potwierdza dzialanie filtra dokladnej glebi.',
    query: 'list of definition in ABCMIZ_0 | nodes Item where has Loci-Declaration/3/Variable',
  },
  {
    id: 'scoped-path-any-depth',
    title: 'Proposition + sciezka A//B',
    description: 'Nowa skladnia dziala tez w where proposition has ...',
    query: 'list of statement in ABCMIZ_0 where proposition has Universal-Quantifier-Formula//Variable',
  },
  {
    id: 'scoped-path-exact-depth',
    title: 'Proposition + sciezka A/4/B',
    description: 'Wariant z dokladna glebia w scoped where.',
    query: 'list of statement in ABCMIZ_0 where proposition has Universal-Quantifier-Formula/4/Variable',
  },
  {
    id: 'nodes-has-any-not-spelling',
    title: 'Pipeline nodes + has * not spelling',
    description:
      "Negacja spelling przypieta do predykatu has: has * not spelling 'Noetherian'.",
    query: "list of definition | nodes Item where has * not spelling 'Noetherian'",
  },
  {
    id: 'nodes-not-spelling',
    title: 'Pipeline nodes + not spelling',
    description:
      "Negacja spelling w where: odfiltrowuje nody, dla ktorych istnieje spelling 'Noetherian'.",
    query: "list of definition | nodes Item where not spelling 'Noetherian'",
  },
  {
    id: 'nodes-not-has',
    title: 'Pipeline nodes + not has',
    description:
      "Negacja typu noda: not has / has not, np. brak Redefine[occurs='true'] wsrod potomkow.",
    query: "list of definition | nodes Item where not has Redefine[occurs='true']",
  },
  {
    id: 'nodes-not-redefine-shorthand',
    title: 'Pipeline nodes + redefine (skrot negacji)',
    description:
      "Negacja logiczna: not redefine true (rownowazne not has Redefine[occurs='true']); obejmuje tez przypadki bez noda Redefine.",
    query: 'list of definition | nodes Item where not redefine true',
  },
  {
    id: 'nodes-redefine-false-shorthand',
    title: 'Pipeline nodes + redefine false (scisle)',
    description:
      "Wariant scisly: wymaga obecnosci Redefine z occurs='false' (bez przypadkow, gdzie Redefine nie istnieje).",
    query: 'list of definition | nodes Item where redefine false',
  },
  {
    id: 'symbols-unique',
    title: 'Lista symboli',
    description: 'Zwraca listę nodów symboli (*-Pattern) jako rekordy, analogicznie do innych list.',
    query: 'list of symbols',
  },
  {
    id: 'symbols-by-spelling-plus',
    title: 'Symbole po spelling',
    description: "Filtruje listę symboli po spelling, np. tylko dodawanie '+'.",
    query: "list of symbols where spelling '+'",
  },
  {
    id: 'negated-adjective-empty',
    title: 'Negated adjective: non empty',
    description:
      "Szuka zaprzeczonego przymiotnika empty. To nie jest 'not spelling empty' - to empty z nonocc='true' (czyli non empty).",
    query: "list of statement where proposition has negated adjective spelling 'empty'",
  },
  {
    id: 'symbols-occurrences',
    title: 'Wystąpienia symboli',
    description:
      'Grupuje symbole po spelling i liczy ile razy wystąpiły (niezależnie od pochodzenia noda).',
    query: 'occurrences of symbols',
  },
  {
    id: 'symbols-literal-wildcards',
    title: 'Literalne * i _',
    description: 'Wildcard to * oraz _. Aby szukac doslownie tych znakow, uzyj escape: \\* oraz \\_.',
    query: "occurrences of symbols | filter('spelling=A\\*B\\_C')",
  },
  {
    id: 'cardinality-ref',
    title: 'Minimalna liczba referencji',
    description: 'Filtruje listę twierdzeń do rekordów z co najmniej 2 wynikami operacji ref.',
    query: 'list of theorem | wherege(ref,2)',
  },
  {
    id: 'numeric-gt-threshold',
    title: 'Liczby rowne 1 (numeq)',
    description: 'Przyklad dzialajacy dla ABCMIZ_0: wyrazenia liczbowe rowne 1.',
    query: 'list of statement | numeq(1)',
  },
  {
    id: 'node-cardinality-3-numerals',
    title: 'Co najmniej 3 liczby',
    description: 'Nowy operator liczebnosci nodow: minimum 3 nody NumeralTerm w item.',
    query: 'list of statement | wherege(numeralterm,3)',
  },
  {
    id: 'node-cardinality-3-numerals-proposition',
    title: 'Co najmniej 3 liczby w Proposition',
    description: 'Wersja ze scopem proposition: minimum 3 nody NumeralTerm w jednej propozycji.',
    query: 'list of statement | wherege(proposition:numeralterm,3)',
  },
  {
    id: 'numeric-weird-encoding',
    title: 'Dziwne kodowanie: + oraz =',
    description:
      "Przyklad praktyczny dla ABCMIZ_0: najpierw filtr po strukturze (+ i =), potem opcjonalnie filtr liczbowy.",
    query:
      "list of statement where proposition has InfixTerm[absolutepatternmmlid='NAT_1:1'] and proposition has RelationFormula[absolutepatternmmlid='XBOOLE_0:4'] | numge(1)",
  },
  {
    id: 'general-3-numbers-and-symbols',
    title: 'Ogolnie: 3 liczby i symbole',
    description:
      'Wariant ogolny: co najmniej 3 liczby w jednej propozycji, dowolne symbole oraz wartosc numeryczna > 200.',
    query:
      "list of statement | wherege(proposition:numeralterm,3) and list of statement where item has InfixTerm | numgt(200)",
  },
  {
    id: 'general-3-numbers-2-infix-numgt200',
    title: '3 liczby + 2 symbole + >200',
    description:
      'Precyzyjny wariant: min. 3 NumeralTerm i min. 2 InfixTerm w jednej Proposition oraz wartosc numeryczna > 200.',
    query:
      'list of statement | wherege(proposition:numeralterm,3) | wherege(proposition:infixterm,2) | numgt(200)',
  },
]

export function getCategorizedExamples(): CategorizedExampleSection[] {
  const buckets = new Map<string, ExampleQueryDefinition[]>()
  for (const category of EXAMPLE_CATEGORY_FLOW) {
    buckets.set(category.id, [])
  }

  const uncategorized: ExampleQueryDefinition[] = []
  for (const example of EXAMPLE_QUERY_LIBRARY) {
    const categoryId = EXAMPLE_CATEGORY_BY_ID[example.id]
    if (!categoryId || !buckets.has(categoryId)) {
      uncategorized.push(example)
      continue
    }
    buckets.get(categoryId)?.push(example)
  }

  const sections = EXAMPLE_CATEGORY_FLOW
    .map((category) => ({
      category,
      examples: buckets.get(category.id) ?? [],
    }))
    .filter((section) => section.examples.length > 0)

  if (uncategorized.length > 0) {
    sections.push({
      category: {
        id: 'other',
        title: 'Pozostale',
        description: 'Przyklady, ktore nie zostaly przypisane do glownej sciezki.',
      },
      examples: uncategorized,
    })
  }

  return sections
}
