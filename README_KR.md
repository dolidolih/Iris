# Iris - An Android native db observer / message broker / reply sender for Kakaotalk bot

이 프로젝트는 카카오톡과의 상호 작용을 자동화하고, 데이터베이스에서 데이터를 추출하며, HTTP 서버를 통해 원격으로 제어할 수 있도록 합니다. Java로 빌드되었으며 Android 기기에서 실행되도록 설계되었고, 시스템 서비스와 직접적인 데이터베이스 접근을 활용합니다.

**프로젝트 상태:** 개발 진행 중 (WIP)

## 기능

*   **메시지 전송:**
    *   애플리케이션 또는 HTTP 요청을 통해 카카오톡 채팅방에 텍스트 메시지를 직접 보냅니다.
    *   카카오톡 채팅방에 사진을 보냅니다 (HTTP를 통해 base64 인코딩된 이미지 지원).
    *   메시지는 설정된 지연 시간을 준수하여 전송 속도를 관리하기 위해 큐 시스템을 통해 전송됩니다.
*   **카카오톡 메시지 복호화:**
    *   카카오톡 데이터베이스에서 암호화된 메시지를 복호화합니다.
    *   요청 시 메시지를 복호화하는 HTTP 엔드포인트를 제공합니다.
*   **카카오톡 데이터베이스 쿼리:**
    *   HTTP 요청을 통해 카카오톡 데이터베이스에 대해 임의의 SQL 쿼리를 실행합니다.
    *   하나의 요청에서 단일 및 **벌크 SQL 쿼리**를 지원합니다.
    *   SQL 인젝션 방지 및 효율성 향상을 위해 **바인딩을 사용한 매개변수화된 쿼리**를 지원합니다.
    *   채팅 로그, 사용자 정보 등 (데이터베이스 스키마에 따라 다름)과 같은 데이터를 검색합니다.
*   **실시간 메시지 모니터링:**
    *   새로운 메시지를 위해 카카오톡 데이터베이스를 모니터링합니다.
    *   새로운 메시지를 자동으로 복호화하고 설정 가능한 웹 서버로 HTTP POST 요청을 통해 전달합니다.
    *   **복호화된 내용, 발신자, 채팅방 이름을 포함한 자세한 메시지 정보를 웹 서버로 보냅니다.**
*   **원격 제어를 위한 HTTP API:**
    *   메시지 전송, 데이터베이스 쿼리, 메시지 복호화를 위한 간단한 HTTP API를 제공합니다.
    *   다른 애플리케이션 또는 스크립트에서 프로그래밍 방식으로 카카오톡 인스턴스를 제어합니다.
*   **JSON을 통한 구성:**
    *   구성 설정 (봇 ID, 봇 이름, 서버 포트, 웹 서버 엔드포인트, **데이터베이스 폴링 속도, 메시지 전송 속도** 등)은 `config.json` 파일에서 로드됩니다.
*   **HTTP 및 쿼리 응답을 통한 복호화:**
    *   웹 서버로 데이터베이스 레코드를 보낼 때와 `/query` 응답에서 메시지 내용, 첨부 파일, 닉네임, 프로필 URL을 포함하여 관련된 모든 값을 복호화합니다.
*   **자동 이미지 정리:**
    *   공간을 절약하기 위해 임시 이미지 저장 디렉토리에서 오래된 이미지 (24시간 이상 된 이미지)를 주기적으로 삭제합니다.

## 시작하기

### 필요 조건

*   **Android 기기:** 이 애플리케이션은 카카오톡이 설치되어 있고 시스템 서비스 및 애플리케이션 데이터에 접근하는 데 필요한 권한이 있는 Android 기기에서 실행되도록 설계되었습니다.
*   **Root 권한:** Android 버전 및 보안 설정에 따라 카카오톡 데이터베이스 및 일부 시스템 서비스에 접근하려면 Android 기기에서 root 권한이 필요할 수 있습니다.
*   **`config.json`:** `config.json` 파일이 Android 기기의 `/data/local/tmp/`에 위치해야 합니다.
*   **HTTP 서버:** Iris와 상호 작용하려면 HTTP 서버가 필요합니다.

### 설정

1.  **리포지토리 복제:** (코드를 배포하는 경우)
    ```bash
    git clone [repository-url]
    cd [repository-directory]
    ```

2.  **`config.json` 구성:**
    `config.json` 파일을 생성하고 Android 기기의 `/data/local/tmp/`에 넣습니다. 파일은 다음 구조를 가져야 합니다:

    ```json
    {
      "bot_name": "[YOUR_BOT_NAME]",
      "bot_http_port": [PORT_FOR_HTTP_SERVER],
      "web_server_endpoint": "[YOUR_WEB_SERVER_URL_FOR_MESSAGE_FORWARDING],
      "db_polling_rate": [DATABASE_POLLING_INTERVAL_IN_MILLISECONDS], // 선택 사항, 기본값 100ms
      "message_send_rate": [MESSAGE_SEND_INTERVAL_IN_MILLISECONDS]    // 선택 사항, 기본값 50ms
    }
    ```
    *   `bot_name`: 봇의 이름 (식별에 사용).
    *   `bot_http_port`: HTTP 서버가 수신 대기할 포트 번호 (예: `3000`).
    *   `web_server_endpoint`: 새로운 카카오톡 메시지를 수신할 웹 서버의 URL (예: `http://172.17.0.2:5000/db for IrisPy`).
    *   `db_polling_rate` (선택 사항): 데이터베이스 변경 사항을 확인하는 간격 (밀리초 단위) (예: `200`). 값이 낮을수록 더 자주 확인하여 CPU 사용량이 증가할 수 있습니다.
    *   `message_send_rate` (선택 사항): 카카오톡 메시지 전송 간 최소 간격 (밀리초 단위) (예: `100`). 메시지 전송 속도를 제어하고 과도한 메시지 전송을 방지하는 데 사용합니다.

3.  **파일 복사:**
    adb를 사용하여 파일을 Android 환경에 복사합니다.
    ```bash
    adb push Iris.dex /data/local/tmp
    adb push config.json /data/local/tmp
    ```

4.  **dex 파일 실행:**
    서비스로 실행하는 경우 (CTRL+C를 눌러도 Iris는 백그라운드에서 실행됩니다)
    ```bash
    adb shell "su root sh -c 'CLASSPATH=/data/local/tmp/Iris.dex /system/bin/app_process / Iris' &"
    ```
    로그를 보면서 실행하고 싶은 경우 (CTRL+C를 누르면 Iris가 중지됩니다)
    ```bash
    adb shell
    su
    sh -c 'CLASSPATH=/data/local/tmp/Iris.dex /system/bin/app_process / Iris'
    ```


### 사용법

애플리케이션이 Android 기기에서 실행되면 HTTP 요청을 사용하여 상호 작용할 수 있습니다.

#### HTTP API 엔드포인트

HTTP 서버는 `config.json` (`bot_http_port`)에 지정된 포트에서 수신 대기합니다. 모든 요청은 `Content-Type: application/json`으로 `POST` 요청으로 보내야 합니다.

*   **`/reply`**: 카카오톡 채팅방에 메시지 또는 사진을 보냅니다.

    **요청 본문 (JSON):**

    ```json
    {
      "type": "text",  // 또는 "image"
      "room": "[CHAT_ROOM_ID]", // 채팅방 ID (문자열)
      "data": "[MESSAGE_TEXT]"  // 텍스트 메시지의 경우
                                // 이미지 메시지의 경우 base64 인코딩된 이미지 문자열
    }
    ```

    **예시 (텍스트 메시지):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "text", "room": "1234567890", "data": "SendMsgDB에서 보낸 메시지!"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    ```

    **예시 (이미지 메시지):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "image", "room": "1234567890", "data": "[BASE64_ENCODED_IMAGE_DATA]"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    ```

*   **`/query`**: 카카오톡 데이터베이스에 대한 SQL 쿼리를 실행합니다. 이 메서드는 응답에서 암호화된 데이터 필드를 자동으로 복호화합니다.
    > `message` 또는 `attachment`를 `user_id` 및 `enc`와 함께 쿼리하면 복호화된 값을 반환합니다.
    > `nickname`, `profile_image_url`, `full_profile_image_url` 또는 `original_profile_image_url`을 `enc`와 함께 쿼리하면 일반 텍스트를 반환합니다.

    **요청 본문 (JSON):**

    ```json
    // 단일 요청의 경우
    {
      "query": "[SQL_QUERY]",  // SQL 쿼리 문자열
      "bind": ["[BINDING_VALUE_1]", "[BINDING_VALUE_2]", ...] // 쿼리에 대한 선택적 바인딩
    }

    // 다중 요청의 경우 (벌크 쿼리)
    {
      "queries":[
        {
          "query": "[SQL_QUERY_1]",
          "bind": ["[BINDING_VALUE_1]", "[BINDING_VALUE_2]", ...] // 쿼리 1에 대한 선택적 바인딩
        },
        {
          "query": "[SQL_QUERY_2]",
          "bind": [] // 쿼리 2에 대한 선택적 바인딩
        },
        // ... 더 많은 쿼리 ...
      ]
    }
    ```

    **예시 (바인딩을 사용한 단일 쿼리):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs WHERE user_id = ? ORDER BY _id DESC LIMIT 5", "bind": ["1234567890"]}' http://[YOUR_DEVICE_IP]:[bot_http_port]/query
    ```

    **예시 (벌크 쿼리):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"queries": [{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5", "bind": []}, {"query": "SELECT name FROM db2.friends LIMIT 2", "bind": []}]}' http://[YOUR_DEVICE_IP]:[bot_http_port]/query
    ```

    **응답 (JSON):**

    ```json
    {
      "success": true,
      "data": [
        // 단일 쿼리 또는 벌크 쿼리의 첫 번째 쿼리의 경우
        [
          // 쿼리 결과 배열, 각 결과는 열 이름-값 맵입니다.
          {
            "_id": "...",
            "chat_id": "...",
            "user_id": "...",
            "message": "...", // 복호화된 메시지 내용
            // ... 기타 열 ...
          },
          // ... 더 많은 결과 ...
        ],
        // 벌크 쿼리의 경우, 후속 쿼리 결과는 다음 배열 요소에 있습니다.
        [
          // 벌크 쿼리의 두 번째 쿼리에 대한 결과
          {
            "name": "...", // 복호화된 이름
          },
          // ... 더 많은 결과 ...
        ],
        // ... 벌크 쿼리가 사용된 경우 더 많은 쿼리 결과 배열 ...
      ]
    }
    ```

*   **`/decrypt`**: 카카오톡 메시지를 복호화합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "enc": [ENCRYPTION_TYPE], // 암호화 유형 (데이터베이스에서 가져온 정수)
      "b64_ciphertext": "[BASE64_ENCODED_CIPHERTEXT]", // Base64 인코딩된 암호화된 메시지
      "user_id": [USER_ID] // 사용자 ID (긴 정수), 선택 사항, config의 bot_id가 기본값
    }
    ```

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"enc": 0, "b64_ciphertext": "[ENCRYPTED_MESSAGE_BASE64]", "user_id": 1234567890}' http://[YOUR_DEVICE_IP]:[bot_http_port]/decrypt
    ```

    **응답 (JSON):**

    ```json
    {
      "plain_text": "[DECRYPTED_MESSAGE_TEXT]"
    }
    ```

#### 메시지 전달을 위한 API 참조

Iris가 카카오톡 데이터베이스에서 새 메시지를 감지하면 `config.json`에 구성된 `web_server_endpoint`로 `POST` 요청을 보냅니다. 요청 본문은 다음 구조의 JSON 객체입니다:

```json
{
  "msg": "[DECRYPTED_MESSAGE_CONTENT]", // 복호화된 메시지 텍스트
  "room": "[CHAT_ROOM_NAME]",          // 채팅방 이름 또는 1:1 채팅의 경우 발신자 이름
  "sender": "[SENDER_NAME]",          // 메시지 발신자 이름
  "json": {                            // 'chat_logs' 테이블의 원시 데이터베이스 행을 JSON으로 표현
    "_id": "...",
    "chat_id": "...",
    "user_id": "...",
    "message": "[DECRYPTED_MESSAGE_CONTENT]", // 복호화된 메시지 내용, "msg" 필드와 동일
    "attachment": "[DECRYPTED_ATTACHMENT_INFO]", // 복호화된 첨부 파일 정보 (있는 경우)
    "v": "{\"enc\": 0, ...}",           // 원본 'v' 열 값 (JSON 형식)
    // ... chat_logs 테이블의 기타 열 ...
  }
}
```

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.