#!/usr/bin/env bash

echo "BABASHKA_TEST_ENV: %BABASHKA_TEST_ENV%"
lein do clean, test
