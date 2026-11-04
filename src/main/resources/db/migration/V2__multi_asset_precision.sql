-- Crypto assets need 8 decimal places (1 satoshi = 0.00000001 BTC) and longer codes.
-- Rates need more precision too: e.g. JPY -> BTC is about 0.00000006.
-- ddl-auto=update never alters existing columns, which is why this is a migration.

ALTER TABLE holdings ALTER COLUMN currency SET DATA TYPE VARCHAR(10);
ALTER TABLE holdings ALTER COLUMN amount SET DATA TYPE NUMERIC(28, 8);

ALTER TABLE wallet_transactions ALTER COLUMN from_currency SET DATA TYPE VARCHAR(10);
ALTER TABLE wallet_transactions ALTER COLUMN to_currency SET DATA TYPE VARCHAR(10);
ALTER TABLE wallet_transactions ALTER COLUMN from_amount SET DATA TYPE NUMERIC(28, 8);
ALTER TABLE wallet_transactions ALTER COLUMN to_amount SET DATA TYPE NUMERIC(28, 8);
ALTER TABLE wallet_transactions ALTER COLUMN rate SET DATA TYPE NUMERIC(38, 18);
