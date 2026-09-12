# 개인 배포 트러블슈팅 (AWS EC2 마이그레이션)

> 원 팀 프로젝트를 개인 GitHub/AWS 인프라로 옮겨 배포하는 과정에서 혼자 겪은 문제들을 기록합니다. 위쪽 "Troubleshooting" 섹션이 팀 개발 중 코드 레벨에서 겪은 문제라면, 이 문서는 배포·인프라 단계에서 겪은 문제들입니다.

## Docker Compose 버전 불일치

- 문제: EC2에서 `docker compose up -d --build`(v2 플러그인 문법) 실행 시 `unknown shorthand flag: 'd' in -d` 에러
- 조사: 인스턴스에 설치된 건 v2 CLI 플러그인이 아니라 하이픈 방식의 v1 스타일 `docker-compose` 바이너리였음
- 해결: 모든 배포 명령을 `docker-compose`(하이픈)로 통일

## buildx 버전 문제

- 문제: `docker-compose up -d --build` 실행 시 `compose build requires buildx 0.17.0 or later`
- 조사: 설치된 buildx가 0.12.1로 구버전
- 해결: GitHub 릴리즈에서 최신 buildx 바이너리를 `~/.docker/cli-plugins/docker-buildx`에 직접 설치

## t3.micro CPU 크레딧 고갈로 SSH 접속 불가

- 문제: 첫 배포(이미지 빌드) 직후 SSH 접속이 완전히 먹통이 됨 (직접 SSH, EC2 Instance Connect 모두 실패)
- 조사: CloudWatch에서 CPU 크레딧 잔량이 0에 가깝게 떨어진 것을 확인 — t3.micro(버스터블) 인스턴스가 MySQL·백엔드·Nginx를 동시에 띄운 채로 무거운 Docker 빌드까지 돌리면서 크레딧을 소진
- 해결: 재부팅으로 응급 복구 후, t3.small로 인스턴스 유형 업그레이드 (다운타임 중 IP가 바뀌지 않도록 Elastic IP를 먼저 할당)

## GitHub Actions 이미지 태그 대문자 문제

- 문제: CD 파이프라인의 `docker/build-push-action`에서 `invalid tag ...: repository name must be lowercase`로 빌드 실패
- 조사: 태그에 쓴 `${{ github.repository_owner }}`가 계정명 그대로(`DANIELSUNWOO`, 대문자)로 치환되는데 Docker/OCI 태그는 소문자만 허용
- 해결: 태그를 소문자로 하드코딩(`danielsunwoo`)

## CD 배포가 SSH 단계에서 타임아웃

- 문제: 이미지 빌드·푸시는 성공하는데 배포(SSH 접속) 단계에서 `dial tcp ***:22: i/o timeout`
- 조사: EC2 보안 그룹의 SSH 인바운드 규칙이 "내 IP"로만 열려 있어서, GitHub Actions 러너의 (매번 바뀌는) IP가 차단됨
- 해결: 포트 22를 0.0.0.0/0으로 임시 개방 (알려진 트레이드오프 — 추후 SSM 전환도 검토했으나, 학습 다양성을 위해 SSH 유지 + 키 정기 교체로 결정)

## 카카오 로그인 4단계 디버깅 (KOE004 → KOE006 → 401 → NPE)

- 문제 1 (KOE004, 앱 관리자 설정 오류): 카카오 로그인 버튼 클릭 시 즉시 에러 페이지
  - 조사: [카카오 로그인] > [일반]의 "사용 설정"이 꺼져 있었음
  - 해결: 활성화 토글 ON
- 문제 2 (KOE006, 등록하지 않은 리다이렉트 URI): 카카오 인가 화면까지는 뜨지만 콜백에서 에러
  - 조사: 카카오가 앱 키 체계를 개편하면서 Redirect URI 등록 위치가 [카카오 로그인] > [일반]에서 **앱 키(REST API 키·JavaScript 키) 각각의 상세 설정**으로 이동함. 프론트가 JS SDK로 `authorize()`를 호출하므로 JavaScript 키 쪽에도 등록이 필요했음
  - 해결: REST API 키·JavaScript 키 양쪽의 "카카오 로그인 리다이렉트 URI"에 콜백 URL 등록
- 문제 3 (토큰 교환 401 Unauthorized): 인가 코드는 받았는데 액세스 토큰 교환이 401로 실패
  - 조사: REST API 키의 "클라이언트 시크릿"이 켜져 있었는데, 프론트 코드(브라우저에서 직접 토큰 교환)는 시크릿을 전송하지 않는 구조였음
  - 해결: 클라이언트 시크릿 비활성화 (이 프로젝트는 토큰 교환을 백엔드가 아니라 브라우저에서 직접 하므로, 시크릿을 켜봐야 프론트에 다시 노출해야 해서 의미가 없음)
- 문제 4 (로그인 처리 중 500, NPE): 토큰 교환은 성공했지만 백엔드 `/api/auth/kakao` 호출이 500
  - 조사: 백엔드 로그에서 `KakaoUserInfoResponse.getKakao_account()`가 null이라 `.getProfile()` 호출 시 NPE 발생. 카카오 [동의항목]에서 "닉네임"·"프로필 사진"이 모두 꺼져 있어서, 사용자 정보 응답 자체에 `kakao_account` 필드가 통째로 빠져 있었음
  - 해결: 동의항목에서 "닉네임"을 필수 동의로 전환

## S3 업로드는 성공하지만 이미지가 403으로 안 보임

- 문제: 게시글에 이미지를 첨부하면 등록은 되는데, 실제 이미지 URL 접근 시 403 Forbidden
- 조사: S3에 파일은 정상 업로드됐지만(404가 아니라 403이라는 게 힌트), 버킷의 퍼블릭 액세스 차단 설정이 켜져 있어 익명 GET이 막혀 있었음
- 해결: 버킷의 "퍼블릭 액세스 차단" 해제 + `s3:GetObject`를 퍼블릭으로 허용하는 버킷 정책 추가

## SSH 프라이빗 키 노출 및 교체 중 겪은 문제

- 상황: 트러블슈팅 중 터미널 화면을 캡처해서 공유하다가 `prep2gether-key.pem`의 프라이빗 키 전체 내용이 실수로 노출됨
- 대응: 새 키 쌍(`prep2gether-key-new`)을 생성해 교체하기로 결정 (SSM 전환은 학습 다양성을 위해 보류하고 SSH 유지 결정)
- 문제 1: `Get-Content 새키.pub | ssh ... "cat >> authorized_keys"` 방식(PowerShell 파이프)으로 서버에 공개키를 추가했더니 인코딩이 깨져서, 키 문자열 앞에 의도치 않은 문자가 붙고 주석의 한글이 `???`로 깨짐 → 새 키로 로그인 실패
  - 해결: 파이프 대신 `scp`로 공개키 파일 자체를 서버에 전송한 뒤, 서버에서 `cat >> authorized_keys`로 추가 (파일 전송 방식이라 인코딩 손상 없음)
- 문제 2: 새 키 파일을 `~/.ssh`로 옮긴 뒤, 이전 위치(Downloads) 기준 명령을 그대로 재실행해서 "파일을 찾을 수 없음" 에러
  - 해결: 새 파일 위치로 `cd` 이동 후 재시도
- 마무리: 서버의 `authorized_keys`에서 노출된 구 키를 제거하고, 이 키로 CD가 접속하는 GitHub Secrets(`EC2_SSH_KEY`)도 함께 새 프라이빗 키로 갱신 — 서버 쪽 키만 바꾸고 이 시크릿을 안 바꾸면 자동 배포가 끊긴다는 점이 포인트
