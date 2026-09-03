# Custom DB-plane image (WS-E, Kevin T89 adjudication). flyway + psql + curl + jq, with the migration
# payload baked in. Built from the reviewed commit and DIGEST-PINNED, so the most safety-critical
# artefact in the pipeline (it runs as the server ADMIN against children's records) is as auditable
# and immutable as the jar. Pulled by the Container Apps job with its managed identity (AcrPull) - no
# registry credential stored. Chosen over an Azure Files mount, which would need a storage ACCOUNT
# KEY and thereby reverse Kevin F1 (shared_access_key_enabled = false).
#
# Build context = repo ROOT:
#   docker build -f deploy/db-plane/Dockerfile -t <acr-login-server>/rht-db-plane:<git-sha> .
FROM flyway/flyway:11-alpine

# psql (01/02 role SQL as admin), curl (IMDS + Key Vault REST), jq (JSON-safe secret parsing). Baked
# in, not 'apk add'-ed at run time - removes an unpinned Alpine-mirror dependency on the deploy path.
USER root
RUN apk add --no-cache postgresql-client curl jq

# Migration payload, baked in (not mounted).
COPY terraform/modules/postgres/sql/01-roles-and-grants.sql       /payload/sql/01-roles-and-grants.sql
COPY terraform/modules/postgres/sql/02-audit-events-hardening.sql  /payload/sql/02-audit-events-hardening.sql
COPY src/main/resources/db/migration/                             /payload/migration/

COPY deploy/db-plane/run-db-plane.sh /usr/local/bin/run-db-plane.sh
RUN chmod +x /usr/local/bin/run-db-plane.sh

# Override the base image's flyway entrypoint: our script orchestrates 01 -> flyway -> 02.
ENTRYPOINT ["/usr/local/bin/run-db-plane.sh"]
