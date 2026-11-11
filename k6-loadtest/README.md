# 사이원장 부하 테스트 및 JVM 모니터링

k6는 테스트 요청을 보내고, Prometheus는 사이원장 JVM 지표를 수집하며, Grafana는 두 결과를 대시보드로 보여줍니다. k6, Prometheus, Grafana는 테스트 PC에서 실행하고 애플리케이션과 관리 엔드포인트는 아카데미 VM에서 실행합니다.

## 아카데미 VM에서 JVM 지표 켜기

`loadtest` Spring 프로필을 적용하면 관리 포트 `8082`에서 헬스와 Prometheus 엔드포인트만 열립니다. 일반 애플리케이션 포트는 그대로 유지됩니다. Compose overlay는 아카데미 Compose 파일에 애플리케이션 서비스가 `backend`라는 이름으로 정의되어 있다고 가정합니다.

별도의 IP 설정은 필요하지 않습니다. Compose가 VM의 8082 포트를 열면 같은 학교 네트워크의 테스트 PC에서 접속할 수 있습니다. 테스트 PC의 Prometheus가 접근할 수 있도록 학교 서버의 네트워크 정책에서 TCP 8082를 허용하세요. 이 포트는 테스트 네트워크에서만 접근 가능해야 합니다.

VM에서 백엔드 저장소 디렉터리로 이동해 두 Compose 파일의 설정을 확인한 다음 백엔드를 다시 빌드하고 실행합니다.

```bash
sudo docker compose --env-file .env -f docker-compose.academy.yml -f docker-compose.academy-loadtest.yml config -q
sudo docker compose --env-file .env -f docker-compose.academy.yml -f docker-compose.academy-loadtest.yml up -d --build backend
```

아카데미 네트워크에서 접근 가능한 PC에서 VM 사설 IP로 연결을 확인합니다. 주소는 실제 VM IP로 바꾸세요.

```bash
curl http://YOUR_SAIWONJANG_SERVER_LAN_IP:8082/actuator/health
curl http://YOUR_SAIWONJANG_SERVER_LAN_IP:8082/actuator/prometheus
```

헬스 엔드포인트는 상태 JSON을 반환하고 Prometheus 엔드포인트는 JVM 메모리, 스레드, GC 지표가 포함된 텍스트를 반환해야 합니다.

## 테스트 PC에서 Prometheus 설정

예시 파일을 복사하고 VM의 사설 IP로 바꿉니다. 이 주소는 사이원장 VM에 접속할 때 사용하는 주소이며, 앞서 확인한 `192.168.0.205` 같은 형태입니다.

```powershell
Copy-Item .\prometheus\targets.json.example .\prometheus\targets\sai-backend.json
notepad .\prometheus\targets\sai-backend.json
```

Prometheus는 `prometheus/targets` 디렉터리의 JSON 파일을 읽습니다. 디렉터리는 저장소에 포함되어 있으므로, 타깃 파일을 만들기 전에 Prometheus를 시작해도 마운트 오류가 나지 않습니다. `sai-backend.json`은 Git에서 제외되어 VM 사설 IP가 저장소에 올라가지 않습니다.

`k6-loadtest` 디렉터리에서 로컬 모니터링 도구를 실행합니다.

```powershell
docker compose -f .\docker-compose.loadtest.yml up -d influxdb prometheus grafana
```

Grafana는 `http://localhost:3000`에서 열고, Prometheus 대상 상태는 `http://localhost:9090/targets`에서 확인합니다. `sai-backend` 대상이 `UP`으로 표시되어야 합니다.

## DB 커넥션 대기 지표 수집

모니터링 도구를 실행한 상태에서 저장소 루트에서 실제 백엔드 관리 주소를 등록합니다.

```powershell
.\k6-loadtest\register-backend.ps1 -ManagementUrl http://YOUR_SAIWONJANG_SERVER_LAN_IP:8082
```

스크립트는 Git에서 제외된 `prometheus/targets/sai-backend.json`을 생성하고, Prometheus에서 필수 HikariCP 지표를 확인합니다. 파일 탐색에는 최대 30초, 이후 수집에는 약 5초가 걸립니다. 확인에 실패해도 대상 설정은 남으므로 `/targets`에서 연결 오류를 확인하세요. 로컬 백엔드는 `http://localhost:8082`를 전달하면 Docker용 `host.docker.internal`로 등록합니다.

Grafana의 **SAI DB Connections** 대시보드 (`http://localhost:3000/d/sai-db-connections`)에서 다음을 확인할 수 있습니다.

| 지표 | 의미 |
| --- | --- |
| `hikaricp_connections_pending` | 커넥션을 기다리는 스레드 수 |
| `hikaricp_connections_active / idle / max` | 사용 중 / 유휴 / 최대 커넥션 수 |
| `hikaricp_connections_acquire_seconds_count / sum` | 획득 횟수 및 누적 획득 시간, 1분 rate 비율로 평균 계산 |
| `hikaricp_connections_acquire_seconds_max` | 라이브러리가 제공하는 시간 창 내 최대 획득 시간 |
| `hikaricp_connections_timeout_total` | 커넥션 획득 타임아웃 누적 횟수 |

`account-sync-load.js` 실행 전 수집 성공을 확인하세요. k6의 `sync_failed`와 같은 시간대를 대시보드에서 선택하면 실패 시점의 풀 상태를 비교할 수 있습니다. 평균 획득 시간은 획득이 없는 구간에서 표시되지 않을 수 있습니다. 5초보다 짧은 대기 급증은 샘플 사이에서 누락될 수 있고, 타임아웃 증가는 커넥션 획득 실패를 보여주지만 HTTP 실패와 일대일로 대응하지는 않습니다. 수집 시작 이전의 과거 지표는 복구되지 않습니다.

실제 요청 부하는 실행할 시나리오를 정한 뒤 k6로 발생시킵니다. 기존 시나리오는 테스트 데이터를 만들거나 변경할 수 있으므로, 실행 전에 준비 절차와 데이터 요구 사항을 확인하세요.
