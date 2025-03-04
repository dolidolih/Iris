package party.qwer.iris;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import java.util.function.Function;
import java.io.InputStream;

public class HttpServer {
    private final KakaoDB kakaoDb;
    private final DBObserver dbObserver;
    private final ObserverHelper observerHelper;
    private static final String NOTI_REF = Main.NOTI_REF;
    private volatile boolean isRunning = false;

    private final Map<String, Function<JSONObject, String>> postEndpointHandlers = new HashMap<>();
    private final Map<String, Function<String, String>> getEndpointHandlers = new HashMap<>();

    public HttpServer(KakaoDB kakaoDb, DBObserver dbObserver, ObserverHelper observerHelper) {
        this.kakaoDb = kakaoDb;
        this.dbObserver = dbObserver;
        this.observerHelper = observerHelper;
        initializeEndpointHandlers();
    }

    private void initializeEndpointHandlers() {
        postEndpointHandlers.put("/reply", this::handleReplyFunction);
        postEndpointHandlers.put("/query", this::handleQueryFunction);
        postEndpointHandlers.put("/decrypt", this::handleDecryptFunction);
        postEndpointHandlers.put("/config/endpoint", this::handlePostConfigEndpoint);
        postEndpointHandlers.put("/config/dbrate", this::handlePostConfigDbRate);
        postEndpointHandlers.put("/config/sendrate", this::handlePostConfigSendRate);

        getEndpointHandlers.put("/config/info", this::handleConfigInfo);
        getEndpointHandlers.put("/config", this::handleConfigPage);
        getEndpointHandlers.put("/config/dbstatus", this::handleConfigDbStatus);
    }


    public void startServer() {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(Configurable.getInstance().getBotSocketPort());
            System.out.println("HTTP Server listening on port " + Configurable.getInstance().getBotSocketPort());
            isRunning = true;

            while (isRunning) {
                Socket clientSocket;
                try {
                    clientSocket = serverSocket.accept();
                    System.out.println("Client connected: " + clientSocket.getInetAddress());

                    Socket finalClientSocket = clientSocket;
                    new Thread(() -> handleClient(finalClientSocket)).start();

                } catch (IOException e) {
                    if (!isRunning) {
                        System.out.println("Server socket accept interrupted during shutdown.");
                        break;
                    }
                    System.err.println("IO Exception in server accept: " + e);
                }
            }
            System.out.println("HTTP Server stopped.");
        } catch (IOException e) {
            System.err.println("Could not listen on port " + Configurable.getInstance().getBotSocketPort() + ": " + e);
            System.exit(1);
        } finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e);
                }
            }
        }
    }

    public void stopServer() {
        isRunning = false;
        try (Socket dummySocket = new Socket("localhost", Configurable.getInstance().getBotSocketPort())) {
            System.out.println("Sent dummy connection to unblock server accept.");
        } catch (IOException e) {
            System.err.println("Error sending dummy connection: " + e);
        }
    }

    private void handleClient(Socket clientSocket) {
        BufferedReader in = null;
        PrintWriter out = null;
        try {
            in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            out = new PrintWriter(clientSocket.getOutputStream(), true);

            String requestLine = in.readLine();
            if (requestLine == null) {
                sendBadRequestResponse(out, "Invalid Request");
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                sendBadRequestResponse(out, "Invalid Request Line");
                return;
            }
            String method = requestParts[0];
            String requestPath = requestParts[1];


            if ("POST".equals(method)) {
                String contentType = null;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-type: ")) {
                        contentType = line.substring("Content-Type: ".length()).trim();
                    }
                }

                if (!"application/json".equalsIgnoreCase(contentType)) {
                    sendBadRequestResponse(out, "Content-Type must be application/json for POST requests");
                    return;
                }

                StringBuilder requestBody = new StringBuilder();
                while (in.ready()) {
                    requestBody.append((char) in.read());
                }
                String requestBodyString = requestBody.toString();

                String responseString = handleHttpRequestLogic(requestPath, requestBodyString);
                sendOkResponse(out, responseString, "application/json");
            } else if ("GET".equals(method)) {
                String responseString = handleHttpGetRequest(requestPath);
                if (responseString.startsWith("HTTP/1.1")) {
                    out.print(responseString);
                    out.flush();
                } else {
                    String contentType = "text/html";
                    if (requestPath.endsWith(".json")) {
                        contentType = "application/json";
                    }
                    sendOkResponse(out, responseString, contentType);
                }
            } else {
                sendBadRequestResponse(out, "Method not supported: " + method);
            }


        } catch (IOException e) {
            System.err.println("IO Exception in client connection: " + e);
            sendInternalServerErrorResponse(out, "IO Error processing request");
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
                if (clientSocket != null) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.err.println("Error closing socket resources: " + e);
            }
            System.out.println("Client connection handled and closed.");
        }
    }

    private String handleHttpGetRequest(String requestPath) {
        String normalizedPath = normalizePath(requestPath); // Normalize the path

        Function<String, String> handler = getEndpointHandlers.get(normalizedPath.split("\\?")[0]);
        if (handler != null) {
            return handler.apply(requestPath); // Keep original requestPath for handler to use query params if needed
        } else {
            if ("/config".equals(normalizedPath)) { // Use startsWith for /config
                return handleConfigPage(requestPath);
            } else if ("/config/info".equals(normalizedPath)) { // Use equals with normalized path
                return handleConfigInfo(requestPath);
            } else if ("/config/dbstatus".equals(normalizedPath)) { // Use equals with normalized path
                return handleConfigDbStatus(requestPath);
            }
            return createErrorResponse("Invalid GET endpoint.");
        }
    }

    // Helper function to normalize path by removing trailing slash
    private String normalizePath(String path) {
        if (path != null && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }


    private String handleConfigPage(String requestPath) {
        String html = ConfigPageDocumentProvider.getDocument(Configurable.getInstance());
        return html;
    }


    private String handleConfigDbStatus(String requestPath) {
        return getDbStatusInfo();
    }


    private String getDbStatusInfo() {
        JSONObject statusJson = new JSONObject();
        try {
            boolean isObserving = dbObserver.isObserving();
            statusJson.put("isObserving", isObserving);
            if (isObserving) {
                statusJson.put("statusMessage", "Observing database");
            } else {
                statusJson.put("statusMessage", "Not observing database");
            }
            List<Map<String, Object>> lastLogsList = observerHelper.getLastChatLogs();
            JSONArray lastLogsJsonArray = new JSONArray(lastLogsList);
            statusJson.put("lastLogs", lastLogsJsonArray);
        } catch (JSONException e) {
            return createErrorResponse("Failed to serialize DB status to JSON: " + e);
        }
        return createObjectSuccessResponse(statusJson);
    }


    private String handlePostConfigEndpoint(JSONObject requestJson) {
        String endpoint = requestJson.optString("endpoint");
        if (endpoint != null && !endpoint.isEmpty()) {
            Configurable.getInstance().setWebServerEndpoint(endpoint);
            return createSuccessResponse("Endpoint updated to: " + endpoint);
        } else {
            return createErrorResponse("Endpoint parameter missing or empty in request body.");
        }
    }

    private String handlePostConfigDbRate(JSONObject requestJson) {
        String rateStr = requestJson.optString("rate");
        if (rateStr != null && !rateStr.isEmpty()) {
            try {
                long rate = Long.parseLong(rateStr);
                Configurable.getInstance().setDbPollingRate(rate);
                return createSuccessResponse("DB polling rate updated to: " + rate);
            } catch (NumberFormatException e) {
                return createErrorResponse("Invalid rate format in request body.");
            }
        } else {
            return createErrorResponse("Rate parameter missing or empty in request body.");
        }
    }

    private String handlePostConfigSendRate(JSONObject requestJson) {
        String rateStr = requestJson.optString("rate");
        if (rateStr != null && !rateStr.isEmpty()) {
            try {
                long rate = Long.parseLong(rateStr);
                Configurable.getInstance().setMessageSendRate(rate);
                Replier.messageSendRate = rate;
                return createSuccessResponse("Message send rate updated to: " + rate);
            } catch (NumberFormatException e) {
                return createErrorResponse("Invalid rate format in request body.");
            }
        } else {
            return createErrorResponse("Rate parameter missing or empty in request body.");
        }
    }


    private String handleConfigInfo(String requestPath) {
        return getConfigInfo();
    }

    private String getConfigInfo() {
        Configurable config = Configurable.getInstance();
        JSONObject configJson = new JSONObject();
        try {
            configJson.put("bot_name", config.getBotName());
            configJson.put("bot_http_port", config.getBotSocketPort());
            configJson.put("web_server_endpoint", config.getWebServerEndpoint());
            configJson.put("db_polling_rate", config.getDbPollingRate());
            configJson.put("message_send_rate", config.getMessageSendRate());
            configJson.put("bot_id", config.getBotId());
        } catch (JSONException e) {
            return createErrorResponse("Failed to serialize config to JSON: " + e);
        }
        return createObjectSuccessResponse(configJson);
    }


    private String getQueryParam(String requestPath, String paramName) {
        try {
            if (requestPath.contains("?")) {
                String queryString = requestPath.substring(requestPath.indexOf("?") + 1);
                String[] params = queryString.split("&");
                for (String param : params) {
                    String[] keyValuePair = param.split("=");
                    if (keyValuePair.length == 2 && keyValuePair[0].equals(paramName)) {
                        return URLDecoder.decode(keyValuePair[1], StandardCharsets.UTF_8.name());
                    }
                }
            }
        } catch (UnsupportedEncodingException e) {
            System.err.println("Error decoding URL parameter: " + e);
        }
        return null;
    }


    private String handleHttpRequestLogic(String requestPath, String requestBody) {
        Function<JSONObject, String> handler = postEndpointHandlers.get(requestPath);
        if (handler != null) {
            try {
                JSONObject requestJson = new JSONObject(requestBody);
                return handler.apply(requestJson);
            } catch (JSONException e) {
                System.err.println("JSON parsing error: " + e);
                return createErrorResponse("Invalid JSON request: " + e);
            } catch (Exception e) {
                System.err.println("Error processing request: " + e);
                return createErrorResponse("Error processing request: " + e);
            }
        } else {
            return createErrorResponse("Invalid POST endpoint.");
        }
    }

    private String handleReplyFunction(JSONObject obj) {
        try {
            String type = obj.optString("type", "text");
            String room = obj.getString("room");
            String data = obj.getString("data");

            if ("image".equals(type)) {
                Replier.SendPhoto(Long.parseLong(room), data);
            } else if ("image_multiple".equals(type)) {
                JSONArray dataArray = obj.getJSONArray("data");
                List<String> imageBase64List = new ArrayList<>();
                for (int i = 0; i < dataArray.length(); i++) {
                    imageBase64List.add(dataArray.getString(i));
                }
                Replier.SendMultiplePhotos(Long.parseLong(room), imageBase64List);
            }
            else {
                Replier.SendMessage(NOTI_REF, Long.parseLong(room), data);
            }
            return createSuccessResponse();
        } catch (Exception e) {
            return createErrorResponse("Error in reply function: " + e);
        }
    }


    private String handleQueryFunction(JSONObject obj) {
        try {
            if (obj.has("queries")) {
                JSONArray queriesArray = obj.getJSONArray("queries");
                List<List<Map<String, Object>>> bulkResults = new ArrayList<>();
                for (int i = 0; i < queriesArray.length(); i++) {
                    JSONObject queryObj = queriesArray.getJSONObject(i);
                    String query = queryObj.getString("query");
                    List<Object> bindValues = jsonArrayToList(queryObj.getJSONArray("bind"));
                    String[] bindArgs = new String[bindValues.size()];
                    for (int j = 0; j < bindValues.size(); j++) {
                        bindArgs[j] = String.valueOf(bindValues.get(j));
                    }
                    List<Map<String, Object>> queryResult = executeQuery(query, bindArgs);
                    bulkResults.add(queryResult);
                }
                return createQuerySuccessResponse(bulkResults);

            } else {
                String query = obj.getString("query");
                JSONArray bindJsonArray = obj.optJSONArray("bind");
                List<Object> bindValues = (bindJsonArray != null) ? jsonArrayToList(bindJsonArray) : Collections.emptyList();
                String[] bindArgs = new String[bindValues.size()];
                for (int i = 0; i < bindValues.size(); i++) {
                    bindArgs[i] = String.valueOf(bindValues.get(i));
                }
                List<Map<String, Object>> queryResult = executeQuery(query, bindArgs);
                return createQuerySuccessResponse(queryResult);
            }

        } catch (JSONException | ClassCastException e) {
            return createErrorResponse("Invalid 'query' or 'queries' field for query function. " + e);
        } catch (SQLiteException e) {
            return createErrorResponse("Database query error: " + e);
        } catch (Exception e) {
            return createErrorResponse("Error executing query: " + e);
        }
    }

    private String handleDecryptFunction(JSONObject obj) {
        try {
            int enc = obj.getInt("enc");
            String b64_ciphertext = obj.getString("b64_ciphertext");
            long user_id = obj.optLong("user_id", Configurable.getInstance().getBotId());
            String plain_text = KakaoDecrypt.decrypt(enc, b64_ciphertext, user_id);
            JSONObject responseJson = new JSONObject();
            responseJson.put("plain_text", plain_text);
            return responseJson.toString();

        } catch (Exception e) {
            return createErrorResponse("Decryption error: " + e);
        }
    }

    private List<Object> jsonArrayToList(JSONArray jsonArray) throws JSONException {
        List<Object> list = new ArrayList<>();
        if (jsonArray != null) {
            for (int i = 0; i < jsonArray.length(); i++) {
                list.add(jsonArray.get(i));
            }
        }
        return list;
    }


    private List<Map<String, Object>> executeQuery(String sqlQuery, String[] bindArgs) {
        List<Map<String, Object>> resultList = new ArrayList<>();
        try (Cursor cursor = kakaoDb.getConnection().rawQuery(sqlQuery, bindArgs)) {
            if (cursor != null) {
                String[] columnNames = cursor.getColumnNames();
                while (cursor.moveToNext()) {
                    Map<String, Object> row = new HashMap<>();
                    for (String columnName : columnNames) {
                        int columnIndex = cursor.getColumnIndexOrThrow(columnName);
                        row.put(columnName, cursor.getString(columnIndex));
                    }
                    resultList.add(row);
                }
            }
        }
        return resultList;
    }

    private String createSuccessResponse() {
        try {
            JSONObject responseJson = new JSONObject();
            responseJson.put("success", true);
            return responseJson.toString();
        } catch (JSONException e) {
            return "{\"success\":false, \"error\":\"Failed to create success JSON response.\"}";
        }
    }

    private String createSuccessResponse(String message) {
        try {
            JSONObject responseJson = new JSONObject();
            responseJson.put("success", true);
            responseJson.put("message", message);
            return responseJson.toString();
        } catch (JSONException e) {
            return "{\"success\":false, \"error\":\"Failed to create success JSON response with message.\"}";
        }
    }

    private String createObjectSuccessResponse(Object message) {
        try {
            JSONObject responseJson = new JSONObject();
            responseJson.put("success", true);
            responseJson.put("message", message);
            return responseJson.toString();
        } catch (JSONException e) {
            return "{\"success\":false, \"error\":\"Failed to create success JSON response with message.\"}";
        }
    }

    private String createQuerySuccessResponse(Object queryResult) {
        try {
            JSONObject responseJson = new JSONObject();
            responseJson.put("success", true);
            JSONArray dataArray = new JSONArray();

            if (queryResult instanceof List) {
                List<?> resultList = (List<?>) queryResult;
                if (!resultList.isEmpty() && resultList.get(0) instanceof List) {
                    List<List<Map<String, Object>>> bulkResults = (List<List<Map<String, Object>>>) queryResult;
                    for (List<Map<String, Object>> singleQueryResult : bulkResults) {
                        JSONArray singleQueryDataArray = new JSONArray();
                        for (Map<String, Object> rowMap : singleQueryResult) {
                            JSONObject rowJson = new JSONObject(rowMap);
                            processDecryptionForResponse(rowJson);
                            singleQueryDataArray.put(rowJson);
                        }
                        dataArray.put(singleQueryDataArray);
                    }
                } else {
                    List<Map<String, Object>> singleQueryResult = (List<Map<String, Object>>) queryResult;
                    for (Map<String, Object> rowMap : singleQueryResult) {
                        JSONObject rowJson = new JSONObject(rowMap);
                        processDecryptionForResponse(rowJson);
                        dataArray.put(rowJson);
                    }
                }
            }
            responseJson.put("data", dataArray);
            return responseJson.toString();
        } catch (JSONException e) {
            return "{\"success\":false, \"error\":\"Failed to create query success JSON response.\"}";
        }
    }

    private void processDecryptionForResponse(JSONObject rowJson) {
        try {
            if (rowJson.has("message") || rowJson.has("attachment")) {
                String vStr = rowJson.optString("v");
                if (!vStr.isEmpty()) {
                    try {
                        JSONObject vJson = new JSONObject(vStr);
                        int enc = vJson.optInt("enc", 0);
                        long userId = rowJson.optLong("user_id", Configurable.getInstance().getBotId());
                        if (rowJson.has("message")) {
                            String encryptedMessage = rowJson.getString("message");
                            if (encryptedMessage.isEmpty() || encryptedMessage.equals("{}")) {

                            } else {
                                rowJson.put("message", KakaoDecrypt.decrypt(enc, encryptedMessage, userId));
                            }
                        }
                        if (rowJson.has("attachment")) {
                            String encryptedAttachment = rowJson.getString("attachment");
                            if (encryptedAttachment.isEmpty() || encryptedAttachment.equals("{}")) {

                            } else {
                                rowJson.put("attachment", KakaoDecrypt.decrypt(enc, encryptedAttachment, userId));
                            }
                        }
                    } catch (JSONException e) {
                        System.err.println("Error parsing 'v' for decryption: " + e);
                    } catch (Exception e) {
                        System.err.println("Decryption error for message/attachment: " + e);
                    }
                }
            }

            long botId = Configurable.getInstance().getBotId();
            int enc = rowJson.optInt("enc", 0);
            if (rowJson.has("nickname")) {
                try {
                    String encryptedNickname = rowJson.getString("nickname");
                    rowJson.put("nickname", KakaoDecrypt.decrypt(enc, encryptedNickname, botId));
                } catch (Exception e) {
                    System.err.println("Decryption error for nickname: " + e);
                }
            }
            String[] urlKeys = {"profile_image_url", "full_profile_image_url", "original_profile_image_url"};
            for (String urlKey : urlKeys) {
                if (rowJson.has(urlKey)) {
                    String encryptedUrl = rowJson.optString(urlKey, null);
                    if (!encryptedUrl.isEmpty()) {
                        try {
                            rowJson.put(urlKey, KakaoDecrypt.decrypt(enc, encryptedUrl, botId));
                        } catch (Exception e) {
                            System.err.println("Decryption error for " + urlKey + ": " + e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("JSON processing error during decryption: " + e);
        }
    }


    private String createErrorResponse(String errorMessage) {
        try {
            JSONObject errorJson = new JSONObject();
            errorJson.put("success", false);
            errorJson.put("error", errorMessage);
            return errorJson.toString();
        } catch (JSONException e) {
            return "{\"success\":false, \"error\":\"" + errorMessage + ". Also failed to create error JSON response.\"}";
        }
    }

    private void sendOkResponse(PrintWriter out, String responseBody, String contentType) {
        sendHttpResponse(out, "HTTP/1.1 200 OK", contentType, responseBody);
    }

    private void sendBadRequestResponse(PrintWriter out, String errorMessage) {
        sendHttpResponse(out, "HTTP/1.1 400 Bad Request", "text/plain", errorMessage);
    }

    private void sendInternalServerErrorResponse(PrintWriter out, String errorMessage) {
        sendHttpResponse(out, "HTTP/1.1 500 Internal Server Error", "text/plain", errorMessage);
    }


    private void sendHttpResponse(PrintWriter out, String statusLine, String contentType, String responseBody) {
        out.println(statusLine);
        out.println("Content-Type: " + contentType + "; charset=UTF-8");
        out.println("Connection: close");
        out.println();
        out.println(responseBody);
    }
}