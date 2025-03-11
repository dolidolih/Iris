# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 HTTP 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **HTTP 서버:** Iris와 상호 작용하여 메시지를 처리하기 위한 별도의 HTTP 서버가 필요합니다.

### 설치

1.  **최신 Iris를 [Releases](https://github.com/dolidolih/Iris/releases)에서 다운로드하세요.**

2.  **파일 복사:**
    adb를 사용하여 Iris dex 파일을 안드로이드 환경에 복사하세요.
    ```bash
    adb push Iris.dex /data/local/tmp
    ```

3.  **dex 파일 실행:**

    iris_control을 실행 가능하게 만드세요.
    ```bash
    chmod +x iris_control
    ```

    실행하려면 iris_control을 사용하세요.
    ```bash
    ./iris_control start
    ```

    iris_control은 start/status/stop 명령어를 제공합니다.

4.  **Config 설정:**

    `http://[ANDROID_IP]:3000/dashboard` 에 브라우저를 통해 접속하여, 설정을 진행합니다.


### 사용법

Iris는 기본적으로 HTTP 프로토콜을 통해 정보를 주고 받습니다.

#### HTTP API 엔드포인트

모든 요청은 별도로 명시되지 않는 한 `Content-Type: application/json`과 함께 `POST` 요청으로 보내야 합니다.

*   **`/reply`**: 카카오톡 채팅방에 메시지 또는 사진을 보냅니다.

    **요청 본문 (JSON):**

    ```json
    {
      "type": "text",  // 또는 "image"
      "room": "[CHAT_ROOM_ID]", // 채팅방 ID (문자열)
      "data": "[MESSAGE_TEXT]"  // 텍스트 메시지의 경우
                                // 이미지 메시지의 경우 Base64 인코딩된 이미지 문자열
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

*   **`/query`**: 카카오톡 데이터베이스에 SQL 쿼리를 실행합니다. 이 메소드는 응답에서 암호화된 데이터 필드를 자동으로 복호화합니다.
    > `message` 또는 `attachment`를 `user_id` 및 `enc`와 함께 쿼리하면 복호화된 값을 반환합니다.
    > `nickname`, `profile_image_url`, `full_profile_image_url` 또는 `original_profile_image_url`을 `enc`와 함께 쿼리하면 복호화된 값을 반환합니다.

    **요청 본문 (JSON):**

    ```json
    // 단일 요청
    {
      "query": "[SQL_QUERY]",  // SQL 쿼리 문자열
      "bind": ["[BINDING_VALUE_1]", "[BINDING_VALUE_2]", ...] // 쿼리에 대한 선택적 바인딩
    }

    // 다중 요청 (벌크 쿼리)
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
          // 쿼리 결과 배열, 각 결과는 열 이름과 값의 맵입니다.
          {
            "_id": "...",
            "chat_id": "...",
            "user_id": "...",
            "message": "...", // 해독된 메시지 내용
            // ... 기타 열 ...
          },
          // ... 더 많은 결과 ...
        ],
        // 벌크 쿼리의 경우, 후속 쿼리 결과는 다음 배열 요소에 있습니다.
        [
          // 두 번째 쿼리에 대한 결과
          {
            "name": "...", // 해독된 이름
          },
          // ... 더 많은 결과 ...
        ],
        // ... 벌크 쿼리를 사용한 경우 더 많은 쿼리 결과 배열 ...
      ]
    }
    ```

*   **`/decrypt`**: 카카오톡 메시지를 복호화합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "enc": [ENCRYPTION_TYPE], // 암호화 유형 (데이터베이스에서 가져온 정수)
      "b64_ciphertext": "[BASE64_ENCODED_CIPHERTEXT]", // Base64 인코딩된 암호화된 메시지
      "user_id": [USER_ID] // 사용자 ID (long integer)
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

#### 설정 API 엔드포인트

*   **`/dashboard` (GET)**: Iris 를 설정하기 위한 웹 UI를 제공합니다. 웹 브라우저에서 이 URL을 열어 설정을 수정하세요.

    **예시:**

    ```bash
    # 웹 브라우저에서 여세요
    http://[YOUR_DEVICE_IP]:[bot_http_port]/dashboard
    ```

    이 엔드포인트는 웹 서버 엔드포인트, 데이터베이스 폴링 속도, 메시지 전송 속도와 같은 구성을 사용자 친화적인 인터페이스를 통해 보고 업데이트할 수 있는 웹 페이지를 제공합니다.

*   **`/config` (GET)**: 현재 구성을 JSON 응답으로 검색합니다. 현재 활성 설정을 확인하는 데 유용합니다.

    **예시:**

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/config
    ```

    **응답 (JSON):**

    ```json
    {
      "bot_name": "[YOUR_BOT_NAME]",
      "bot_http_port": [PORT_FOR_HTTP_SERVER],
      "web_server_endpoint": "[YOUR_WEB_SERVER_URL_FOR_MESSAGE_FORWARDING],
      "db_polling_rate": [DATABASE_POLLING_INTERVAL_IN_MILLISECONDS],
      "message_send_rate": [MESSAGE_SEND_INTERVAL_IN_MILLISECONDS],
      "bot_id": [YOUR_KAKAO_TALK_USER_ID]
    }
    ```

*   **`/config/endpoint` (POST)**: 메시지 전달을 위한 웹 서버 엔드포인트를 업데이트합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "endpoint": "[YOUR_WEB_SERVER_URL]"
    }
    ```

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"endpoint": "http://192.168.1.100:5000/new_messages"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/config/endpoint
    ```

*   **`/config/dbrate` (POST)**: 데이터베이스 폴링 속도를 업데이트합니다. 이 값을 조정하면 Iris가 데이터베이스에서 새 메시지를 확인하는 빈도가 변경됩니다. 값이 낮을수록 CPU 사용량이 증가하지만 메시지 감지가 더 즉각적일 수 있습니다.

    **요청 본문 (JSON):**

    ```json
    {
      "rate": [DATABASE_POLLING_INTERVAL_IN_MILLISECONDS]
    }
    ```

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"rate": 300}' http://[YOUR_DEVICE_IP]:[bot_http_port]/config/dbrate
    ```

*   **`/config/sendrate` (POST)**: 메시지 전송 속도를 업데이트합니다. 이는 카카오톡으로 메시지를 보내는 최소 간격을 제어하여 전송 빈도를 관리하는 데 도움이 됩니다.

    **요청 본문 (JSON):**

    ```json
    {
      "rate": [MESSAGE_SEND_INTERVAL_IN_MILLISECONDS]
    }
    ```

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"rate": 200}' http://[YOUR_DEVICE_IP]:[bot_http_port]/config/sendrate
    ```
*   **`/config/botport` (POST)**: 봇 HTTP 서버 포트를 업데이트합니다. **참고**: 이 변경 사항은 적용하려면 Iris를 재시작해야 합니다.

    **요청 본문 (JSON):**

    ```json
    {
      "port": [NEW_PORT_NUMBER]
    }
    ```

    **예시:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"port": 3001}' http://[YOUR_DEVICE_IP]:[bot_http_port]/config/botport
    ```

##### 메시지 전달을 위한 API 레퍼런스

Iris가 카카오톡 데이터베이스에서 새 메시지를 감지하면 `/config/endpoint` API를 통해 구성된 `web_server_endpoint`로 `POST` 요청을 보냅니다. 요청 본문은 다음 구조의 JSON 객체입니다.

```json
{
  "msg": "[DECRYPTED_MESSAGE_CONTENT]", // 복호화된 메시지 내용
  "room": "[CHAT_ROOM_NAME]",          // 채팅방 이름 또는 1:1 채팅의 경우 발신자 이름
  "sender": "[SENDER_NAME]",          // 메시지 발신자 이름
  "json": {                            // 'chat_logs' 테이블의 원시 데이터베이스 행 (JSON 형식)
    "_id": "...",
    "chat_id": "...",
    "user_id": "...",
    "message": "[DECRYPTED_MESSAGE_CONTENT]", // 복호화된 메시지 내용, "msg" 필드와 동일
    "attachment": "[DECRYPTED_ATTACHMENT_INFO]", // 복호화된 attachment 내용 
    "v": "{\"enc\": 0, ...}",           // 원래 'v' 열 값 (JSON 형식)
    // ... chat_logs 테이블의 기타 열 ...
  }
}
```

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
