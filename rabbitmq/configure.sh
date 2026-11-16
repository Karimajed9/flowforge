#!/bin/sh
set -eu

API="http://rabbitmq:15672/api"
AUTH="$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD"

echo "Waiting for RabbitMQ Management API..."

curl --fail --silent --show-error \
  --retry 30 \
  --retry-delay 2 \
  --retry-connrefused \
  --user "$AUTH" \
  "$API/overview" > /dev/null

echo "Creating dead-letter exchange..."

curl --fail --silent --show-error \
  --user "$AUTH" \
  -H "Content-Type: application/json" \
  -X PUT \
  "$API/exchanges/%2F/flowforge.executions.dlx" \
  --data '{
    "type": "direct",
    "durable": true,
    "auto_delete": false,
    "internal": false,
    "arguments": {}
  }'

echo "Creating dead-letter queue..."

curl --fail --silent --show-error \
  --user "$AUTH" \
  -H "Content-Type: application/json" \
  -X PUT \
  "$API/queues/%2F/flowforge.executions.dead" \
  --data '{
    "durable": true,
    "auto_delete": false,
    "arguments": {}
  }'

echo "Binding dead-letter queue..."

curl --fail --silent --show-error \
  --user "$AUTH" \
  -H "Content-Type: application/json" \
  -X POST \
  "$API/bindings/%2F/e/flowforge.executions.dlx/q/flowforge.executions.dead" \
  --data '{
    "routing_key": "execution.failed",
    "arguments": {}
  }'

echo "Applying dead-letter policy..."

curl --fail --silent --show-error \
  --user "$AUTH" \
  -H "Content-Type: application/json" \
  -X PUT \
  "$API/policies/%2F/flowforge-dlx" \
  --data '{
    "pattern": "^flowforge[.]executions[.]pending$",
    "definition": {
      "dead-letter-exchange": "flowforge.executions.dlx",
      "dead-letter-routing-key": "execution.failed"
    },
    "priority": 1,
    "apply-to": "queues"
  }'

echo "RabbitMQ dead-letter setup completed successfully."