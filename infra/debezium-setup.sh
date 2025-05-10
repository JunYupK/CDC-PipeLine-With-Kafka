#!/bin/bash
# debezium-setup.sh

# PostgreSQL이 시작될 때까지 대기
until PGPASSWORD=home psql -h localhost -U kjy -d source_db -c '\q' 2>/dev/null; do
  echo "PostgreSQL is unavailable - sleeping"
  sleep 3
done

echo "PostgreSQL is up - setting up logical replication"

# Publication 생성
PGPASSWORD=home psql -h localhost -U kjy -d source_db -c "
DROP PUBLICATION IF EXISTS dbz_publication;
CREATE PUBLICATION dbz_publication FOR TABLE articles, media, article_changes;
"

# Kafka Connect가 시작될 때까지 대기
until curl -s http://localhost:8083/ > /dev/null; do
  echo "Kafka Connect is unavailable - sleeping"
  sleep 3
done

echo "Kafka Connect is up - creating connector"

# Connector 구성 생성 및 등록
cat > connector-config.json << EOF
{
  "name": "postgres-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "postgres.internal",
    "database.port": "5432",
    "database.user": "kjy",
    "database.password": "home",
    "database.dbname": "source_db",
    "database.server.name": "postgres",
    "schema.include.list": "public",
    "table.include.list": "public.articles,public.media,public.article_changes",
    "plugin.name": "pgoutput",
    "slot.name": "debezium",
    "publication.name": "dbz_publication",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false"
  }
}
EOF

curl -X POST -H "Content-Type: application/json" --data @connector-config.json http://localhost:8083/connectors

echo "Debezium connector setup complete"