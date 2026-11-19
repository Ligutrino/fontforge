#!/usr/bin/env bash
# Ejecutar en tu Linux Mint, dentro de esta carpeta: ./compilar.sh
set -euo pipefail
cd "$(dirname "$0")"
docker build -t ffglifos .
mkdir -p trabajo
docker run --rm -v "$PWD/trabajo:/work/trabajo" -e WORK=/work/trabajo \
  --user "$(id -u):$(id -g)" -e HOME=/tmp ffglifos
echo
echo "Listo:"
ls -lh trabajo/salida/ffglifos.apk
