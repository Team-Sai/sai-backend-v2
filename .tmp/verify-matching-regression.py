from pathlib import Path
import re
import subprocess
import json

root = Path(__file__).resolve().parents[1]
source = root / 'src/main/java/org/teamsai/saibackend/domain/matching/repository/MatchingCandidateRepository.java'
code = source.read_text(encoding='utf-8')
template = re.search(r'FIND_CANDIDATES_SQL\s*=\s*"""(.*?)""";', code, re.S).group(1)
assert template.count('%s') == 2

def query(contract_id, account_id, first_status, first_id, next_id, expected):
    sql = template.replace('%s', "AND 'LOAN' = 'SETTLEMENT' AND s.settlement_id = " + str(contract_id), 1)
    sql = sql.replace('%s', "AND 'LOAN' = 'LOAN' AND lc.contract_id = " + str(contract_id), 1)
    sql = sql.replace(':linkedAccountId', str(account_id)).replace(':transactionAt', 'CURRENT_TIMESTAMP()')
    sql = re.sub(r'\brepayment_schedule\b', 'schedule_fixture', sql)
    sql = re.sub(r'\bpayment_record\b', 'payment_fixture', sql)
    fixture = f"""
WITH schedule_fixture AS (
  SELECT schedule_id, contract_id, sequence, created_at, total_payment_due,
    CASE WHEN contract_id = {contract_id} AND sequence = 1 THEN '{first_status}'
         WHEN contract_id = {contract_id} AND sequence >= 2 THEN 'PENDING'
         ELSE status END AS status
  FROM repayment_schedule
), payment_fixture AS (
  SELECT * FROM payment_record
  WHERE NOT (payment_target_type = 'LOAN' AND target_id IN
    (SELECT schedule_id FROM repayment_schedule WHERE contract_id = {contract_id}))
)
"""
    command = ['docker', 'exec', '-i', 'sai-backend-db', 'sh', '-c',
        'MYSQL_PWD="$MARIADB_ROOT_PASSWORD" mariadb --default-character-set=utf8mb4 -N -B -uroot "$MARIADB_DATABASE"']
    result = subprocess.run(command, input=(fixture + sql + ';\n').encode('utf-8'), capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace'))
    rows = [line.split('\t') for line in result.stdout.decode('utf-8').splitlines() if line.strip()]
    ids = [int(row[1]) for row in rows]
    assert ids == expected, f'{first_status}: expected={expected}, actual={ids}'
    return {'firstStatus': first_status, 'contractId': contract_id, 'candidateScheduleIds': ids, 'expectedScheduleIds': expected, 'result': 'PASS'}

results = [
    query(99154, 98678, 'OVERDUE', 99099, 99100, [99099]),
    query(99154, 98678, 'PAID', 99099, 99100, [99100]),
    query(99154, 98678, 'WRITTEN_OFF', 99099, 99100, [99100]),
    query(99158, 98677, 'PAID', 99123, 99124, [99124]),
]
report = {'verification': 'Actual repository SQL executed on MariaDB with SELECT-only CTE fixtures; no persisted data changes', 'cases': results}
(root / '.tmp/matching-regression-result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
