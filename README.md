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

Szczegolowy runbook pod serwer Linux i HTTPS na IP znajdziesz w `DEPLOY_LINUX.md`.

### Prerequisites

```bash
# Docker Compose (PostgreSQL + MinIO)
docker-compose up -d

# Gradle 8.x+
java -version  # Java 25+
```

### Build

```bash
cd mizar-stack
./gradlew build
```

### Run

```bash
./gradlew bootRun

# lub
java -jar build/libs/mizar-stack-0.1.0.jar
```

### Frontend (Nginx + Let's Encrypt na IP)

Frontend jest serwowany przez `nginx`, ktory stoi pomiedzy uzytkownikiem a backendem:

- `nginx` publikuje tylko porty `80` i `443`,
- backend `app` dziala tylko w sieci dockera (bez publicznego portu hosta),
- ruch API jest proxyowany przez `nginx`: `/api/* -> app:8080/*`.

TLS na samym adresie IP jest realizowany przez Let's Encrypt (profil `shortlived`, certyfikat ~6 dni).
Certyfikat i odnowienia obsluguja uslugi `certbot-init` (bootstrap) oraz `certbot-renew` (cykliczne renew).
Frontend startuje od razu w trybie HTTP (na czas bootstrapu certyfikatu), a po wygenerowaniu certyfikatu automatycznie przeladowuje konfiguracje i przechodzi na HTTPS.

Ustawienia w `.env`:

```env
FRONTEND_API_BASE_URL=/api
LETSENCRYPT_IP=159.89.106.226
LETSENCRYPT_EMAIL=admin@example.com
CERTBOT_RENEW_INTERVAL_SECONDS=43200
NGINX_RELOAD_CHECK_SECONDS=30
ADMIN_PASSWORD=changeme-admin
```

Uruchomienie calosci:

```bash
docker compose up -d --build
```

Po starcie:

- `frontend` wystartuje od razu i wystawi challenge ACME na porcie `80`,
- `certbot-init` pobierze pierwszy certyfikat dla `LETSENCRYPT_IP`,
- `frontend` automatycznie przeladuje `nginx` i wlaczy HTTPS na `443`,
- `certbot-renew` bedzie odnawial certyfikat cyklicznie,
- `frontend` automatycznie przeladuje `nginx` po odnowieniu certyfikatu.

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

