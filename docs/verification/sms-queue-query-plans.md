# SMS lane lookup plans

Measured on PostgreSQL **18.4**, x86_64 Alpine, in a disposable local container on 2026-10-23. Each table contained **100,000 terminal, 1,000 pending and 10 SENDING rows**. No production database, recipient data or SMS provider was used.

Reproduce from the repository root:

```bash
python3 scripts/verification/explain-sms-queues.py --engine podman
# --engine docker is also supported.
```

The script creates/removes its own container, publishes no database ports, reads the table definitions and existing indexes from V1, reads the exact SELECTs from the production repositories, and compares them before/after V4. It tests both empty and four-item in-process lane exclusions, with a four-item selection limit. It runs `ANALYZE`, discards one warm-up per case, then takes the median execution time from five `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` samples. Full final plans and server version are saved under `build/reports/sms-queue-plans.json` (ignored build output).

| Query | Excluded lanes | Before, median ms | After, median ms | Shared buffer hits before → after |
| --- | ---: | ---: | ---: | ---: |
| Notifications | 0 | 8.859 | 1.831 | 2181 → 34 |
| Notifications | 4 | 9.163 | 2.108 | 2181 → 34 |
| OTP | 0 | 6.999 | 1.712 | 1468 → 27 |
| OTP | 4 | 7.833 | 2.407 | 1468 → 27 |

Before V4, the plans scanned historical rows to locate `SENDING` entries. After V4, both used their existing due index plus the new sparse `notification_sending_lane` or `otp_sending_phone` index. The examples had warm shared buffers (zero shared-read blocks in the recorded final samples).

This demonstrates useful index access for this synthetic history/backlog distribution, not a confirmed production regression or production latency guarantee. Production row distributions, statistics, hardware and prepared-plan selection can change the plan. Check real plans on an approved environment before further tuning; [PostgreSQL EXPLAIN documentation](https://www.postgresql.org/docs/18/sql-explain.html) explains the measurements. `EXPLAIN ANALYZE` executes its statement: this verification runs only SELECTs on disposable synthetic data.

V4 adds ordinary transactional partial indexes without changing existing migrations or queue semantics. On a large deployed history, index creation can hold write locks while it builds; schedule the migration for the normal single-instance maintenance deployment window. There is no concurrent-build or rolling-deployment claim.
