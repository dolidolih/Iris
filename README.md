# Iris - 안드로이드 네이티브 DB기반 봇 프레임워크

이 프로젝트는 카카오톡 안드로이드 앱의 데이터베이스와 연동하여 HTTP 기반 채팅 봇을 작성할 수 있는 환경을 제공합니다.

**프로젝트 상태:** 베타

## 시작하기

### 필요 조건

*   **안드로이드 기기:** 이 애플리케이션은 카카오톡이 설치되어 있는 안드로이드 기기에서 실행되도록 설계되었습니다.
*   **루트 권한:** 카카오톡 데이터베이스와 일부 시스템 서비스에 접근하기 위하여 루트 권한이 필요합니다.
*   **HTTP 서버 또는 WebSocket 클라이언트:** Iris와 상호 작용하여 메시지를 처리하기 위한 별도의 HTTP 서버나 WebSocket 클라이언트가 필요합니다.

### 설치

1.  **최신 Iris를 [Releases](https://github.com/dolidolih/Iris/releases)에서 다운로드하세요.**

2.  **파일 복사:**
    adb를 사용하여 Iris apk 파일을 안드로이드 환경에 복사하세요.
    ```bash
    adb push Iris.apk /data/local/tmp
    ```

3.  **apk 파일 실행:**

    iris_control을 실행 가능하게 만드세요.(윈도우 이용자는 skip)
    ```bash
    chmod +x iris_control
    ```

    실행하려면 iris_control을 사용하세요.(윈도우 이용자는 ./iris_control.ps1)
    ```bash
    ./iris_control start
    ```

    iris_control은 install/start/status/stop 명령어를 제공합니다.

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
      "type": "text",  // 또는 "image", "image_multiple"
      "room": "[CHAT_ROOM_ID]", // 채팅방 ID (문자열)
      "data": "[MESSAGE_TEXT]"  // 텍스트 메시지의 경우
                                // 이미지 메시지의 경우 Base64 인코딩된 이미지 문자열
                                // 여러 이미지 메시지의 경우 Base64 인코딩된 이미지 문자열의 리스트
    }
    ```

    **예시 (텍스트 메시지):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "text", "room": "1234567890", "data": "SendMsgDB에서 보낸 메시지!"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    ```

    **예시 (이미지 메시지):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "image", "room": "1234567890", "data": "[BASE64_ENCODED_IMAGE_DATA]"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    curl -X POST -H "Content-Type: application/json" -d '{"type": "image_multiple", "room": "1234567890", "data": [BASE64_ENCODED_IMAGE_DATA1,BASE64_ENCODED_IMAGE_DATA2,BASE64_ENCODED_IMAGE_DATA3]}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
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
      "data": [
          // 쿼리 결과 배열, 각 결과는 열 이름과 값의 맵입니다.
          {
            "_id": "...",
            "chat_id": "...",
            "user_id": "...",
            "message": "...", // 해독된 메시지 내용
            // ... 기타 열 ...
          },
          // ... 더 많은 결과 ...
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

*   **`/aot` (GET)**: aot 관련 토큰을 리턴합니다.

    **예시:**

    ```bash
    curl -X GET http://[YOUR_DEVICE_IP]:[bot_http_port]/aot
    ```

    **응답 (JSON):**

    ```json
    {
      "success": true,
      "aot" : {
        "access_token" : String,
        "refresh_token" : String,
        "token_type" : String,
        "d_id" : String
      }
    }
    ```


#### 조회 API 엔드포인트

카카오톡 DB를 직접 조립하지 않고 방/사람/메시지를 찾기 위해 쓰는 엔드포인트들입니다. `db2`(`database_encrypted` + `db2.sqlite`)를 붙여서 읽으므로 별도 루팅 작업이 필요 없고, `db2` 의 `room`/`member` 테이블은 암호화되어 있지 않아 복호화 없이 동작합니다.

*   **`/rooms` (GET)**: 최근 채팅방 목록. `?limit=`(1~100, 기본 30) 으로 건수를 조절합니다. `chat_title.title` 이 없으면 `chat_rooms.display_name` 으로 대체합니다.

    ```bash
    curl "http://[YOUR_DEVICE_IP]:[bot_http_port]/rooms?limit=10"
    ```

    **응답 (JSON):** `{ "rooms": [ { "id": Long, "name": String } ] }`

*   **`/room/{roomId}` (GET)**: 방 이름. `chat_title` → `chat_rooms.display_name` 순으로 찾습니다. 없으면 `name: null`.
*   **`/room/{roomId}/members` (GET)**: 방 구성원 (`id`, `name`, `profile_image_url`). `?limit=`(1~1000, 기본 256) 으로 방정원이 많은 계정도 넘기지 않습니다.
*   **`/room/{roomId}/links` (GET)**: 방이 가진 링크 카드 (`id`, `url`, `image_url`, `title`, `description`, `member_limit`, `searchable`). 링크 카드가 없거나 방이 존재하지 않으면 빈 배열.
*   **`/user/{userId}` (GET)**: 사용자 프로필 (`id`, `name`, `profile_image_url`). `users` 테이블을 먼저 보고 없으면 `friend` 에서 찾습니다.
*   **`/user/name/{userId}` (GET)**: 표시 이름만 (`name: null` 일 수 있음).
*   **`/chat/{roomId}/messages` (GET)**: 커서 페이지네이션 메시지 목록. `?limit=`(1~100, 기본 50), `?cursor=`(previous page의 `next_cursor`, `before_id` 로 재사용).

    ```json
    { "items": [ { "msg": "text", "room": "room name", "sender": "nickname", "json": { ...raw row... }, "extension": { ... } } ],
      "next_cursor": Long, "has_more": true }
    ```

    `json` 은 `db2.chat_logs` 의 raw 행이고, `extension` 은 `attachment` JSON 입니다. 기본은 `json` 만 실리며 `extension` 은 `null` — `POST /config/extension` 을 `true` 로 켜면 들어옵니다.
*   **`/chat/{logId}` (GET)**: 한 건의 메시지. `json` 필드 하나만 돌아갑니다. 없으면 `json: null`.
*   **`/chat/{logId}/deleted` (GET)**: 삭제된 메시지 여부 (`{ "deleted": bool, "message": String }`).
*   **`/chat/{roomId}/deleted` (GET)**: 그 방에서 삭제 표시된 메시지 목록.
*   **`/chat/{logId}/{count}/prev` (GET)**: 이 메시지부터 앞쪽(count 건)으로 읽습니다. `/chat/123/prev/10` 처럼 씁니다.
*   **`/reactions/{logId}` (GET)**: `reaction` 테이블 내용. 이 KakaoTalk 빌드에서는 `log_id` 가 `chat_logs._id` 기준이라 방장자 `_id` 로 조회합니다.
*   **`/search` (POST)**: 메시지 전문 검색. `crypto_database` 의 `chat_log_search` 를 SQLCipher 로 읽습니다.

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"query":"테스트","limit":10}' http://[YOUR_DEVICE_IP]:[bot_http_port]/search
    ```

    **응답 (JSON):** `{ "hits": [ { "id": Long, "preview": String, "room_id": Long?, "room_name": String?, "sender_id": Long?, "sender_name": String? } ] }` — `crypto_database` 접근 불가나 라이브 미적재 시 `hits: []`.

#### 런타임 / 상태 API 엔드포인트

*   **`/process-status` (GET)**: 데몬 전체 상태 한 방. `server_running`, `db_observing`, `port`, `bot_id`, `bot_id_resolved`(false 이면 salt 가 0 으로 굳어 자기 메시지 복호가 깨집니다), `uptime_ms`, `sqlcipher`(라이브 적재 경로/출처/암호화 DB 접근 가능 여부), `ws_subscribers`(지금 붙어있는 `/ws` 구독자 수), `logs`(데몬 로그, 최신 선행), `last_logs`(최근 수신 채팅 raw 행).

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/process-status
    ```

*   **`/process-command` (POST)**: `{"command": "stop"}` 또는 `{"command": "restart"}`. `stop` 은 서버 엔진까지 종료하므로 재가동에는 별도 기동이 필요합니다.
*   **`/openapi.json` (GET)** / **`/swagger` (GET)**: OpenAPI 3.1 명세와 Swagger UI. 브라우저가 CDN 에서 UI 번들을 load 하므로 오프라인이면 `openapi.json` 링크로 대체됩니다.

#### SQLCipher 암호화 DB

KakaoTalk 는 이제 `crypto_database`(메시지 전문 검색 인덱스)와 `crypto_user_database`(사용자/친구 표시 이름)를 AES-256-CBC(SQLCipher) 로 암호화해 보관합니다. Iris 는 같은 SQLCipher 라이브러리를 정적 의존성으로 들고 있어서, 읽기 전용으로 열어 원문을 그대로 조회합니다.

* 키는 Android Keystore `KAKAO_KEY_STORAGE`/`EC`(non-exportable EC key) 로 래핑된 `database_encrypted/kakao.db.key` 를 unwrap 하여 얻습니다. Keystore 접근은 KakaoTalk 의 uid 로 `keystore2` 를 직접 두드리는 방식이라, root 라도 shell uid 로는 실패합니다.
* 라이브는 `libsqlcipher.so` 를 APK 에서 추출해 `/data/local/tmp/Iris.work/native/` 에 놓고 `System.load` 합니다. `app_process` 로 띄운 프로세스는 `nativeLibraryDir` 를 만들지 못하므로 `System.loadLibrary("sqlcipher")` 가 다른 앱의 라이브러리를 집어올 수 있는데, 이 경우 `/process-status` 의 `sqlcipher.foreign` 이 `true` 로 찍히고 키 오류와 구별이 불가능해집니다(그래서 `source` 필드로 출처를 먼저 나타냅니다).
* 원문 DB 는 절대 열지 않고 `/data/local/tmp/Iris.work/` 에 owner-only 사본을 만들어 `PRAGMA query_only` 로 읽습니다. 키 풀 실패는 조용히 무시되고 관련 엔드포인트는 빈 결과를 반환합니다.

*   **`/config/types` (POST)**: `/ws`·webhook 으로 내보낼 메시지 타입 필터. `types` 가 `null` 이면 기존 동작(type `1`/`2` + `attachment` 있는 case)을 유지하고, 배열을 주면 그 타입만 내보냅니다. `include_system_events` 는 type `5` 처럼 내용이 비어 있는 시스템 이벤트까지 포함할지 결정합니다.

    ```json
    { "types": [1, 2, 12], "include_system_events": false }
    ```

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"types":[1],"include_system_events":false}' http://[YOUR_DEVICE_IP]:[bot_http_port]/config/types
    ```

*   **`/config/extension` (POST)**: 프레임의 `extension` 필드(`attachment` JSON + 타입 분류) 포함 여부. 기본 `false`. 이 플래그는 `/ws` 와 webhook 에 **동일하게** 적용됩니다 — 켜면 webhook 페이로드에도 `extension` 키가 추가되므로, 수신측 파서가 strict 하면 미리 확인하고 켜야 합니다.

    ```json
    { "enable": true }
    ```

*   **`/config/systemevents` (POST)**: `include_system_events` 토글만 (`{"enable": true}`).

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
      "web_server_endpoint": "[YOUR_WEB_SERVER_URL_FOR_MESSAGE_FORWARDING]",
      "db_polling_rate": [DATABASE_POLLING_INTERVAL_IN_MILLISECONDS],
      "message_send_rate": [MESSAGE_SEND_INTERVAL_IN_MILLISECONDS],
      "bot_id": [YOUR_KAKAO_TALK_USER_ID],
      "broadcast_types": null,
      "include_system_events": false,
      "enable_extension": false
    }
    ```

    마지막 세 필드는 기본값과 같으면 생략됩니다(kotlinx.serialization 기본 동작).

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

#### WebSocket 엔드포인트

*   **`/ws`**: WebSocket 연결을 생성합니다.

##### 메시지 전달을 위한 API 레퍼런스

Iris가 카카오톡 데이터베이스에서 새 메시지를 감지하면 `/config/endpoint` API를 통해 구성된 `web_server_endpoint`로 `POST` 요청을 보냅니다.
또한, `/ws`를 통해 WebSocket이 연결되어 있다면, WebSocket으로도 아래의 이벤트를 전달합니다.

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

## License

This project contains a mix of MIT-licensed and GPL-licensed code.

* **Original Code:** All files in this repository, unless otherwise noted, are licensed under the **MIT License**. See `LICENSE-MIT` for details.
* **Third-Party Code:** The file previously located at `src/main/java/party/qwer/iris/NotificationPoller.kt` was partially derived from an external project and was licensed under the **GNU General Public License v3.0**. That file has been removed (the notification-listener code path no longer exists); `LICENSE-GPLv3.0` is retained for reference while it was present.

**Important:** With `NotificationPoller.kt` removed, current builds no longer compile in any GPL v3.0 source, so the combined-work clause above no longer binds them. If you re-add that file (or any GPL v3.0 derived source), the whole binary again falls under GPL v3.0.