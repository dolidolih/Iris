# Iris - An Android native db observer / message broker / reply sender for Kakaotalk bot

This project allows you to automate interactions with KakaoTalk, extract data from its database, and control it remotely via an HTTP server. It's built in Java and designed to run on Android devices, leveraging system services and direct database access.

**Project Status:** Work in Progress (WIP)

## Features

*   **Send Messages:**
    *   Send text messages to KakaoTalk chat rooms directly from your application or via HTTP requests.
    *   Send photos to KakaoTalk chat rooms (base64 encoded images supported via HTTP).
    *   Messages are sent via a queue system to manage send rate, respecting configured delays.
*   **Decrypt KakaoTalk Messages:**
    *   Decrypt encrypted messages from the KakaoTalk database.
    *   Provides an HTTP endpoint to decrypt messages on demand.
*   **KakaoTalk Database Query:**
    *   Execute arbitrary SQL queries against the KakaoTalk database via HTTP requests.
    *   Supports single and **bulk SQL queries** in one request.
    *   Supports **parameterized queries using bindings** to prevent SQL injection and improve efficiency.
    *   Retrieve data such as chat logs, user information, and more (depending on database schema).
*   **Real-time Message Monitoring:**
    *   Monitors the KakaoTalk database for new messages.
    *   Automatically decrypts and forwards new messages to a configurable web server via HTTP POST requests.
    *   **Sends detailed message information including decrypted content, sender, and room name to the web server.**
*   **HTTP API for Remote Control:**
    *   Exposes a simple HTTP API to send messages, query the database, and decrypt messages.
    *   Control your KakaoTalk instance programmatically from other applications or scripts.
*   **Configuration via JSON:**
    *   Configuration settings (like bot ID, bot name, server port, web server endpoint, **database polling rate, and message send rate**) are loaded from a `config.json` file.
*   **Decryption via HTTP and Query Responses:**
    *   Decrypt all relevant values when sending a database record to a web server and in `/query` responses, including message content, attachments, nicknames, and profile URLs.
*   **Automatic Image Cleanup:**
    *   Periodically deletes old images (older than 24 hours) from the temporary image storage directory to save space.

## Getting Started

### Prerequisites

*   **Android Device:** This application is designed to run on an Android device where KakaoTalk is installed and you have the necessary permissions to access system services and application data.
*   **Root Access:** Accessing KakaoTalk's database and some system services might require root access on your Android device, depending on your Android version and security settings.
*   **`config.json`:**  A `config.json` file needs to be placed in `/data/local/tmp/` on your Android device.
*   **HTTP Server:** An HTTP server is required to interact with Iris.

### Setup

1.  **Clone the repository:** (If you are distributing the code)
    ```bash
    git clone [repository-url]
    cd [repository-directory]
    ```

2.  **Configure `config.json`:**
    Create a `config.json` file and place it in `/data/local/tmp/` on your Android device. The file should have the following structure:

    ```json
    {
      "bot_name": "[YOUR_BOT_NAME]",
      "bot_http_port": [PORT_FOR_HTTP_SERVER],
      "web_server_endpoint": "[YOUR_WEB_SERVER_URL_FOR_MESSAGE_FORWARDING],
      "db_polling_rate": [DATABASE_POLLING_INTERVAL_IN_MILLISECONDS], // Optional, default 100ms
      "message_send_rate": [MESSAGE_SEND_INTERVAL_IN_MILLISECONDS]    // Optional, default 50ms
    }
    ```
    *   `bot_name`: A name for your bot (used for identification).
    *   `bot_http_port`: The port number for the HTTP server to listen on (e.g., `3000`).
    *   `web_server_endpoint`: The URL of your web server that will receive new KakaoTalk messages (e.g., `http://172.17.0.2:5000/db for IrisPy`).
    *   `db_polling_rate` (optional):  The interval in milliseconds to check for database changes (e.g., `200`). Lower values mean more frequent checks, potentially increasing CPU usage.
    *   `message_send_rate` (optional): The minimum interval in milliseconds between sending KakaoTalk messages (e.g., `100`). Use this to control the message sending rate and avoid flooding.

3.  **Copy files:**
    Use adb to copy files into your Android environment.
    ```bash
    adb push Iris.dex /data/local/tmp
    adb push config.json /data/local/tmp
    ```

4.  **Run the dex file:**
    If you run it as a service, (Iris will be run in background although you pressed CTRL+C)
    ```bash
    adb shell "su root sh -c 'CLASSPATH=/data/local/tmp/Iris.dex /system/bin/app_process / Iris' &"
    ```
    If you want to run it and watch the logs, (Iris will stops when you press CTRL+C)
    ```bash
    adb shell
    su
    sh -c 'CLASSPATH=/data/local/tmp/Iris.dex /system/bin/app_process / Iris'
    ```


### Usage

Once the application is running on your Android device, you can interact with it using HTTP requests.

#### HTTP API Endpoints

The HTTP server listens on the port specified in your `config.json` (`bot_http_port`).  All requests should be sent as `POST` requests with `Content-Type: application/json`.

*   **`/reply`**: Send a message or photo to a KakaoTalk chat room.
    
    **Request Body (JSON):**

    ```json
    {
      "type": "text",  // or "image"
      "room": "[CHAT_ROOM_ID]", // Chat room ID (as a string)
      "data": "[MESSAGE_TEXT]"  // For text messages
                                // Base64 encoded image string for image messages
    }
    ```

    **Example (text message):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "text", "room": "1234567890", "data": "Hello from SendMsgDB!"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    ```

    **Example (image message):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"type": "image", "room": "1234567890", "data": "[BASE64_ENCODED_IMAGE_DATA]"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/reply
    ```

*   **`/query`**: Execute an SQL query on the KakaoTalk database. This method automatically decrypts encrypted data fields in the response.
    > If `message` or `attachment` are queried with `user_id` and `enc`, it will return decrypted value.  
    > If `nickname`, `profile_image_url`, `full_profile_image_url`, or `original_profile_image_url` are queried with `enc`, it will also return the plain text.

    **Request Body (JSON):**

    ```json
    // for single request
    {
      "query": "[SQL_QUERY]",  // SQL query string
      "bind": ["[BINDING_VALUE_1]", "[BINDING_VALUE_2]", ...] // Optional bindings for the query
    }

    // for multiple requests (bulk query)
    {
      "queries":[
        {
          "query": "[SQL_QUERY_1]",
          "bind": ["[BINDING_VALUE_1]", "[BINDING_VALUE_2]", ...] // Optional bindings for query 1
        },
        {
          "query": "[SQL_QUERY_2]",
          "bind": [] // Optional bindings for query 2
        },
        // ... more queries ...
      ]
    }
    ```

    **Example (single query with binding):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs WHERE user_id = ? ORDER BY _id DESC LIMIT 5", "bind": ["1234567890"]}' http://[YOUR_DEVICE_IP]:[bot_http_port]/query
    ```

    **Example (bulk query):**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"queries": [{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5", "bind": []}, {"query": "SELECT name FROM db2.friends LIMIT 2", "bind": []}]}' http://[YOUR_DEVICE_IP]:[bot_http_port]/query
    ```

    **Response (JSON):**

    ```json
    {
      "success": true,
      "data": [
        // For single query, or the first query in bulk queries
        [
          // Array of query results, each result is a map of column name to value
          {
            "_id": "...",
            "chat_id": "...",
            "user_id": "...",
            "message": "...", // Decrypted message content
            // ... other columns ...
          },
          // ... more results ...
        ],
        // For bulk queries, subsequent query results will be in the following array elements.
        [
          // Results for the second query in bulk
          {
            "name": "...", // Decrypted name
          },
          // ... more results ...
        ],
        // ... more query result arrays if bulk query was used ...
      ]
    }
    ```

*   **`/decrypt`**: Decrypt a KakaoTalk message.

    **Request Body (JSON):**

    ```json
    {
      "enc": [ENCRYPTION_TYPE], // Encryption type (integer from database)
      "b64_ciphertext": "[BASE64_ENCODED_CIPHERTEXT]", // Base64 encoded encrypted message
      "user_id": [USER_ID] // User ID (long integer), optional, defaults to bot_id from config
    }
    ```

    **Example:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"enc": 0, "b64_ciphertext": "[ENCRYPTED_MESSAGE_BASE64]", "user_id": 1234567890}' http://[YOUR_DEVICE_IP]:[bot_http_port]/decrypt
    ```

    **Response (JSON):**

    ```json
    {
      "plain_text": "[DECRYPTED_MESSAGE_TEXT]"
    }
    ```

##### Configuration API Endpoints (GET)

The following endpoints allow you to dynamically reconfigure Iris without restarting the application. These are accessed using `GET` requests.

*   **`/config/endpoint?endpoint=[YOUR_WEB_SERVER_URL]`**: Update the web server endpoint for message forwarding.

    **Example:**

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/config/endpoint?endpoint=http://192.168.1.100:5000/new_messages
    ```

*   **`/config/dbrate?rate=[DATABASE_POLLING_INTERVAL_IN_MILLISECONDS]`**: Update the database polling rate. Adjusting this value changes how frequently Iris checks for new messages in the database. Lower values increase CPU usage but may provide more immediate message detection.

    **Example:**

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/config/dbrate?rate=300
    ```

*   **`/config/sendrate?rate=[MESSAGE_SEND_INTERVAL_IN_MILLISECONDS]`**: Update the message send rate. This controls the minimum interval between sending messages to KakaoTalk, helping to manage send frequency.

    **Example:**

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/config/sendrate?rate=200
    ```

*   **`/config/info`**: Retrieve the current configuration as a JSON response. This is useful for verifying the currently active settings.

    **Example:**

    ```bash
    curl http://[YOUR_DEVICE_IP]:[bot_http_port]/config/info
    ```

    **Response (JSON):**

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

#### API Reference for Message Forwarding

When Iris detects a new message in the KakaoTalk database, it sends a `POST` request to the `web_server_endpoint` configured in `config.json`. The request body is a JSON object with the following structure:

```json
{
  "msg": "[DECRYPTED_MESSAGE_CONTENT]", // Decrypted message text
  "room": "[CHAT_ROOM_NAME]",          // Name of the chat room or sender name for 1:1 chats
  "sender": "[SENDER_NAME]",          // Name of the message sender
  "json": {                            // Raw database row from 'chat_logs' table as JSON
    "_id": "...",
    "chat_id": "...",
    "user_id": "...",
    "message": "[DECRYPTED_MESSAGE_CONTENT]", // Decrypted message content, same as "msg" field
    "attachment": "[DECRYPTED_ATTACHMENT_INFO]", // Decrypted attachment information if available
    "v": "{\"enc\": 0, ...}",           // Original 'v' column value (JSON format)
    // ... other columns from chat_logs table ...
  }
}
```

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.
