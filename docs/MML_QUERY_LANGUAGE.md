# MML Query Language - Operatory i Slowa Kluczowe

Ten dokument zawiera wylacznie dokladny opis operatorow i slow kluczowych jezyka zapytan.
Opis odpowiada aktualnej implementacji parsera ANTLR i evaluatora backendu.

## 1. Operatory logiczne

## `and`

- Rola: przeciecie dwoch zbiorow wynikow.
- Semantyka: zwraca tylko rekordy wystepujace jednoczesnie po lewej i po prawej stronie.
- Przyklad: `A and B`.

## `or`

- Rola: suma dwoch zbiorow wynikow.
- Semantyka: zwraca rekordy z lewej i prawej strony (z deduplikacja po `item_id`/`lib_id`).
- Przyklad: `A or B`.

## `butnot`

- Rola: roznica zbiorow.
- Semantyka: zwraca rekordy z lewej strony, ktorych nie ma po prawej.
- Przyklad: `A butnot B`.

## `not`

- Rola: negacja.
- Semantyka: zwraca dopelnienie wzgledem uniwersum elementow (`view_items`), nie wzgledem lokalnego podzbioru.
- Przyklad: `not A`.

## 2. Operator potoku

## `|`

- Rola: sekwencyjne laczenie operacji.
- Semantyka: wynik operacji po lewej jest wejsciem operacji po prawej.
- Przyklad: `ABCMIZ_0:func 5 | ref | occur`.

## 3. Operatory pipeline (relacyjne i transformacyjne)

## `ref`

- Wejscie: itemy (np. twierdzenia, definicje, konstruktory).
- Wyjscie: konstruktory wskazane przez relacje `item_constructor_ref.role='ref'`.

## `occur` / `occurs`

- Wejscie: zwykle konstruktory.
- Wyjscie: elementy referujace te konstruktory przez relacje `role='ref'` (kierunek odwrotny do `ref`).

## `definition` / `definitions`

- Wejscie: konstruktory.
- Wyjscie: powiazane definicje przez tabele `constructor_definition`.

## `notation`

- Wejscie: konstruktory.
- Wyjscie: powiazane notacje przez tabele `notation_constructor`.

## `redef` / `redefinition`

- Wejscie: konstruktory.
- Wyjscie: suma wynikow `origin` i `copy` (konstruktory z relacji redefinicji).

## `origin` / `original`

- Wejscie: copy-konstruktory.
- Wyjscie: ich origin-konstruktory z `constructor_redefinition.origin_item_id`.

## `copy` / `copied`

- Wejscie: origin-konstruktory.
- Wyjscie: ich copy-konstruktory z `constructor_redefinition.copy_item_id`.

## `termtype ref`

- Wejscie: itemy.
- Wyjscie: konstruktory wskazane przez `item_constructor_ref.role='termtype_ref'`.

## `deftype ref`

- Wejscie: itemy.
- Wyjscie: konstruktory wskazane przez `item_constructor_ref.role='deftype_ref'`.

## `main mode`

- Wejscie: rejestracje.
- Wyjscie: glowny konstruktor trybu (`registration.main_mode_constructor_id`).

## `main functor`

- Wejscie: rejestracje.
- Wyjscie: glowny konstruktor funktora (`registration.main_func_constructor_id`).

## `filter('...')`

- Rola: filtrowanie wynikow.
- Tryb `key=value`: porownanie exact, case-insensitive.
- Tryb `needle`: wyszukiwanie `contains` po zlozonym tekscie rekordu.
- Obslugiwane klucze w `key=value`:
  - `article`, `article_name`
  - `lib`, `lib_id`
  - `kind`
  - `subkind`
  - `node`, `node_type`
  - `text`, `raw_text`, `text_content`
  - `position`, `text_position`

## `grep('regex')`

- Rola: filtrowanie regexem (Java `Pattern`, `CASE_INSENSITIVE`).
- Przeszukiwane pola:
  - `raw_text`/`text_content`
  - `lib_id`
  - `article_name`
- Przy blednym regexie zwracany jest blad `Invalid grep regex`.

## `reverse`

- Rola: odwrocenie kolejnosci rekordow.

## `invert`

- Rola: odwrocenie kolejnosci rekordow.
- Uwaga: aktualnie dziala tak samo jak `reverse`.

## 4. Operatory kardynalnosci

## `whereeq(op,n)`

- Warunek: liczba wynikow operacji `op` dla rekordu jest rowna `n`.

## `wherege(op,n)`

- Warunek: liczba wynikow operacji `op` dla rekordu jest >= `n`.

## `wherele(op,n)`

- Warunek: liczba wynikow operacji `op` dla rekordu jest <= `n`.

## `wheregt(op,n)`

- Warunek: liczba wynikow operacji `op` dla rekordu jest > `n`.

## `wherelt(op,n)`

- Warunek: liczba wynikow operacji `op` dla rekordu jest < `n`.

Dozwolone `op`:

- `ref`
- `occur` / `occurs`
- `definition` / `definitions`
- `notation`
- `termtype ref`
- `deftype ref`

## 5. Slowa kluczowe skladni bazowej

## `list`

- Rozpoczyna zapytanie listujace elementy.

## `of`

- Laczy `list` z typem listy (`list of theorem`).

## `in`

- Ogranicza zapytanie do zrodla (`ARTICLE_NAME` lub `*`).

## `article`

- Zapytanie o wszystkie itemy z artykulu (`article ABCMIZ_0`).

## `where`

- Wprowadza warunek specjalny dla theorem-infix query.

## `proposition`

- Czesci skladni special query: `proposition has infix-term ...`.

## `has`

- Czesci skladni special query: `proposition has infix-term ...`.

## `infix-term`

- Wskazuje warunek na nod `Infix-Term` w `Proposition`.

## `absolutepatternmmlid`

- Nazwa atrybutu filtrowania infix-term.
- Uzycie: `infix-term[absolutepatternmmlid='RELAT_1:3']`.
- W theorem-infix query pierwszy predykat musi zawierac ten atrybut.

## 6. Slowa kluczowe typow list (`list of ...`)

## `constructor` / `constructors`

- Zwraca konstruktory (`view_constructors`).

## `theorem` / `theorems`

- Zwraca twierdzenia (`view_theorems`).

## `definition` / `definitions`

- Zwraca definicje (`view_definitions`).

## `statement` / `statements`

- Zwraca statements (`view_statements`).

## `registration` / `registrations`

- Zwraca rejestracje (`view_registrations`).

## `all`

- Zwraca wszystkie elementy (`view_items`) z mapowaniem `node_type`.

## 7. Slowa kluczowe typow itemow (`ARTICLE:kind NUMBER`)

## `func`

- Typ konstruktora: funktor.

## `pred`

- Typ konstruktora: predykat.

## `attr`

- Typ konstruktora: atrybut.

## `mode`

- Typ konstruktora: mode.

## `sel`

- Typ konstruktora: selector.

## `aggr`

- Typ konstruktora: aggregate.

## `struct`

- Typ konstruktora: structure.

## `th`

- Typ statement: theorem.

## `def`

- Typ statement: definicja.

## `dfs`

- Typ statement: definiens/definition-related statement.

## `sch`

- Typ statement: scheme.

## 8. Znaki specjalne i symbole skladni

## `(`

- Otwiera nawias grupujacy.

## `)`

- Zamyka nawias grupujacy.

## `[`

- Otwiera filtr atrybutu dla `infix-term`.

## `]`

- Zamyka filtr atrybutu dla `infix-term`.

## `=`

- Operator przypisania wartosci w `key=value` i filtrze atrybutu.

## `:`

- Rozdziela artykul od typu itemu (`ABCMIZ_0:func 5`).

## `,`

- Rozdziela argumenty w `where*`, np. `wherege(ref,2)`.

## `*`

- Oznacza wszystkie artykuly w `in *`.

## 9. Priorytet operatorow

Od najwyzszego:

1. Nawiasy `(...)`
2. Potok `|`
3. `not`
4. `and`
5. `or` i `butnot`

## 10. Minimalne przyklady operatorow i slow kluczowych

```text
list of theorem in ABCMIZ_0
ABCMIZ_0:func 5 | ref | occur
list of constructor | wherege(ref,2)
list of theorem | filter('article=ABCMIZ_0')
list of theorem | grep('field|lattice')
not (list of registration in ABCMIZ_0)
list of theorem where proposition has infix-term[absolutepatternmmlid='RELAT_1:3'] and proposition has infix-term
```
