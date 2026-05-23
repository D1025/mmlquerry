#!/bin/sh
set -eu

CERT_DIR="/etc/nginx/certs"
CERT_FILE="${CERT_DIR}/server.crt"
KEY_FILE="${CERT_DIR}/server.key"
IP_MARKER="${CERT_DIR}/.ssl_ip"

SSL_IP="${NGINX_SSL_IP:-127.0.0.1}"
SSL_DAYS="${NGINX_SSL_DAYS:-825}"

mkdir -p "${CERT_DIR}"

REGENERATE="false"
if [ ! -f "${CERT_FILE}" ] || [ ! -f "${KEY_FILE}" ] || [ ! -f "${IP_MARKER}" ]; then
    REGENERATE="true"
elif [ "$(cat "${IP_MARKER}")" != "${SSL_IP}" ]; then
    REGENERATE="true"
fi

if [ "${REGENERATE}" = "true" ]; then
    echo "Generating self-signed certificate for IP ${SSL_IP}"
    openssl req -x509 -nodes -newkey rsa:2048 \
        -keyout "${KEY_FILE}" \
        -out "${CERT_FILE}" \
        -days "${SSL_DAYS}" \
        -subj "/CN=${SSL_IP}" \
        -addext "subjectAltName=IP:${SSL_IP}"
    printf "%s" "${SSL_IP}" > "${IP_MARKER}"
fi

chmod 600 "${KEY_FILE}"
chmod 644 "${CERT_FILE}"
chmod 644 "${IP_MARKER}"
