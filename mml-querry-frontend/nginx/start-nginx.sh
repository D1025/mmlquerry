#!/bin/sh
set -eu

letsencrypt_target="${LETSENCRYPT_TARGET:-${LETSENCRYPT_IP:-}}"
if [ -z "$letsencrypt_target" ]; then
  echo "LETSENCRYPT_TARGET (or legacy LETSENCRYPT_IP) is required." >&2
  exit 1
fi

cert_dir="/etc/letsencrypt/live/${letsencrypt_target}"
cert_file="${cert_dir}/fullchain.pem"
key_file="${cert_dir}/privkey.pem"
trigger_file="/var/www/certbot/.reload-nginx"
http_template="/etc/nginx/custom-templates/default.http.conf.template"
https_template="/etc/nginx/custom-templates/default.https.conf.template"
target_conf="/etc/nginx/conf.d/default.conf"

render_config() {
  if [ -f "$cert_file" ] && [ -f "$key_file" ]; then
    sed "s|\${LETSENCRYPT_TARGET}|${letsencrypt_target}|g" "$https_template" > "$target_conf"
  else
    cp "$http_template" "$target_conf"
  fi
}

touch "$trigger_file"
render_config

last_reload_stamp="$(stat -c %Y "$trigger_file" 2>/dev/null || echo 0)"
last_cert_stamp="$(stat -c %Y "$cert_file" 2>/dev/null || echo 0)"
check_interval="${NGINX_RELOAD_CHECK_SECONDS:-30}"

monitor_reload_trigger() {
  while true; do
    current_stamp="$(stat -c %Y "$trigger_file" 2>/dev/null || echo 0)"
    current_cert_stamp="$(stat -c %Y "$cert_file" 2>/dev/null || echo 0)"
    if [ "$current_stamp" != "$last_reload_stamp" ] || [ "$current_cert_stamp" != "$last_cert_stamp" ]; then
      render_config
      if nginx -t; then
        if nginx -s reload; then
          last_reload_stamp="$current_stamp"
          last_cert_stamp="$current_cert_stamp"
        else
          echo "Nginx reload failed, will retry on next loop."
        fi
      else
        echo "Nginx config test failed, will retry on next loop."
      fi
    fi

    sleep "$check_interval"
  done
}

monitor_reload_trigger &
exec nginx -g "daemon off;"
