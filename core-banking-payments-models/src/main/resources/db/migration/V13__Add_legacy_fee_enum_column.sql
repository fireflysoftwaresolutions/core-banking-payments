-- Keep the legacy free-text fee_type unchanged; classification requires an explicit caller value.
ALTER TABLE payment_fee ADD COLUMN fee_type_enum fee_type_enum;
