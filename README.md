# SendMsgDB - An Android native db observer / message broker / reply sender for Kakaotalk bot

This project allows you to automate interactions with KakaoTalk, extract data from its database, and control it remotely via an HTTP server. It's built in Java and designed to run on Android devices, leveraging system services and direct database access.

**Project Status:** Work in Progress (WIP)

## Features

*   **Send Messages:**
    *   Send text messages to KakaoTalk chat rooms directly from your application or via HTTP requests.
    *   Send photos to KakaoTalk chat rooms (base64 encoded images supported via HTTP).
*   **Decrypt KakaoTalk Messages:**
    *   Decrypt encrypted messages from the KakaoTalk database.
    *   Provides an HTTP endpoint to decrypt messages on demand.
*   **KakaoTalk Database Query:**
    *   Execute arbitrary SQL queries against the KakaoTalk database via HTTP requests.
    *   Retrieve data such as chat logs, user information, and more (depending on database schema).
*   **Real-time Message Monitoring:**
    *   Monitors the KakaoTalk database for new messages.
    *   Automatically decrypts and forwards new messages to a configurable web server via HTTP POST requests.
*   **HTTP API for Remote Control:**
    *   Exposes a simple HTTP API to send messages, query the database, and decrypt messages.
    *   Control your KakaoTalk instance programmatically from other applications or scripts.
*   **Configuration via JSON:**
    *   Configuration settings (like bot ID, bot name, server port, and web server endpoint) are loaded from a `config.json` file.

**Work in Progress (WIP):**

*   Decrypt all values when sending a database record to a web server (currently only message content is decrypted).
*   Implement socket-based remote SQL query functionality (currently using HTTP).

## Getting Started

### Prerequisites

*   **Android Device:** This application is designed to run on an Android device where KakaoTalk is installed and you have the necessary permissions to access system services and application data.
*   **Root Access (Potentially):** Accessing KakaoTalk's database and some system services might require root access on your Android device, depending on your Android version and security settings.
*   **Android SDK:** You will need the Android SDK set up to compile and deploy this application.
*   **`config.json`:**  A `config.json` file needs to be placed in `/data/local/tmp/` on your Android device.

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
      "bot_id": [YOUR_KAKAO_TALK_USER_ID],
      "bot_name": "[YOUR_BOT_NAME]",
      "bot_http_port": [PORT_FOR_HTTP_SERVER],
      "web_server_endpoint": "[YOUR_WEB_SERVER_URL_FOR_MESSAGE_FORWARDING]"
    }
    ```
    *   `bot_id`: Your KakaoTalk user ID (numerical).
    *   `bot_name`: A name for your bot (used for identification).
    *   `bot_http_port`: The port number for the HTTP server to listen on (e.g., `8080`).
    *   `web_server_endpoint`: The URL of your web server that will receive new KakaoTalk messages (e.g., `http://your-server.com/messages`).

3.  **Compile the Java code:**
    Use an Android development environment (like Android Studio or command-line tools) to compile the `SendMsgDB.java` file into an APK. You might need to adjust the build configuration depending on your environment.

4.  **Deploy to Android Device:**
    Install the compiled APK on your Android device. You might need to enable "Install from Unknown Sources" in your device settings.

5.  **Run the Application:**
    Execute the `SendMsgDB` application on your Android device. It will start the HTTP server and begin monitoring the KakaoTalk database.

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

*   **`/query`**: Execute an SQL query on the KakaoTalk database.

    **Request Body (JSON):**

    ```json
    {
      "query": "[SQL_QUERY]" // SQL query string
    }
    ```

    **Example:**

    ```bash
    curl -X POST -H "Content-Type: application/json" -d '{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5"}' http://[YOUR_DEVICE_IP]:[bot_http_port]/query
    ```

    **Response (JSON):**

    ```json
    {
      "success": true,
      "data": [
        // Array of query results, each result is a map of column name to value
        {
          "_id": "...",
          "chat_id": "...",
          "user_id": "...",
          "message": "..."
        },
        // ... more results ...
      ]
    }
    ```

*   **`/decrypt`**: Decrypt a KakaoTalk message.

    **Request Body (JSON):**

    ```json
    {
      "enc": [ENCRYPTION_TYPE], // Encryption type (integer from database)
      "b64_ciphertext": "[BASE64_ENCODED_CIPHERTEXT]", // Base64 encoded encrypted message
      "user_id": [USER_ID] // User ID (long integer)
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

### Important Notes

*   **Security:** This project involves accessing and decrypting private data from the KakaoTalk database. Ensure you understand the security implications and use this tool responsibly.
*   **Permissions:**  Granting necessary permissions to the application on your Android device is crucial for it to function correctly.
*   **Database Schema Changes:** KakaoTalk database schema can change with updates. This project might require updates to remain compatible with newer versions of KakaoTalk.
*   **Terms of Service:** Be aware of KakaoTalk's Terms of Service. Automating interactions might be against their terms, and usage is at your own risk.
*   **Error Handling:** Check the application logs and HTTP response codes for error information if something is not working as expected.

## Credits

*   **SendMsg & Initial Concept:** Based on the work of `ye-seola/go-kdb`.
*   **KakaoTalk Decryption Logic:** Inspired by and utilizes decryption methods from `jiru/kakaodecrypt`.

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.