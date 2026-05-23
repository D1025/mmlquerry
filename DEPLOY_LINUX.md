# Deploy na Linuxie (Nginx + Let's Encrypt dla IP)

Poniżej masz gotowy, praktyczny runbook dla serwera Linux i publicznego IP `159.89.106.226`.

## 1) Wymagania

- Publiczny serwer Linux z adresem IP `159.89.106.226`.
- Otwarty ruch przychodzacy TCP `80` i `443` (firewall systemowy + firewall dostawcy chmury).
- Docker + Docker Compose plugin.

## 2) Instalacja Dockera (Ubuntu/Debian)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker $USER
```

Po `usermod` wyloguj/zaloguj sesje SSH.

## 3) Pobranie projektu i konfiguracja `.env`

```bash
git clone <URL_TWOJEGO_REPO>
cd Magisterka
cp .env.template .env
```

Edytuj `.env`:

```env
LETSENCRYPT_IP=159.89.106.226
LETSENCRYPT_EMAIL=twoj-email@example.com
ADMIN_PASSWORD=ustaw-silne-haslo-admina
POSTGRES_PASSWORD=ustaw-silne-haslo-db
MINIO_ROOT_PASSWORD=ustaw-silne-haslo-minio
```

## 4) Otworzenie portow

Jesli uzywasz UFW:

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw reload
```

## 5) Start stacka

```bash
docker compose up -d --build
```

Co sie stanie:

- `frontend` (nginx) startuje na `80/443`,
- `certbot-init` pobiera certyfikat Let's Encrypt dla IP,
- po wydaniu certyfikatu frontend przeladowuje konfiguracje i obsluguje HTTPS,
- `certbot-renew` odnawia certyfikat cyklicznie.

## 6) Weryfikacja

```bash
docker compose ps
docker compose logs --no-log-prefix certbot-init
docker compose logs --no-log-prefix frontend
```

Test endpointow:

```bash
curl -I http://159.89.106.226
curl -I https://159.89.106.226
```

## 7) Gdy certyfikat nie zostal wydany za pierwszym razem

Najczestsza przyczyna: port `80` nie jest publicznie dostepny.

Po poprawce firewalla:

```bash
docker compose run --rm certbot-init
docker compose logs --no-log-prefix frontend
```

## 8) Aktualizacja aplikacji

```bash
git pull
docker compose up -d --build
```

## 9) Zatrzymanie

```bash
docker compose down
```
