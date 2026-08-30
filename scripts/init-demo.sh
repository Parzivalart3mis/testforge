#!/bin/sh
# Creates the demo target database and applies its schema.
# Runs once, on the container's first start, via the postgres entrypoint.
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    CREATE DATABASE testforge_demo;
SQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname testforge_demo \
    -f /docker-entrypoint-initdb.d/demo/demo_commerce.sql

echo "TestForge demo schema ready: 22 tables in testforge_demo.public"
