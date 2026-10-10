#!/bin/sh
set -eu

: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD must be set}"
db_dir=/opt/campushub/db

existing_tables=$(MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot campushub \
  --batch --skip-column-names \
  -e "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE();")
if [ "$existing_tables" -ne 0 ]; then
  echo 'Refusing fresh-volume bootstrap: database is not empty.' >&2
  exit 1
fi

run_sql() {
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot campushub < "$1"
}

run_sql "$db_dir/schema.sql"
run_sql "$db_dir/migration/V001__add_business_unique_constraints.sql"
run_sql "$db_dir/migration/V002__add_identity_and_merchant_authorization.sql"
run_sql "$db_dir/migration/V003__add_order_cancellation_outbox.sql"
run_sql "$db_dir/migration/V004__add_shop_cache_invalidation_outbox.sql"
run_sql "$db_dir/migration/V005__add_shop_search_indexes.sql"
run_sql "$db_dir/migration/V006__allow_campus_posts_without_shop.sql"
run_sql "$db_dir/seed.sql"
