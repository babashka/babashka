#!/usr/bin/env bash

function ver_lte() {
  printf '%s\n%s' "$1" "$2" | sort -C -V
}

max_glibc_version="2.31"
current_glibc_version=$(ldd --version | head -1 | awk '{print $4}' | cut -d "-" -f 1)

function bail() {
  echo "glibc greater than max version ${max_glibc_version}: ${current_glibc_version}"
  exit 1
}

ver_lte "${current_glibc_version}" "${max_glibc_version}" || bail
