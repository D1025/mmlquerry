# Mizar ESX Query Workbench

Aplikacja do pobierania, indeksowania i przeszukiwania plików ESX biblioteki Mizar Mathematical Library. Backend pobiera wersjonowane dane z GitHub Releases, zapisuje źródłowe pliki XML w MinIO, indeksuje ich strukturę w PostgreSQL i udostępnia REST API. Frontend zapewnia edytor własnego języka zapytań, tabelę wyników oraz panel administracyjny ingestu.

## Jak działa system

```text
GitHub Releases
      |
      | download plików .esx
      v
MinIO / S3
      |
      | parsowanie i indeksowanie XML
      v
PostgreSQL
      |
      | REST API + MML Query DSL
      v
React Query Workbench
```

Najważniejsze możliwości:

- pobieranie najnowszego release'u ESX z repozytorium GitHub,
- trwałe przechowywanie plików w storage zgodnym z S3,
- indeksowanie artykułów, elementów MML i całej struktury węzłów XML,
- przeszukiwanie twierdzeń, definicji, statements, rejestracji i symboli,
- filtrowanie po nazwach węzłów, atrybutach, ścieżkach XML, liczbach i `spelling`,
- wersjonowanie wyników według tagu release'u,
- panel administratora do uruchamiania downloadu, indeksowania i pełnego ingestu.

## Technologie

| Obszar | Narzędzia |
|---|---|
| Backend | Java 17, Spring Boot 3.5.7, Gradle, Spring Web, Spring Data JPA, JdbcClient |
| Parser zapytań | ANTLR 4.13, własna gramatyka MML Query |
| Dane | PostgreSQL 16, Flyway, MinIO/S3 |
| XML | SAX, dom4j, Woodstox, Jackson XML |
| Frontend | React 19, TypeScript 6, Vite 8, Redux Toolkit, Material UI 9 |
| Deployment | Docker Compose, nginx 1.29, Certbot / Let's Encrypt |
| Testy | JUnit 5, Spring Boot Test, H2, ESLint |

## Usługi Docker Compose

Lokalnie nie trzeba uruchamiać całego stacka. Certbot jest potrzebny wyłącznie na publicznym serwerze, a frontend może działać bez kontenera przez Vite.

| Usługa | Rola | Lokalny dev | Serwer |
|---|---|:---:|:---:|
| `postgres` | baza danych i indeks zapytań | tak | tak |
| `minio` | storage plików ESX | tak | tak |
| `minio-init` | jednorazowe utworzenie bucketa | tak | tak |
| `app` | backend Spring Boot | tak | tak |
| `frontend` | build React + nginx | opcjonalnie | tak |
| `certbot-init` | pierwszy certyfikat TLS | nie | tak |
| `certbot-renew` | odnawianie certyfikatu | nie | tak |

`postgres`, `minio`, `app` są wystawione lokalnie tylko na `127.0.0.1`. Publicznie porty `80` i `443` publikuje wyłącznie kontener `frontend`.

## Struktura repozytorium

```text
.
├── compose.yaml                 # definicja całego stacka
├── .env.template               # szablon konfiguracji
├── DEPLOY_LINUX.md             # rozszerzony runbook dla Linuxa
├── PROJECT_CONTEXT.md          # szczegółowy opis kodu i architektury
├── docs/
│   └── MML_QUERY_LANGUAGE.md   # dokumentacja języka zapytań
├── mizar-stack/                # backend Spring Boot
│   ├── src/main/antlr4/        # gramatyka ANTLR
│   ├── src/main/java/          # API, ingest, mapper i query engine
│   └── src/main/resources/     # konfiguracja i migracje Flyway
└── mml-querry-frontend/        # frontend React/Vite
```

Nazwa katalogu `mml-querry-frontend` zawiera historyczną literówkę i jest używana w komendach oraz konfiguracji Compose.

## Konfiguracja

Utwórz lokalny plik `.env` na podstawie szablonu:

```powershell
Copy-Item .env.template .env
```

Linux/macOS:

```bash
cp .env.template .env
```

Plik `.env` jest ignorowany przez Git. Przed uruchomieniem co najmniej sprawdź:

| Zmienna | Znaczenie |
|---|---|
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | dane bazy PostgreSQL |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` | dane dostępowe MinIO |
| `S3_BUCKET`, `S3_REGION` | bucket i region używane przez backend |
| `GITHUB_REPO` | repozytorium `owner/name` z release'ami ESX |
| `GITHUB_TOKEN` | opcjonalny token zwiększający limit GitHub API |
| `ADMIN_PASSWORD` | hasło do panelu administratora i chronionego API |
| `APP_CORS_ALLOWED_ORIGINS` | dozwolone originy wywołujące backend |
| `LETSENCRYPT_TARGET`, `LETSENCRYPT_EMAIL` | publiczna domena/IP i e-mail Certbota |

Główny, aktualnie używany przepływ ingestu działa przez GitHub Releases. Port backendu w Compose to `8080`; zmienna `APP_PORT` z szablonu nie zmienia obecnego mapowania portów.

## Uruchomienie lokalne

### Wariant zalecany: backend w Dockerze, frontend przez Vite

Wymagania:

- Docker Desktop albo Docker Engine z pluginem Compose,
- Node.js 22 i npm,
- Git.

1. Skopiuj `.env.template` do `.env` i dostosuj konfigurację.

2. Uruchom backend. Compose automatycznie dołączy jego zależności: PostgreSQL, MinIO i `minio-init`.

```bash
docker compose up -d --build app
```

3. W drugim terminalu uruchom frontend developerski:

```bash
cd mml-querry-frontend
npm ci
npm run dev
```

Adresy:

| Element | Adres |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend API | `http://localhost:8080` |
| Health check | `http://localhost:8080/actuator/health` |
| MinIO API | `http://localhost:9000` |
| MinIO Console | `http://localhost:9001` |

Vite przekazuje `/api/*` do `http://localhost:8080` i usuwa prefiks `/api`, dlatego nie trzeba ustawiać osobnego adresu API ani uruchamiać lokalnego nginx. Target proxy można zmienić przez `VITE_DEV_API_TARGET`.

W tym wariancie działają tylko `postgres`, `minio`, zakończony kodem `0` kontener `minio-init` oraz `app`. Kontenery `frontend`, `certbot-init` i `certbot-renew` pozostają wyłączone.

### Wariant alternatywny: wszystko lokalnie w Dockerze

Jeśli nie chcesz instalować Node.js, uruchom nginx razem z aplikacją, ale nadal pomiń Certbota:

```bash
docker compose up -d --build frontend
```

Compose uruchomi `frontend`, `app` i wszystkie zależności backendu. Aplikacja będzie dostępna przez HTTP pod `http://localhost`. Lokalny HTTPS nie jest w tym wariancie konfigurowany.

Nie używaj lokalnie samego `docker compose up`, jeśli nie potrzebujesz certyfikatu publicznego — ta komenda uruchamia również oba kontenery Certbota.

### Przydatne komendy lokalne

```bash
docker compose ps
docker compose logs -f app
docker compose up -d --build app
docker compose stop
docker compose down
```

`docker compose down` usuwa kontenery i sieć, ale pozostawia dane w nazwanych wolumenach. Nie używaj `docker compose down -v`, jeśli chcesz zachować bazę, pliki MinIO i certyfikaty.

## Uruchomienie na serwerze

Pełny stack serwerowy obejmuje wszystkie usługi z `compose.yaml`: bazę, MinIO, backend, frontend nginx oraz oba procesy Certbota.

### Wymagania

- publiczny serwer Linux,
- Docker Engine i Docker Compose plugin,
- otwarte porty TCP `80` i `443` w firewallu systemowym i u dostawcy serwera,
- dla domeny: rekord DNS `A` i opcjonalnie `AAAA` wskazujący na serwer.

Szczegóły instalacji Dockera na Ubuntu/Debian znajdują się w [DEPLOY_LINUX.md](DEPLOY_LINUX.md).

### 1. Pobranie i konfiguracja

```bash
git clone <URL_REPOZYTORIUM>
cd Magisterka
cp .env.template .env
```

Ustaw w `.env` unikalne, silne hasła i publiczny target:

```env
POSTGRES_PASSWORD=<silne-haslo-bazy>
MINIO_ROOT_PASSWORD=<silne-haslo-minio>
ADMIN_PASSWORD=<silne-haslo-administratora>

LETSENCRYPT_TARGET=mizar.example.com
LETSENCRYPT_EMAIL=admin@example.com
FRONTEND_API_BASE_URL=/api
APP_CORS_ALLOWED_ORIGINS=https://mizar.example.com
```

Dla wdrożenia bez domeny `LETSENCRYPT_TARGET` może wskazywać publiczny adres IP, zgodnie z konfiguracją Certbota w `compose.yaml`.

### 2. Start pełnego stacka

```bash
docker compose up -d --build
```

Po starcie nginx najpierw udostępnia HTTP i katalog challenge ACME. `certbot-init` pobiera certyfikat, a frontend przełącza konfigurację na HTTPS i przekierowuje ruch z portu `80` na `443`. `certbot-renew` cyklicznie sprawdza odnowienie certyfikatu.

### 3. Weryfikacja

```bash
docker compose ps
docker compose logs --no-log-prefix certbot-init
docker compose logs --tail=200 app frontend
curl -I http://mizar.example.com
curl -I https://mizar.example.com
```

Jeśli certyfikat nie został wydany, najpierw sprawdź DNS i publiczną dostępność portu `80`, a następnie ponów inicjalizację:

```bash
docker compose run --rm certbot-init
docker compose logs --no-log-prefix certbot-init
```

### 4. Aktualizacja i zatrzymanie

```bash
git pull
docker compose up -d --build
```

Zatrzymanie bez usuwania danych:

```bash
docker compose down
```

Dane PostgreSQL, MinIO i Let's Encrypt są przechowywane w nazwanych wolumenach Dockera.

## Pierwsze użycie i ingest danych

Samo uruchomienie stacka tworzy schemat bazy przez Flyway, ale nie pobiera automatycznie biblioteki ESX. Ingest trzeba uruchomić z panelu administratora:

- lokalny Vite: `http://localhost:5173/#/admin`,
- lokalny nginx: `http://localhost/#/admin`,
- serwer: `https://<LETSENCRYPT_TARGET>/#/admin`.

Zaloguj się wartością `ADMIN_PASSWORD` z `.env`. Panel pozwala uruchomić:

- **Download** — pobranie najnowszego release'u i zapis plików w MinIO,
- **Index** — indeksowanie wskazanego prefiksu S3,
- **Full ingest** — download i indeksowanie katalogu `esx_mml` w jednej operacji.

Postęp operacji jest przesyłany do panelu przez Server-Sent Events. Pełny ingest może być zasobo- i czasochłonny, zależnie od rozmiaru release'u.

## REST API

W trybie lokalnym API jest dostępne bezpośrednio pod `http://localhost:8080`. Przez frontend nginx lub Vite używany jest prefiks `/api`, który proxy usuwa przed przekazaniem requestu do backendu.

Najważniejsze endpointy:

| Metoda i endpoint | Rola |
|---|---|
| `POST /query/execute` | wykonanie zapytania DSL z pagingiem, sortowaniem i wersją danych |
| `GET /query/syntax` | obsługiwane operatory, nody, atrybuty i przykłady |
| `GET /query/versions` | dostępne wersje zindeksowanych danych |
| `GET /query/items/{itemId}/fragment` | pełniejszy fragment XML pobrany z S3 |
| `POST /query/warmup` | rozgrzanie wybranych zapytań |
| `POST /ingest/download` | download GitHub Release do S3; wymaga autoryzacji admina |
| `POST /ingest/index?prefix=...` | indeksowanie prefiksu S3; wymaga autoryzacji admina |
| `POST /ingest/full` | pełny ingest; wymaga autoryzacji admina |
| `GET /ingest/stats/latest` | statystyki ostatniego indeksowania; wymaga autoryzacji admina |
| `GET /admin/operations/stream` | status operacji administracyjnych przez SSE |

Przykładowe wykonanie zapytania:

```bash
curl -X POST http://localhost:8080/query/execute \
  -H "Content-Type: application/json" \
  -d '{"query":"list of theorem in ABCMIZ_0","page":0,"size":10}'
```

## MML Query DSL

Język zapytań obsługuje między innymi:

```text
list of theorem in ABCMIZ_0

list of definition
| nodes Item where redefine true and has *[spelling='Noetherian']

occurrences of symbols | filter('spelling=ali*')

list of statement where proposition has negated adjective spelling 'empty'

list of statement | wherege(proposition:numeralterm,3)

list of statement | number >= 200
```

Aktualne typy `list of` to: `theorem`, `definition`, `statement`, `registration`, `symbol` i `all`. `list of constructor` nie jest obsługiwane przez bieżącą gramatykę. Część operatorów relacyjnych związanych z konstruktorami (`ref`, `occur`, `definition`, `notation` i podobne) pozostaje dla zgodności z danymi legacy i na świeżej bazie może zwracać puste wyniki.

Pełniejszy opis składni znajduje się w [docs/MML_QUERY_LANGUAGE.md](docs/MML_QUERY_LANGUAGE.md). Ostatecznym źródłem prawdy jest gramatyka [MmlQuery.g4](mizar-stack/src/main/antlr4/mag/mizarstack/query/MmlQuery.g4).

## Budowanie i testy

Backend na Windows:

```powershell
cd mizar-stack
.\gradlew.bat test
.\gradlew.bat clean bootJar
```

Backend na Linux/macOS:

```bash
cd mizar-stack
./gradlew test
./gradlew clean bootJar
```

Frontend:

```bash
cd mml-querry-frontend
npm ci
npm run lint
npm run build
```

Pomocniczy `run-gradle.cmd` sprawdza rozwiązywanie zależności `jaxen` i `dom4j`, a następnie uruchamia czysty zestaw testów backendu.

## Najczęstsze problemy

- **`minio-init` ma status `Exited (0)`** — to poprawne; kontener ma tylko utworzyć bucket i zakończyć pracę.
- **Frontend lokalny nie łączy się z API** — sprawdź `docker compose ps`, logi `app` i wartość `VITE_DEV_API_TARGET`.
- **Brak wyników zapytań** — po pierwszym starcie uruchom pełny ingest w panelu administratora i sprawdź jego logi.
- **Backend nie startuje** — sprawdź `docker compose logs app postgres minio` oraz zgodność danych dostępowych w `.env`.
- **HTTPS nie działa na serwerze** — sprawdź publiczny port `80`, DNS/target i logi `certbot-init`.
- **Lokalny port `443` nie odpowiada** — bez certyfikatu nginx celowo działa tylko po HTTP.

## Dokumentacja

- [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) — szczegółowy opis aktualnego kodu, modelu danych, API i ograniczeń,
- [docs/MML_QUERY_LANGUAGE.md](docs/MML_QUERY_LANGUAGE.md) — składnia i operatory DSL,
- [DEPLOY_LINUX.md](DEPLOY_LINUX.md) — rozszerzony runbook wdrożenia na Linuxie.

Projekt jest rozwijany jako część pracy magisterskiej dotyczącej mapowania i przeszukiwania struktur biblioteki Mizar.
