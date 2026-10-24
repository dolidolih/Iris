# Iris

## 필요 환경
* JDK 17 (JDK 20+ 는 Kotlin/KAPT 툴체인과 맞지 않습니다)
* Android SDK (`local.properties` 의 `sdk.dir` 로 지정)
* 별도 jar 다운로드 불필요 — hidden API 는 전부 `java.lang.reflect` 로 런타임에 얻습니다.

## 빌드
```shell
./gradlew :app:assembleDebug
```
산출물은 `output/Iris-debug.apk` 에 복사됩니다. `libsqlcipher.so` 가 `lib/arm64-v8a/` 와 `lib/x86_64/` 에 함께 들어갑니다.

## 실행 (root 가 이미 잡혀 있어야 합니다)
```shell
# APK 를 장치로
adb push output/Iris-debug.apk /data/local/tmp/Iris.apk

# 기동 — app_process 로 띄우고, CLASSPATH 에 APK 를 넣습니다.
CLASSPATH=/data/local/tmp/Iris.apk app_process / party.qwer.iris.Main
```
`app_process` 로 띄운 프로세스는 `nativeLibraryDir` 를 만들지 못하므로, SQLCipher 라이브러리는 위 명령으로 기동된 뒤에야 `/data/local/tmp/Iris.work/native/` 에 자동 추출됩니다.

## 데몬 동작 조건
* `/data/local/tmp/config.json` — 설정 파일. 없으면 기본값으로 생성되고, 이후 변경은 전부 원자적으로(`.tmp` → rename, `.bak` 백업) 저장됩니다. 파일이 깨져 있어도 백업에서 복구합니다.
* `/data/local/tmp/Iris.work/` — owner-only(0700) 작업 디렉터리. 암호화된 KakaoTalk DB 사본과 `libsqlcipher.so` 추출물이 여기 놓입니다. 원문 DB 는 절대 직접 열지 않습니다.
* `/data/local/tmp/Iris.log` — 로그. 없으면 stdout 으로만 나갑니다.
* KakaoTalk 의 uid 로 Android Keystore `keystore2` 를 직접 두드려 `crypto_user_database` 키를 unwrap 합니다. root 라도 shell uid 로는 실패하므로 반드시 root 컨텍스트(`su 0` / `su root`)에서 실행해야 합니다.

## 검증
`app_process` 로 기동했다면 HTTP 포트(기본 3000)에서 `/process-status` 를 보세요. `db_observing`, `bot_id_resolved`, `sqlcipher.*` 가 한 화면에 나옵니다. `/dashboard` 는 GUI 없이 도는 데몬을 위한 유일한 화면입니다.
