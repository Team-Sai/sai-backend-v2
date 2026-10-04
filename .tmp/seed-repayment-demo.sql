SET NAMES utf8mb4;
START TRANSACTION;
INSERT INTO loan_contract (creditor_id,debtor_id,relation_type,principal_amount,interest_rate,repayment_type,start_date,maturity_date,repayment_day,status,creditor_address,debtor_address,contract_alias,terms)
SELECT 889179,889176,'ACQUAINTANCE',480000,0,'EQUAL_PRINCIPAL','2026-08-25',DATE_ADD('2026-08-25',INTERVAL 6 MONTH),25,'COMPLETED','테스트 주소','테스트 주소','[테스트] 이전 달 미상환 포함','개발용 상환 분석 테스트 데이터. 실제 서명 계약이 아닙니다.'
WHERE NOT EXISTS (SELECT 1 FROM loan_contract WHERE contract_alias='[테스트] 이전 달 미상환 포함' AND creditor_id=889179 AND debtor_id=889176);
SET @cid=(SELECT contract_id FROM loan_contract WHERE contract_alias='[테스트] 이전 달 미상환 포함' AND creditor_id=889179 AND debtor_id=889176 ORDER BY contract_id DESC LIMIT 1);
INSERT INTO contract_account (linked_account_id,account_status,selected_at,contract_id)
SELECT 98678,'ACTIVE',NOW(),@cid WHERE NOT EXISTS (SELECT 1 FROM contract_account WHERE contract_id=@cid);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,1,DATE_ADD('2026-08-25',INTERVAL 1 MONTH),80000,0,80000,400000,IF(DATE_ADD('2026-08-25',INTERVAL 1 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=1);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,2,DATE_ADD('2026-08-25',INTERVAL 2 MONTH),80000,0,80000,320000,IF(DATE_ADD('2026-08-25',INTERVAL 2 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=2);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,3,DATE_ADD('2026-08-25',INTERVAL 3 MONTH),80000,0,80000,240000,IF(DATE_ADD('2026-08-25',INTERVAL 3 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=3);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,4,DATE_ADD('2026-08-25',INTERVAL 4 MONTH),80000,0,80000,160000,IF(DATE_ADD('2026-08-25',INTERVAL 4 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=4);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,5,DATE_ADD('2026-08-25',INTERVAL 5 MONTH),80000,0,80000,80000,IF(DATE_ADD('2026-08-25',INTERVAL 5 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=5);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,6,DATE_ADD('2026-08-25',INTERVAL 6 MONTH),80000,0,80000,0,IF(DATE_ADD('2026-08-25',INTERVAL 6 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=6);
INSERT INTO loan_contract (creditor_id,debtor_id,relation_type,principal_amount,interest_rate,repayment_type,start_date,maturity_date,repayment_day,status,creditor_address,debtor_address,contract_alias,terms)
SELECT 889179,889176,'ACQUAINTANCE',720000,0,'EQUAL_PRINCIPAL','2026-09-15',DATE_ADD('2026-09-15',INTERVAL 6 MONTH),15,'COMPLETED','테스트 주소','테스트 주소','[테스트] 10월 15일 상환','개발용 상환 분석 테스트 데이터. 실제 서명 계약이 아닙니다.'
WHERE NOT EXISTS (SELECT 1 FROM loan_contract WHERE contract_alias='[테스트] 10월 15일 상환' AND creditor_id=889179 AND debtor_id=889176);
SET @cid=(SELECT contract_id FROM loan_contract WHERE contract_alias='[테스트] 10월 15일 상환' AND creditor_id=889179 AND debtor_id=889176 ORDER BY contract_id DESC LIMIT 1);
INSERT INTO contract_account (linked_account_id,account_status,selected_at,contract_id)
SELECT 98678,'ACTIVE',NOW(),@cid WHERE NOT EXISTS (SELECT 1 FROM contract_account WHERE contract_id=@cid);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,1,DATE_ADD('2026-09-15',INTERVAL 1 MONTH),120000,0,120000,600000,IF(DATE_ADD('2026-09-15',INTERVAL 1 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=1);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,2,DATE_ADD('2026-09-15',INTERVAL 2 MONTH),120000,0,120000,480000,IF(DATE_ADD('2026-09-15',INTERVAL 2 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=2);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,3,DATE_ADD('2026-09-15',INTERVAL 3 MONTH),120000,0,120000,360000,IF(DATE_ADD('2026-09-15',INTERVAL 3 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=3);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,4,DATE_ADD('2026-09-15',INTERVAL 4 MONTH),120000,0,120000,240000,IF(DATE_ADD('2026-09-15',INTERVAL 4 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=4);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,5,DATE_ADD('2026-09-15',INTERVAL 5 MONTH),120000,0,120000,120000,IF(DATE_ADD('2026-09-15',INTERVAL 5 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=5);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,6,DATE_ADD('2026-09-15',INTERVAL 6 MONTH),120000,0,120000,0,IF(DATE_ADD('2026-09-15',INTERVAL 6 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=6);
INSERT INTO loan_contract (creditor_id,debtor_id,relation_type,principal_amount,interest_rate,repayment_type,start_date,maturity_date,repayment_day,status,creditor_address,debtor_address,contract_alias,terms)
SELECT 889179,889176,'ACQUAINTANCE',1320000,0,'EQUAL_PRINCIPAL','2026-09-25',DATE_ADD('2026-09-25',INTERVAL 6 MONTH),25,'COMPLETED','테스트 주소','테스트 주소','[테스트] 10월 25일 상환','개발용 상환 분석 테스트 데이터. 실제 서명 계약이 아닙니다.'
WHERE NOT EXISTS (SELECT 1 FROM loan_contract WHERE contract_alias='[테스트] 10월 25일 상환' AND creditor_id=889179 AND debtor_id=889176);
SET @cid=(SELECT contract_id FROM loan_contract WHERE contract_alias='[테스트] 10월 25일 상환' AND creditor_id=889179 AND debtor_id=889176 ORDER BY contract_id DESC LIMIT 1);
INSERT INTO contract_account (linked_account_id,account_status,selected_at,contract_id)
SELECT 98678,'ACTIVE',NOW(),@cid WHERE NOT EXISTS (SELECT 1 FROM contract_account WHERE contract_id=@cid);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,1,DATE_ADD('2026-09-25',INTERVAL 1 MONTH),220000,0,220000,1100000,IF(DATE_ADD('2026-09-25',INTERVAL 1 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=1);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,2,DATE_ADD('2026-09-25',INTERVAL 2 MONTH),220000,0,220000,880000,IF(DATE_ADD('2026-09-25',INTERVAL 2 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=2);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,3,DATE_ADD('2026-09-25',INTERVAL 3 MONTH),220000,0,220000,660000,IF(DATE_ADD('2026-09-25',INTERVAL 3 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=3);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,4,DATE_ADD('2026-09-25',INTERVAL 4 MONTH),220000,0,220000,440000,IF(DATE_ADD('2026-09-25',INTERVAL 4 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=4);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,5,DATE_ADD('2026-09-25',INTERVAL 5 MONTH),220000,0,220000,220000,IF(DATE_ADD('2026-09-25',INTERVAL 5 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=5);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,6,DATE_ADD('2026-09-25',INTERVAL 6 MONTH),220000,0,220000,0,IF(DATE_ADD('2026-09-25',INTERVAL 6 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=6);
INSERT INTO loan_contract (creditor_id,debtor_id,relation_type,principal_amount,interest_rate,repayment_type,start_date,maturity_date,repayment_day,status,creditor_address,debtor_address,contract_alias,terms)
SELECT 889179,889176,'ACQUAINTANCE',900000,0,'EQUAL_PRINCIPAL','2026-10-10',DATE_ADD('2026-10-10',INTERVAL 6 MONTH),10,'COMPLETED','테스트 주소','테스트 주소','[테스트] 11월부터 상환','개발용 상환 분석 테스트 데이터. 실제 서명 계약이 아닙니다.'
WHERE NOT EXISTS (SELECT 1 FROM loan_contract WHERE contract_alias='[테스트] 11월부터 상환' AND creditor_id=889179 AND debtor_id=889176);
SET @cid=(SELECT contract_id FROM loan_contract WHERE contract_alias='[테스트] 11월부터 상환' AND creditor_id=889179 AND debtor_id=889176 ORDER BY contract_id DESC LIMIT 1);
INSERT INTO contract_account (linked_account_id,account_status,selected_at,contract_id)
SELECT 98678,'ACTIVE',NOW(),@cid WHERE NOT EXISTS (SELECT 1 FROM contract_account WHERE contract_id=@cid);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,1,DATE_ADD('2026-10-10',INTERVAL 1 MONTH),150000,0,150000,750000,IF(DATE_ADD('2026-10-10',INTERVAL 1 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=1);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,2,DATE_ADD('2026-10-10',INTERVAL 2 MONTH),150000,0,150000,600000,IF(DATE_ADD('2026-10-10',INTERVAL 2 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=2);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,3,DATE_ADD('2026-10-10',INTERVAL 3 MONTH),150000,0,150000,450000,IF(DATE_ADD('2026-10-10',INTERVAL 3 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=3);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,4,DATE_ADD('2026-10-10',INTERVAL 4 MONTH),150000,0,150000,300000,IF(DATE_ADD('2026-10-10',INTERVAL 4 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=4);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,5,DATE_ADD('2026-10-10',INTERVAL 5 MONTH),150000,0,150000,150000,IF(DATE_ADD('2026-10-10',INTERVAL 5 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=5);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,6,DATE_ADD('2026-10-10',INTERVAL 6 MONTH),150000,0,150000,0,IF(DATE_ADD('2026-10-10',INTERVAL 6 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=6);
INSERT INTO loan_contract (creditor_id,debtor_id,relation_type,principal_amount,interest_rate,repayment_type,start_date,maturity_date,repayment_day,status,creditor_address,debtor_address,contract_alias,terms)
SELECT 889176,889179,'ACQUAINTANCE',600000,0,'EQUAL_PRINCIPAL','2026-09-20',DATE_ADD('2026-09-20',INTERVAL 6 MONTH),20,'COMPLETED','테스트 주소','테스트 주소','[테스트] 양예은 받을 돈','개발용 상환 분석 테스트 데이터. 실제 서명 계약이 아닙니다.'
WHERE NOT EXISTS (SELECT 1 FROM loan_contract WHERE contract_alias='[테스트] 양예은 받을 돈' AND creditor_id=889176 AND debtor_id=889179);
SET @cid=(SELECT contract_id FROM loan_contract WHERE contract_alias='[테스트] 양예은 받을 돈' AND creditor_id=889176 AND debtor_id=889179 ORDER BY contract_id DESC LIMIT 1);
INSERT INTO contract_account (linked_account_id,account_status,selected_at,contract_id)
SELECT 98677,'ACTIVE',NOW(),@cid WHERE NOT EXISTS (SELECT 1 FROM contract_account WHERE contract_id=@cid);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,1,DATE_ADD('2026-09-20',INTERVAL 1 MONTH),100000,0,100000,500000,IF(DATE_ADD('2026-09-20',INTERVAL 1 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=1);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,2,DATE_ADD('2026-09-20',INTERVAL 2 MONTH),100000,0,100000,400000,IF(DATE_ADD('2026-09-20',INTERVAL 2 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=2);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,3,DATE_ADD('2026-09-20',INTERVAL 3 MONTH),100000,0,100000,300000,IF(DATE_ADD('2026-09-20',INTERVAL 3 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=3);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,4,DATE_ADD('2026-09-20',INTERVAL 4 MONTH),100000,0,100000,200000,IF(DATE_ADD('2026-09-20',INTERVAL 4 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=4);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,5,DATE_ADD('2026-09-20',INTERVAL 5 MONTH),100000,0,100000,100000,IF(DATE_ADD('2026-09-20',INTERVAL 5 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=5);
INSERT INTO repayment_schedule (contract_id,sequence,due_date,principal_due,interest_due,total_payment_due,remaining_principal,status)
SELECT @cid,6,DATE_ADD('2026-09-20',INTERVAL 6 MONTH),100000,0,100000,0,IF(DATE_ADD('2026-09-20',INTERVAL 6 MONTH)<'2026-10-04','OVERDUE','PENDING') WHERE NOT EXISTS (SELECT 1 FROM repayment_schedule WHERE contract_id=@cid AND sequence=6);
COMMIT;
SELECT contract_id,contract_alias,creditor_id,debtor_id,principal_amount,status FROM loan_contract WHERE contract_alias LIKE '[테스트]%' AND creditor_id IN (889176,889179) AND debtor_id IN (889176,889179);
SELECT COUNT(*) AS schedule_count FROM repayment_schedule r JOIN loan_contract c ON c.contract_id=r.contract_id WHERE c.contract_alias LIKE '[테스트]%' AND c.creditor_id IN (889176,889179) AND c.debtor_id IN (889176,889179);

