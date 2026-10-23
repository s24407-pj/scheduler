#!/usr/bin/env python3
"""Compare actual queue SQL before/after V4 on disposable synthetic PostgreSQL data; never connects to an existing DB."""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--engine', choices=['podman', 'docker'], default='podman')
parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/sms-queue-plans.json')
args = parser.parse_args()
name = 'scheduler-sms-explain-' + uuid.uuid4().hex[:12]


def run(*command, data=None):
    return subprocess.run(command, input=data, text=True, check=True, capture_output=True).stdout.strip()


def sql(statement):
    return run(args.engine, 'exec', '-i', name, 'psql', '-XAt', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', data=statement)


def queue_query(path, excluded):
    # Read the exact JDBC SELECT so the verification follows changes to the production queue query.
    source = (ROOT / path).read_text()
    query = re.search(r'"""\s*(SELECT \* FROM \([\s\S]*?)"""', source).group(1)
    query = query.replace('$exclusion', excluded)
    return query.replace('?', "'2027-06-01T07:00:00Z'::timestamptz", 1).replace('?', '4')


def indexes(plan):
    found = []
    if 'Index Name' in plan:
        found.append(plan['Index Name'])
    for child in plan.get('Plans', []):
        found.extend(indexes(child))
    return sorted(set(found))


try:
    run(args.engine, 'run', '--detach', '--rm', '--name', name,
        '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:18-alpine')
    deadline = time.monotonic() + 30
    while True:
        try:
            sql('SELECT 1;')
            break
        except subprocess.CalledProcessError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.1)
    baseline = (ROOT / 'src/main/resources/db/migration/V1__init_schema.sql').read_text()
    ddl = '\n'.join(re.findall(r'CREATE TABLE public\.(?:notification_deliveries|otp_deliveries) \([\s\S]*?\n\);', baseline))
    ddl += '\nALTER TABLE notification_deliveries ADD PRIMARY KEY(id);\n'
    ddl += '\n'.join(re.findall(r'CREATE (?:UNIQUE )?INDEX (?:notification_due|notification_reminder_date|notification_staff_revision|otp_deliveries_due)\b[\s\S]*?;', baseline))
    sql(ddl)
    sql("""
    INSERT INTO notification_deliveries
        (id,kind,reservation_id,company_id,customer_id,reservation_revision,appointment_date,state,
         attempts,next_attempt_at,deadline,created_at,updated_at)
    SELECT uuidv7(),'CREATE',uuidv7(),'00000000-0000-0000-0000-000000000001',uuidv7(),0,'2027-06-02',
        'ACCEPTED',1,'2027-06-01 07:00Z','2027-06-01 08:00Z','2027-06-01 07:00Z','2027-06-01 07:00Z'
    FROM generate_series(1,100000);
    INSERT INTO notification_deliveries
        (id,kind,reservation_id,company_id,customer_id,reservation_revision,appointment_date,state,
         attempts,next_attempt_at,deadline,created_at,updated_at)
    SELECT md5('notification-'||i)::uuid,CASE WHEN i%3=0 THEN 'REMINDER' ELSE 'CREATE' END,uuidv7(),
        '00000000-0000-0000-0000-000000000001',md5('customer-'||i)::uuid,0,'2027-06-02','PENDING',0,
        '2027-06-01 07:00Z','2027-06-01 08:00Z','2027-06-01 07:00Z','2027-06-01 07:00Z'
    FROM generate_series(1,1000) i;
    INSERT INTO notification_deliveries
        (id,kind,reservation_id,company_id,customer_id,reservation_revision,appointment_date,state,
         attempts,next_attempt_at,deadline,created_at,updated_at)
    SELECT uuidv7(),'CREATE',uuidv7(),'00000000-0000-0000-0000-000000000001',md5('customer-'||i)::uuid,
        0,'2027-06-02','SENDING',1,'2027-06-01 07:00Z','2027-06-01 08:00Z','2027-06-01 07:00Z','2027-06-01 07:00Z'
    FROM generate_series(1,10) i;
    INSERT INTO otp_deliveries (phone_number,code,state,attempts,created_at,expires_at,next_attempt_at,updated_at)
    SELECT '+4870'||lpad(i::text,7,'0'),NULL,'ACCEPTED',1,'2027-06-01 07:00Z','2027-06-01 07:05Z',
        '2027-06-01 07:00Z','2027-06-01 07:00Z' FROM generate_series(1,100000) i;
    INSERT INTO otp_deliveries (phone_number,code,state,attempts,created_at,expires_at,next_attempt_at,updated_at)
    SELECT '+4880'||lpad(i::text,7,'0'),NULL,'PENDING',0,'2027-06-01 07:00Z','2027-06-01 07:05Z',
        '2027-06-01 07:00Z','2027-06-01 07:00Z' FROM generate_series(1,1000) i;
    INSERT INTO otp_deliveries (phone_number,code,state,attempts,created_at,expires_at,next_attempt_at,updated_at)
    SELECT '+4880'||lpad(i::text,7,'0'),NULL,'SENDING',1,'2027-06-01 07:00Z','2027-06-01 07:05Z',
        '2027-06-01 07:00Z','2027-06-01 07:00Z' FROM generate_series(1,10) i;
    ANALYZE notification_deliveries; ANALYZE otp_deliveries;
    """)
    paths = {
        'notification': 'src/main/kotlin/pl/kacosmetology/scheduler/notification/NotificationDeliveryRepository.kt',
        'otp': 'src/main/kotlin/pl/kacosmetology/scheduler/auth/OtpDeliveryRepository.kt',
    }
    report = {'postgres_version': sql('SELECT version();'),
              'rows_per_table': {'terminal': 100000, 'pending': 1000, 'sending': 10}, 'plans': {}}
    for phase in ['before', 'after']:
        if phase == 'after':
            sql((ROOT / 'src/main/resources/db/migration/V4__sms_sending_lane_indexes.sql').read_text())
            sql('ANALYZE notification_deliveries; ANALYZE otp_deliveries;')
        for kind, path in paths.items():
            for admitted in [0, 4]:
                exclusion = ''
                if admitted:
                    if kind == 'otp':
                        exclusion = "AND md5(d.phone_number) NOT IN (" + ','.join("md5('+4880'||lpad('%s',7,'0'))" % i for i in range(11,15)) + ')'
                    else:
                        exclusion = "AND (d.company_id::text || ':' || d.customer_id::text) NOT IN (" + ','.join("'00000000-0000-0000-0000-000000000001:' || md5('customer-%s')::uuid::text" % i for i in range(11,15)) + ')'
                query = queue_query(path, exclusion)
                samples = [json.loads(sql('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + query))[0] for _ in range(6)]
                entry = {'median_execution_ms': statistics.median(s['Execution Time'] for s in samples[1:]),
                         'shared_hit_blocks': samples[-1]['Plan'].get('Shared Hit Blocks',0),
                         'shared_read_blocks': samples[-1]['Plan'].get('Shared Read Blocks',0),
                         'indexes': indexes(samples[-1]['Plan']), 'last_plan': samples[-1]}
                key = '%s/%s/admitted-%d' % (phase,kind,admitted)
                report['plans'][key] = entry
                print(key, json.dumps({k:v for k,v in entry.items() if k != 'last_plan'}), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
finally:
    subprocess.run([args.engine,'rm','--force',name], check=False, capture_output=True)
