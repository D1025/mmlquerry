# Mizar Stack - Architektura i Implementacja

## 🎯 Cel Projektu

**Mizar Stack** to platforma do:
1. Pobierania artykułów Mizara z GitHub Releases
2. Przechowywania ich w MinIO (S3-compatible object storage)
3. Parsowania XML (ESX format) i indeksowania do PostgreSQL
4. Queryowania i analizowania struktury biblioteki Mizara

## 🏗️ Architektura

```
GitHub Releases (Mizar ESX files)
    ↓ (download + extract)
MinIO / S3 (storage)
    ↓ (parse + map)
PostgreSQL (mizar_schema)
    ↓ (query + analyze)
REST API (/ingest, /query, /search)
```

## 📦 Komponenty

### 1. IngestService (ingest/)
- **Funkcja**: Pobieranie z GitHub, upload do S3, indeksowanie do BD
- **Główne metody**:
  - `downloadLatestReleaseToS3()` - Download release z GitHub
  - `indexS3Prefix(String prefix)` - Indeks plików z S3
  - `downloadAndIndex()` - Pełny pipeline

### 2. EsxMmlMapperService (ingest/)
- **Funkcja**: Parsowanie XML (ESX) i mapowanie do schematu BD
- **Podejście**: SAX Reader (streaming) dla oszczędzania pamięci
- **Output**: Tabele mizar_schema (article, mml_item, constructor, notation, ...)

### 3. XmlProcessingService (ingest/)
- **Funkcja**: Wstępna obróbka XML
- **Główne metody**:
  - `validateXml(byte[])`
  - `extractMetadata(byte[])`
  - `preprocessXml(byte[])`

### 4. QueryEvaluationService (query/eval/)
- **Funkcja**: Ewaluacja parsed query AST
- **Wzorzec**: Visitor pattern na QueryNode AST

### 5. QueryParser (query/parser/)
- **Funkcja**: Parsowanie string query do AST
- **Narzędzie**: ANTLR4 (grammar: MmlQuery.g4)

### 6. QueryProcessingPipeline (query/integration/)
- **Funkcja**: End-to-end query execution z profiling

## 🗄️ Schema Bazy Danych

Główne tabele:

```sql
article              -- Artykuł (kolekcja mml_items)
mml_item             -- Element biblioteki (konstruktor, twierdzenie, itp.)
constructor          -- Konstruktor (subtype mml_item)
notation             -- Notacja (subtype mml_item)
statement            -- Twierdzenie/Definicja (subtype mml_item)
registration         -- Rejestracja (subtype mml_item)
symbol               -- Symbol tekstowy
format               -- Format notacji
item_constructor_ref -- Referencje między elementami
notation_symbol      -- Mapowanie notation → symbols
notation_constructor -- Mapowanie notation → constructors
constructor_definition -- Definicje konstruktorów
```

**Pełna dokumentacja**: `MAPOWANIE_ESX_BD.md`

## 🚀 Uruchomienie

Szczegolowy runbook pod Linux jest w `DEPLOY_LINUX.md`. Ponizej masz skrocona wersje dla 3 scenariuszy.

### Wymagania

- Docker + Docker Compose plugin
- Java 17+ (backend)
- Node.js 22+ (frontend dev mode)

### 1) Lokalnie (dev, bez publicznego HTTPS)

Ten wariant jest najlepszy do codziennej pracy: baza i storage w Dockerze, backend i frontend odpalane lokalnie.

```bash
# 1. Konfiguracja
cp .env.template .env

# 2. Infrastruktura (DB + MinIO)
docker compose up -d postgres minio minio-init

# 3. Backend
cd mizar-stack
./gradlew bootRun

# 4. Frontend (drugi terminal)
cd ../mml-querry-frontend
npm ci
npm run dev -- --host 0.0.0.0 --port 5173
```

Adresy:
- Frontend: `http://localhost:5173`
- Backend API: `http://localhost:8080`

### 2) Serwer bez domeny (HTTPS na publicznym IP)

Uzywa certyfikatu Let's Encrypt dla IP (shortlived). Ustaw w `.env`:

```env
LETSENCRYPT_TARGET=159.89.106.226
LETSENCRYPT_EMAIL=admin@example.com
FRONTEND_API_BASE_URL=/api
ADMIN_PASSWORD=changeme-admin
```

Uruchom:

```bash
docker compose up -d --build
```

Wymagane:
- publiczny routing na ten IP,
- otwarte porty `80/tcp` i `443/tcp`.

### 3) Serwer z domena (HTTPS na domenie)

1. Ustaw DNS:
- rekord `A` (i opcjonalnie `AAAA`) domeny musi wskazywac na serwer.

2. Ustaw `.env`:

```env
LETSENCRYPT_TARGET=mizar.twojadomena.pl
LETSENCRYPT_EMAIL=admin@example.com
FRONTEND_API_BASE_URL=/api
ADMIN_PASSWORD=changeme-admin
```

3. Uruchom:

```bash
docker compose up -d --build
```

### Jak dziala HTTPS w compose

- `frontend` (nginx) publikuje `80` i `443`,
- backend `app` dziala tylko wewnetrz sieci dockera (`app:8080`),
- `/api/*` jest proxy do backendu,
- `certbot-init` wydaje pierwszy certyfikat,
- `certbot-renew` odnawia certyfikat cyklicznie,
- frontend sam przeladowuje nginx po zmianie certyfikatu.

Uwaga: do czasu wydania certyfikatu `443` moze chwile zwracac `connection refused`, a `80` powinien dzialac od razu.

### Panel admina

- W naglowku frontendu dostepna jest zakladka `Admin`.
- Haslo administratora ustawiasz po stronie backendu przez `ADMIN_PASSWORD` w `.env`.
- Frontend hashuje wpisane haslo (SHA-256) i wysyla hash w naglowku `Authorization`.
- Zabezpieczone endpointy:
  - `/ingest/*`
  - `/admin/*`
- Z panelu admina mozna uruchomic:
  - pobranie zasobow do S3,
  - indeksowanie wskazanego prefixu S3,
  - pelny ingest (download + index).
- Logi postepu operacji sa odswiezane na zywo w panelu.

### Application Properties

```properties
# PostgreSQL
spring.datasource.url=jdbc:postgresql://localhost:5432/mizar
spring.datasource.username=postgres
spring.datasource.password=postgres

# MinIO / S3
app.s3.endpoint=http://localhost:9000
app.s3.bucket=mizar-data
aws.accessKeyId=minioadmin
aws.secretAccessKey=minioadmin

# GitHub
app.github.repo=MizarProject/Mizar
app.github.token=ghp_xxxx...  # Optional, dla vyššej rate limitu
app.http.timeoutMs=15000
```

## 🔌 REST API

### Ingest Endpoints

#### Download Latest Release
```bash
POST /ingest/download

Response:
{
  "tagName": "esx-mizar-8.1.11_5.68.1412",
  "s3Prefix": "mizar-esx/releases/esx-mizar-8.1.11_5.68.1412",
  "filesUploaded": 1234,
  "bytesUploaded": 567890123,
  "durationSeconds": 45
}
```

#### Index from S3
```bash
POST /ingest/index?prefix=mizar-esx/releases/esx-mizar-8.1.11_5.68.1412/esx_mml

Response:
{
  "runId": 1,
  "filesSeen": 1234,
  "filesProcessed": 1234,
  "newVersions": 1234,
  "filesFailed": 0,
  "totalBytes": 567890123,
  "durationSeconds": 120
}
```

#### Full Ingest (Download + Index)
```bash
POST /ingest/full

Response:
{
  "download": { ... },
  "index": { ... }
}
```

#### Latest Statistics
```bash
GET /ingest/stats/latest

Response:
{
  "runId": 1,
  "articlesProcessed": 1234,
  "itemsIndexed": 567890,
  "errors": 0,
  "durationSeconds": 165
}
```

### Query Endpoints (TODO)

```bash
POST /query/parse?q=list<constructors>
POST /query/eval
GET /search?article=XBOOLE_0&kind=func
```

## 📊 Mapowanie ESX → BD

Proces mapowania jest dokumentowany w `MAPOWANIE_ESX_BD.md`:

1. **Parsowanie** (SAX Reader)
   - Text-Proper → article
   - Item nodes → extrahowanie type/subtype
   
2. **Ekstrakcja** (heurystyki)
   - Pattern nodes → constructor kind
   - Symbols z attributes
   - Referencje do innych konstruktorów
   
3. **Wstawianie** (do PostgreSQL)
   - article → mml_item
   - constructor/notation/statement/registration (subtypes)
   - item_constructor_ref (referencje)
   
4. **Resolver** (2-phase)
   - Phase 1: Wstawianie z pending refs
   - Phase 2: Rozwiązywanie cross-article references

## 🔍 Heurystyki Mapowania

```java
// Typ elementu
if (xml.contains("notation")) → kind = "notation"
else if (patterns exist) → kind = "constructor"
else if (definition/statement) → kind = "statement"
else if (registration) → kind = "registration"

// Podtyp
if (Functor-Pattern) → subKind = "func"
else if (Attribute-Pattern) → subKind = "attr"
else if (Mode-Pattern) → subKind = "mode"
// ... itd

// LibID
libId = MMLId || absoluteconstrMMLId || ARTICLE:subKind NUMBER
```

## 📈 Wydajność

### Optymalizacje

1. **SAX Streaming**: Nie ładuje całego dokumentu do RAM
2. **Detaching**: `.detach()` elementy po przetworzeniu
3. **Batch Inserts**: Grupowanie operacji DB
4. **Indeksowanie**: Indeksy na `lib_id`, `text`, `kind`

### Benchmarks (Szacunkowe)

- **Download** (1GB, 50Mbps): ~20s
- **Parse & Index** (1000 files, ~1GB): ~5 min
- **DB Queries**: <100ms (z indeksami)

## 🛠️ Struktura Projektu

```
mizar-stack/
├── src/main/java/mag/mizarstack/
│   ├── ingest/
│   │   ├── IngestService.java
│   │   ├── EsxMmlMapperService.java
│   │   └── XmlProcessingService.java
│   ├── query/
│   │   ├── ast/                 # Abstract Syntax Tree nodes
│   │   ├── parser/              # QueryParser + AstBuilder
│   │   ├── eval/                # QueryEvaluationService
│   │   └── integration/         # QueryProcessingPipeline
│   ├── entity/                  # JPA entities
│   ├── dao/                     # Data Access Objects
│   ├── config/                  # Spring configuration
│   └── web/                     # REST Controllers
├── src/main/antlr4/
│   └── mag/mizarstack/query/
│       └── MmlQuery.g4          # ANTLR Grammar
├── src/main/resources/
│   ├── application.properties
│   └── db/migration/            # Flyway migrations
└── build.gradle
```

## 📝 Migracje Bazy

```
V1__schema.sql          -- Dokumenty i wersjonowanie
V2__ingest_run.sql      -- Tracking ingest runs
V3__mizar_schema.sql    -- Schema mapowania Mizara (main)
V4__mizar_views.sql     -- Views dla analytics
```

## 🔄 Workflow Ingestji

```
1. User: POST /ingest/full
2. IngestService.downloadAndIndex()
   a) downloadLatestReleaseToS3()
      - Fetch https://api.github.com/repos/MizarProject/Mizar/releases/latest
      - Download zipball
      - Extract esx_mml/**/*.esx files
      - Upload to S3 (mizar-esx/releases/{tag}/)
   b) indexS3Prefix(s3Prefix)
      - List all .esx files in S3 prefix
      - For each file:
        - Download from S3
        - EsxMmlMapperService.processArticleXml()
          * SAX parse XML
          * Extract items (constructors, notations, etc.)
          * Insert to BD (article, mml_item, constructor, ...)
          * Collect pending references
        - Resolve pending references
      - Log statistics
3. Response: FullIngestResult (download + index stats)
```

## 🧪 Testowanie

```bash
# Unit tests
./gradlew test

# Integration tests (z BD)
./gradlew integrationTest

# Gradle build
./gradlew build

# Clean rebuild
./gradlew clean build
```

## 📚 Dodatkowe Dokumenty

- **MAPOWANIE_ESX_BD.md** - Szczegółowe mapowanie, heurystyki, przykłady XML
- **MmlQuery.g4** - ANTLR Grammar dla query language

## 🚨 Troubleshooting

### Build Fails: "ANTLR grammar not found"
```bash
# Upewnij się że plik istnieje:
ls -la src/main/antlr4/mag/mizarstack/query/MmlQuery.g4

# Jeśli nie, grammar zostanie wygenerowana w dalszych krokach
```

### MinIO Connection Error
```bash
# Sprawdzenie MinIO
docker ps | grep minio
curl http://localhost:9000/minio/health/live

# Jeśli offline, restart:
docker-compose restart minio
```

### PostgreSQL Connection Error
```bash
# Sprawdzenie PostgreSQL
docker ps | grep postgres
psql -h localhost -U postgres -c "SELECT version();"

# Jeśli offline:
docker-compose restart postgres
```

## 📄 Licencja

Projekt stanowi część pracy magisterskiej.

## 👤 Autor

Magisterka: Mapowanie Struktur Biblioteki Mizar do Bazy Danych

---

**Last Updated**: 2026-01-03

