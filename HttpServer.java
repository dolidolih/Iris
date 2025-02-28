import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.net.HttpURLConnection;
import java.net.URL;

public class HttpServer {
    private final KakaoDB kakaoDb;
    private static final String NOTI_REF = Iris.NOTI_REF;


    public HttpServer(KakaoDB kakaoDb) {
        this.kakaoDb = kakaoDb;
    }

    public void startServer() {
        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(Configurable.getInstance().getBotSocketPort());
            System.out.println("HTTP Server listening on port " + Configurable.getInstance().getBotSocketPort());

            while (true) {
                Socket clientSocket = null;
                try {
                    clientSocket = serverSocket.accept();
                    System.out.println("Client connected: " + clientSocket.getInetAddress());

                    Socket finalClientSocket = clientSocket;
                    new Thread(() -> handleClient(finalClientSocket)).start();

                } catch (IOException e) {
                    System.err.println("IO Exception in server accept: " + e.toString());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not listen on port " + Configurable.getInstance().getBotSocketPort() + ": " + e.toString());
            System.exit(1);
        } finally {
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.toString());
                }
            }
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
                    sendBadRequestResponse(out, "Content-Type must be application/json");
                    return;
                }

                StringBuilder requestBody = new StringBuilder();
                while (in.ready()) {
                    requestBody.append((char) in.read());
                }
                String requestBodyString = requestBody.toString();

                String responseString = handleHttpRequestLogic(requestPath, requestBodyString);
                sendOkResponse(out, responseString);
            } else if ("GET".equals(method)) {
                String responseString = handleHttpGetRequest(requestPath);
                sendOkResponse(out, responseString);
            } else {
                sendBadRequestResponse(out, "Method not supported: " + method);
            }


        } catch (IOException e) {
            System.err.println("IO Exception in client connection: " + e.toString());
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
                System.err.println("Error closing socket resources: " + e.toString());
            }
            System.out.println("Client connection handled and closed.");
        }
    }

    private String handleHttpGetRequest(String requestPath) {
        if (requestPath.startsWith("/config/endpoint")) {
            String endpoint = getQueryParam(requestPath, "endpoint");
            if (endpoint != null) {
                Configurable.getInstance().setWebServerEndpoint(endpoint);
                return createSuccessResponse("Endpoint updated to: " + endpoint);
            } else {
                return createErrorResponse("Endpoint parameter missing.");
            }
        } else if (requestPath.startsWith("/config/dbrate")) {
            String rateStr = getQueryParam(requestPath, "rate");
            if (rateStr != null) {
                try {
                    long rate = Long.parseLong(rateStr);
                    Configurable.getInstance().setDbPollingRate(rate);
                    return createSuccessResponse("DB polling rate updated to: " + rate);
                } catch (NumberFormatException e) {
                    return createErrorResponse("Invalid rate format.");
                }
            } else {
                return createErrorResponse("Rate parameter missing.");
            }
        } else if (requestPath.startsWith("/config/sendrate")) {
            String rateStr = getQueryParam(requestPath, "rate");
            if (rateStr != null) {
                try {
                    long rate = Long.parseLong(rateStr);
                    Configurable.getInstance().setMessageSendRate(rate);
                    Replier.messageSendRate = rate; // Accessing public static messageSendRate
                    return createSuccessResponse("Message send rate updated to: " + rate);
                } catch (NumberFormatException e) {
                    return createErrorResponse("Invalid rate format.");
                }
            } else {
                return createErrorResponse("Rate parameter missing.");
            }
        } else if (requestPath.startsWith("/config/info")) {
            return getConfigInfo();
        }
        return createErrorResponse("Invalid config endpoint.");
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
            return createErrorResponse("Failed to serialize config to JSON: " + e.toString());
        }
        return createSuccessResponse(configJson.toString());
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
            System.err.println("Error decoding URL parameter: " + e.toString());
        }
        return null;
    }


    private String handleHttpRequestLogic(String requestPath, String requestBody) {
        try {
            JSONObject obj = new JSONObject(requestBody);

            if ("/reply".equals(requestPath)) {
                return handleReplyFunction(obj);
            } else if ("/query".equals(requestPath)) {
                return handleQueryFunction(obj);
            } else if ("/decrypt".equals(requestPath)) {
                return handleDecryptFunction(obj);
            }
            else {
                return createErrorResponse("Invalid endpoint.");
            }

        } catch (JSONException e) {
            System.err.println("JSON parsing error: " + e.toString());
            return createErrorResponse("Invalid JSON request: " + e.toString());
        } catch (Exception e) {
            System.err.println("Error processing request: " + e.toString());
            return createErrorResponse("Error processing request: " + e.toString());
        }
    }

    private String handleReplyFunction(JSONObject obj) throws Exception {
        String type = obj.optString("type");
        String room = obj.getString("room");
        String data = obj.getString("data");

        if ("image".equals(type)) {
            Replier.SendPhoto(Long.parseLong(room), data);
        } else {
            Replier.SendMessage(NOTI_REF, Long.parseLong(room), data);
        }
        return createSuccessResponse();
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
                List<Object> bindValues = jsonArrayToList(obj.getJSONArray("bind"));
                String[] bindArgs = new String[bindValues.size()];
                for (int i = 0; i < bindValues.size(); i++) {
                    bindArgs[i] = String.valueOf(bindValues.get(i));
                }
                List<Map<String, Object>> queryResult = executeQuery(query, bindArgs);
                return createQuerySuccessResponse(queryResult);
            }

        } catch (JSONException | ClassCastException e) {
            return createErrorResponse("Invalid 'query' or 'queries' field for query function. " + e.toString());
        } catch (SQLiteException e) {
            return createErrorResponse("Database query error: " + e.toString());
        } catch (Exception e) {
            return createErrorResponse("Error executing query: " + e.toString());
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
            return createErrorResponse("Decryption error: " + e.toString());
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
        Cursor cursor = null;
        try {
            cursor = kakaoDb.getConnection().rawQuery(sqlQuery, bindArgs);
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
        } finally {
            if (cursor != null) {
                cursor.close();
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
                if (vStr != null && !vStr.isEmpty()) {
                    try {
                        JSONObject vJson = new JSONObject(vStr);
                        int enc = vJson.optInt("enc", 0);
                        long userId = rowJson.optLong("user_id", Configurable.getInstance().getBotId());
                        if (rowJson.has("message")) {
                            String encryptedMessage = rowJson.getString("message");
                            if (encryptedMessage == null || encryptedMessage.isEmpty() || encryptedMessage.equals("{}")) {

                            } else {
                                rowJson.put("message", KakaoDecrypt.decrypt(enc, encryptedMessage, userId));
                            }
                        }
                        if (rowJson.has("attachment")) {
                            String encryptedAttachment = rowJson.getString("attachment");
                            if (encryptedAttachment == null || encryptedAttachment.isEmpty() || encryptedAttachment.equals("{}")) {

                            } else {
                                rowJson.put("attachment", KakaoDecrypt.decrypt(enc, encryptedAttachment, userId));
                            }
                        }
                    } catch (JSONException e) {
                        System.err.println("Error parsing 'v' for decryption: " + e.toString());
                    } catch (Exception e) {
                        System.err.println("Decryption error for message/attachment: " + e.toString());
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
                    System.err.println("Decryption error for nickname: " + e.toString());
                }
            }
            String[] urlKeys = {"profile_image_url", "full_profile_image_url", "original_profile_image_url"};
            for (String urlKey : urlKeys) {
                if (rowJson.has(urlKey)) {
                    String encryptedUrl = rowJson.optString(urlKey, null);
                    if (encryptedUrl != null) {
                        try {
                            rowJson.put(urlKey, KakaoDecrypt.decrypt(enc, encryptedUrl, botId));
                        } catch (Exception e) {
                            System.err.println("Decryption error for " + urlKey + ": " + e.toString());
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("JSON processing error during decryption: " + e.toString());
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

    private void sendOkResponse(PrintWriter out, String responseBody) {
        sendHttpResponse(out, "HTTP/1.1 200 OK", "application/json", responseBody);
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