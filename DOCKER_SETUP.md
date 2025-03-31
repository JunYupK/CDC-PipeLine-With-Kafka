# Docker를 이용한 크롤러 환경 설정

이 문서에서는 Docker를 사용하여 Crawl4AI 환경을 설정하고 크롤러를 실행하는 방법을 설명합니다.

## 사전 요구 사항

- Docker가 설치되어 있어야 합니다.
- Docker Compose가 설치되어 있어야 합니다.

## 설치 및 실행 방법

### 1. Docker Compose 파일 준비

프로젝트 루트 디렉토리의 `docker-compose.yml` 파일을 사용합니다. 이 파일은 Crawl4AI 서비스를 실행하기 위한 설정을 포함하고 있습니다.

### 2. Docker 컨테이너 실행

다음 명령어로 Docker 컨테이너를 실행합니다:

```bash
cd C:\Users\top15\Desktop\ETL
docker-compose up -d
```

`-d` 옵션은 컨테이너를 백그라운드에서 실행합니다.

### 3. 서비스 상태 확인

다음 명령어로 서비스 상태를 확인할 수 있습니다:

```bash
docker ps
```

또는 테스트 스크립트를 실행하여 서비스 연결을 확인할 수 있습니다:

```bash
python test_docker_crawler.py
```

### 4. 크롤러 실행

수정된 코드는 Docker 컨테이너에서 실행되는 Crawl4AI API를 호출하도록 변경되었습니다. 기존 방식으로 테스트 스크립트를 실행할 수 있습니다:

```bash
python CrawlingServer/test_news_crawler.py
```

또는 스포츠 크롤러를 실행할 수 있습니다:

```bash
python CrawlingServer/test_sports_crawler.py 야구
```

## Docker 환경 설정 옵션

`docker-compose.yml` 파일에서 다음 설정을 변경할 수 있습니다:

- `MAX_CONCURRENT_TASKS`: 동시에 실행할 수 있는 최대 크롤링 작업 수
- `CRAWL4AI_API_TOKEN`: API 보안을 위한 토큰 (설정하면 API 호출에 인증이 필요함)
- `memory` 제한: 컨테이너가 사용할 수 있는 최대 메모리 양

## 문제 해결

### 컨테이너가 시작되지 않는 경우

다음 명령어로 로그를 확인할 수 있습니다:

```bash
docker logs crawl4ai-server
```

### API에 연결할 수 없는 경우

1. 컨테이너가 실행 중인지 확인합니다:
   ```bash
   docker ps
   ```

2. 포트가 제대로 매핑되었는지 확인합니다:
   ```bash
   docker-compose ps
   ```

3. 방화벽 설정을 확인합니다.

### 크롤링 작업이 실패하는 경우

1. Docker 컨테이너의 로그를 확인합니다.
2. 메모리 한도를 늘려보세요.
3. `MAX_CONCURRENT_TASKS` 값을 줄여보세요.

## Docker 컨테이너 중지

다음 명령어로 Docker 컨테이너를 중지할 수 있습니다:

```bash
docker-compose down
```

## 주의 사항

- Docker 환경에서는 메모리 사용량을 모니터링해야 합니다. 크롤링 작업은 메모리를 많이 사용할 수 있습니다.
- 동시에 너무 많은 크롤링 작업을 실행하면 성능이 저하될 수 있습니다.
- API 토큰을 사용하려면 코드에서 인증 헤더를 추가해야 합니다.
