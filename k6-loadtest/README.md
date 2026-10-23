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

실제 요청 부하는 실행할 시나리오를 정한 뒤 k6로 발생시킵니다. 기존 시나리오는 테스트 데이터를 만들거나 변경할 수 있으므로, 실행 전에 준비 절차와 데이터 요구 사항을 확인하세요.
