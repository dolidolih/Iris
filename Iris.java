//SendMsg : ye-seola/go-kdb
//Kakaodecrypt : jiru/kakaodecrypt

import android.os.IBinder;
import android.os.ServiceManager;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.ComponentName;
import android.app.RemoteInput;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import android.util.Base64;
import java.io.File;
import java.io.FileReader;
import java.io.FileOutputStream;
import android.net.Uri;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.List;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;

import android.os.FileObserver;

class Iris {

    private static IBinder binder = ServiceManager.getService("activity");
    private static IActivityManager activityManager = IActivityManager.Stub.asInterface(binder);
    private static final String NOTI_REF;
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/config.json";
    private static final String DB_PATH = "/data/data/com.kakao.talk/databases";
    private static String WATCH_FILE;
    private static long lastModifiedTime = 0;
    private static DBFileObserver dbFileObserver; // FileObserver instance


    static {
        String notiRefValue = null;
        File prefsFile = new File("/data/data/com.kakao.talk/shared_prefs/KakaoTalk.hw.perferences.xml");
        BufferedReader prefsReader = null;
        try {
            prefsReader = new BufferedReader(new FileReader(prefsFile));
            String line;
            while ((line = prefsReader.readLine()) != null) {
                if (line.contains("<string name=\"NotificationReferer\">")) {
                    int start = line.indexOf(">") + 1;
                    int end = line.indexOf("</string>");
                    notiRefValue = line.substring(start, end);
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading preferences file: " + e.toString());
            notiRefValue = "default_noti_ref";
        } finally {
            if (prefsReader != null) {
                try {
                    prefsReader.close();
                } catch (IOException e) {
                    System.err.println("Error closing preferences file reader: " + e.toString());
                }
            }
        }

        if (notiRefValue == null || notiRefValue.equals("default_noti_ref")) {
            System.err.println("NotificationReferer not found in preferences file or error occurred, using default or potentially failed to load.");
        } else {
            System.out.println("NotificationReferer loaded: " + notiRefValue);
        }
        NOTI_REF = (notiRefValue != null) ? notiRefValue : "default_noti_ref";

        WATCH_FILE = DB_PATH + "/KakaoTalk.db-wal";
    }

    public static void main(String[] args) {
        Configurable.loadConfig(CONFIG_FILE_PATH);
        Iris.KakaoDB kakaoDb = new Iris.KakaoDB();
        Iris.ObserverHelper observerHelper = new Iris.ObserverHelper();
        HttpServer httpServer = new HttpServer(kakaoDb);

        dbFileObserver = new DBFileObserver(WATCH_FILE, FileObserver.MODIFY, kakaoDb, observerHelper);
        dbFileObserver.startWatching();
        System.out.println("FileObserver started watching: " + WATCH_FILE);


        httpServer.startServer();

        dbFileObserver.stopWatching();
        System.out.println("FileObserver stopped watching: " + WATCH_FILE);

        kakaoDb.closeConnection();
    }

    static class DBFileObserver extends FileObserver {
        private final Iris.KakaoDB kakaoDb;
        private final Iris.ObserverHelper observerHelper;
        private long lastModifiedTimeObserver = 0;

        public DBFileObserver(String path, int mask, Iris.KakaoDB kakaoDb, Iris.ObserverHelper observerHelper) {
            super(path, mask);
            this.kakaoDb = kakaoDb;
            this.observerHelper = observerHelper;
        }

        @Override
        public void onEvent(int event, String path) {
            if (event == FileObserver.MODIFY) {
                File watchFile = new File(WATCH_FILE);
                long currentModifiedTime = watchFile.lastModified();

                if (currentModifiedTime > lastModifiedTimeObserver) {
                    lastModifiedTimeObserver = currentModifiedTime;
                    System.out.println("Database file changed detected by FileObserver at: " + new java.util.Date(currentModifiedTime));
                    observerHelper.checkChange(kakaoDb, WATCH_FILE);
                }
            }
        }
    }


    static class Replier {
        private static IBinder binder = ServiceManager.getService("activity");
        private static IActivityManager activityManager = IActivityManager.Stub.asInterface(binder);
        private static final String NOTI_REF = Iris.NOTI_REF;

        private static void SendMessage(String notiRef, Long chatId, String msg) throws Exception {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"));

            intent.putExtra("noti_referer", notiRef);
            intent.putExtra("chat_id", chatId);
            intent.setAction("com.kakao.talk.notification.REPLY_MESSAGE");

            Bundle results = new Bundle();
            results.putCharSequence("reply_message", msg);

            RemoteInput remoteInput = new RemoteInput.Builder("reply_message").build();
            RemoteInput[] remoteInputs = new RemoteInput[]{remoteInput};
            RemoteInput.addResultsToIntent(remoteInputs, intent, results);
            activityManager.startService(
                    null,
                    intent,
                    intent.getType(),
                    false,
                    "com.android.shell",
                    null,
                    -2
            );
        }

        private static void SendPhoto(String room, String base64ImageDataString) throws Exception {
            byte[] decodedImage = android.util.Base64.decode(base64ImageDataString, android.util.Base64.DEFAULT);
            String timestamp = String.valueOf(System.currentTimeMillis());
            File picDir = new File("/sdcard/Android/data/com.kakao.talk/files");
            if (!picDir.exists()) {
                picDir.mkdirs();
            }
            File imageFile = new File(picDir, timestamp + ".png");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(imageFile);
                fos.write(decodedImage);
                fos.flush();
            } catch (IOException e) {
                System.err.println("Error saving image to file: " + e.toString());
                throw e;
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (IOException e) {
                        System.err.println("Error closing FileOutputStream: " + e.toString());
                    }
                }
            }

            Uri imageUri = Uri.fromFile(imageFile);

            Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
            mediaScanIntent.setData(imageUri);
            try {
                activityManager.broadcastIntent(
                        null,
                        mediaScanIntent,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        -1,
                        null,
                        false,
                        false,
                        -2
                );
                System.out.println("Media scanner broadcast intent sent for: " + imageUri.toString());
            } catch (Exception e) {
                System.err.println("Error broadcasting media scanner intent: " + e.toString());
                throw e;
            }

            Intent intent = new Intent();
            intent.setAction(Intent.ACTION_SENDTO);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, imageUri);
            intent.putExtra("key_id", Long.parseLong(room));
            intent.putExtra("key_type", 1);
            intent.putExtra("key_from_direct_share", true);
            intent.setPackage("com.kakao.talk");

            try {
                activityManager.startActivityAsUserWithFeature(
                        null,
                        "com.android.shell",
                        null,
                        intent,
                        intent.getType(),
                        null, null, 0, 0,
                        null,
                        null,
                        -2
                );
            } catch (Exception e) {
                System.err.println("Error starting activity for sending image: " + e.toString());
                throw e;
            }
        }
    }

    static class HttpServer {
        private final KakaoDB kakaoDb;
        private static final String NOTI_REF = Iris.NOTI_REF;


        public HttpServer(KakaoDB kakaoDb) {
            this.kakaoDb = kakaoDb;
        }

        public void startServer() {
            ServerSocket serverSocket = null;
            try {
                serverSocket = new ServerSocket(Configurable.getBotSocketPort());
                System.out.println("HTTP Server listening on port " + Configurable.getBotSocketPort());

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
                System.err.println("Could not listen on port " + Configurable.getBotSocketPort() + ": " + e.toString());
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
                if (requestLine == null || !requestLine.startsWith("POST ")) {
                    sendBadRequestResponse(out, "Invalid Request Line");
                    return;
                }

                String requestPath = requestLine.split(" ")[1];

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
            Long chatId = Long.parseLong(room);
            String data = obj.getString("data");

            if ("image".equals(type)) {
                Replier.SendPhoto(room, data);
            } else {
                Replier.SendMessage(NOTI_REF, chatId, data);
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
                long user_id = obj.optLong("user_id", Configurable.getBotId());
                String plain_text = Iris.KakaoDecrypt.decrypt(enc, b64_ciphertext, user_id);
                JSONObject responseJson = new JSONObject();
                responseJson.put("plain_text", plain_text);
                return responseJson.toString();

            } catch (Exception e) {
                return createErrorResponse("Decryption error: " + e.toString());
            }
        }

        private List<Object> jsonArrayToList(org.json.JSONArray jsonArray) throws JSONException {
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
                            long userId = rowJson.optLong("user_id", Configurable.getBotId());
                            if (rowJson.has("message")) {
                                String encryptedMessage = rowJson.getString("message");
                                if (encryptedMessage == null || encryptedMessage.isEmpty() || encryptedMessage.equals("{}")) {
                                    // pass as is without decryption
                                } else {
                                    rowJson.put("message", Iris.KakaoDecrypt.decrypt(enc, encryptedMessage, userId));
                                }
                            }
                            if (rowJson.has("attachment")) {
                                String encryptedAttachment = rowJson.getString("attachment");
                                if (encryptedAttachment == null || encryptedAttachment.isEmpty() || encryptedAttachment.equals("{}")) {
                                    // pass as is without decryption
                                } else {
                                    rowJson.put("attachment", Iris.KakaoDecrypt.decrypt(enc, encryptedAttachment, userId));
                                }
                            }
                        } catch (JSONException e) {
                            System.err.println("Error parsing 'v' for decryption: " + e.toString());
                        } catch (Exception e) {
                            System.err.println("Decryption error for message/attachment: " + e.toString());
                        }
                    }
                }

                long botId = Configurable.getBotId();
                int enc = rowJson.optInt("enc", 0);
                if (rowJson.has("nickname")) {
                    try {
                        String encryptedNickname = rowJson.getString("nickname");
                        rowJson.put("nickname", Iris.KakaoDecrypt.decrypt(enc, encryptedNickname, botId));
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
                                rowJson.put(urlKey, Iris.KakaoDecrypt.decrypt(enc, encryptedUrl, botId));
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

    static class Configurable {
        private static JSONObject config;
        private static long BOT_ID_CONFIG;
        private static String BOT_NAME_CONFIG;
        private static int BOT_HTTP_PORT_CONFIG;
        private static String WEB_SERVER_ENDPOINT_CONFIG;

        public static void loadConfig(String configFile) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            } catch (IOException e) {
                System.err.println("Error reading config.json: " + e.toString());
            }
            try {
                config = new JSONObject(sb.toString());
                BOT_ID_CONFIG = config.getLong("bot_id");
                BOT_NAME_CONFIG = config.getString("bot_name");
                BOT_HTTP_PORT_CONFIG = config.getInt("bot_http_port");
                WEB_SERVER_ENDPOINT_CONFIG = config.getString("web_server_endpoint");
            } catch (JSONException e) {
                System.err.println("JSON parsing error in config.json: " + e.toString());
            }
        }

        public static long getBotId() { return BOT_ID_CONFIG; }
        public static String getBotName() { return BOT_NAME_CONFIG; }
        public static int getBotSocketPort() { return BOT_HTTP_PORT_CONFIG; }
        public static String getWebServerEndpoint() { return WEB_SERVER_ENDPOINT_CONFIG; }
    }

    static class KakaoDecrypt extends Configurable {
        private static final java.util.Map<String, byte[]> keyCache = new java.util.HashMap<>();
        private static long BOT_USER_ID;

        static {
            BOT_USER_ID = Configurable.getBotId();
        }


        private static String incept(int n) {
            String[] dict1 = {"adrp.ldrsh.ldnp", "ldpsw", "umax", "stnp.rsubhn", "sqdmlsl", "uqrshl.csel", "sqshlu", "umin.usubl.umlsl", "cbnz.adds", "tbnz",
                    "usubl2", "stxr", "sbfx", "strh", "stxrb.adcs", "stxrh", "ands.urhadd", "subs", "sbcs", "fnmadd.ldxrb.saddl",
                    "stur", "ldrsb", "strb", "prfm", "ubfiz", "ldrsw.madd.msub.sturb.ldursb", "ldrb", "b.eq", "ldur.sbfiz", "extr",
                    "fmadd", "uqadd", "sshr.uzp1.sttrb", "umlsl2", "rsubhn2.ldrh.uqsub", "uqshl", "uabd", "ursra", "usubw", "uaddl2",
                    "b.gt", "b.lt", "sqshl", "bics", "smin.ubfx", "smlsl2", "uabdl2", "zip2.ssubw2", "ccmp", "sqdmlal",
                    "b.al", "smax.ldurh.uhsub", "fcvtxn2", "b.pl"};
            String[] dict2 = {"saddl", "urhadd", "ubfiz.sqdmlsl.tbnz.stnp", "smin", "strh", "ccmp", "usubl", "umlsl", "uzp1", "sbfx",
                    "b.eq", "zip2.prfm.strb", "msub", "b.pl", "csel", "stxrh.ldxrb", "uqrshl.ldrh", "cbnz", "ursra", "sshr.ubfx.ldur.ldnp",
                    "fcvtxn2", "usubl2", "uaddl2", "b.al", "ssubw2", "umax", "b.lt", "adrp.sturb", "extr", "uqshl",
                    "smax", "uqsub.sqshlu", "ands", "madd", "umin", "b.gt", "uabdl2", "ldrsb.ldpsw.rsubhn", "uqadd", "sttrb",
                    "stxr", "adds", "rsubhn2.umlsl2", "sbcs.fmadd", "usubw", "sqshl", "stur.ldrsh.smlsl2", "ldrsw", "fnmadd", "stxrb.sbfiz",
                    "adcs", "bics.ldrb", "l1ursb", "subs.uhsub", "ldurh", "uabd", "sqdmlal"};
            String word1 = dict1[n % dict1.length];
            String word2 = dict2[(n + 31) % dict2.length];
            return word1 + '.' + word2;
        }

        private static byte[] genSalt(long user_id, int encType) {
            if (user_id <= 0) {
                return new byte[16];
            }

            String[] prefixes = {"", "", "12", "24", "18", "30", "36", "12", "48", "7", "35", "40", "17", "23", "29",
                    "isabel", "kale", "sulli", "van", "merry", "kyle", "james", "maddux",
                    "tony", "hayden", "paul", "elijah", "dorothy", "sally", "bran",
                    incept(830819), "veil"};
            String saltStr;
            try {
                saltStr = prefixes[encType] + user_id;
                saltStr = saltStr.substring(0, Math.min(saltStr.length(), 16));
            } catch (ArrayIndexOutOfBoundsException e) {
                throw new IllegalArgumentException("Unsupported encoding type " + encType, e);
            }
            saltStr = saltStr + "\0".repeat(Math.max(0, 16 - saltStr.length()));
            return saltStr.getBytes(StandardCharsets.UTF_8);
        }

        private static void pkcs16adjust(byte[] a, int aOff, byte[] b) {
            int x = (b[b.length - 1] & 0xff) + (a[aOff + b.length - 1] & 0xff) + 1;
            a[aOff + b.length - 1] = (byte) (x % 256);
            x = x >> 8;
            for (int i = b.length - 2; i >= 0; i--) {
                x = x + (b[i] & 0xff) + (a[aOff + i] & 0xff);
                a[aOff + i] = (byte) (x % 256);
                x = x >> 8;
            }
        }

        private static byte[] deriveKey(byte[] passwordBytes, byte[] saltBytes, int iterations, int dkeySize) throws Exception {
            String password = new String(passwordBytes, StandardCharsets.US_ASCII) + "\0";
            byte[] passwordUTF16BE = password.getBytes(StandardCharsets.UTF_16BE);

            MessageDigest hasher = MessageDigest.getInstance("SHA-1");
            int digestSize = hasher.getDigestLength();
            int blockSize = 64;

            byte[] D = new byte[blockSize];
            for (int i = 0; i < blockSize; i++) {
                D[i] = 1;
            }
            byte[] S = new byte[blockSize * ((saltBytes.length + blockSize - 1) / blockSize)];
            for (int i = 0; i < S.length; i++) {
                S[i] = saltBytes[i % saltBytes.length];
            }
            byte[] P = new byte[blockSize * ((passwordUTF16BE.length + blockSize - 1) / blockSize)];
            for (int i = 0; i < P.length; i++) {
                P[i] = passwordUTF16BE[i % passwordUTF16BE.length];
            }

            byte[] I = new byte[S.length + P.length];
            System.arraycopy(S, 0, I, 0, S.length);
            System.arraycopy(P, 0, I, S.length, P.length);

            byte[] B = new byte[blockSize];
            int c = (dkeySize + digestSize - 1) / digestSize;

            byte[] dKey = new byte[dkeySize];
            for (int i = 1; i <= c; i++) {
                hasher = MessageDigest.getInstance("SHA-1");
                hasher.update(D);
                hasher.update(I);
                byte[] A = hasher.digest();

                for (int j = 1; j < iterations; j++) {
                    hasher = MessageDigest.getInstance("SHA-1");
                    hasher.update(A);
                    A = hasher.digest();
                }

                for (int j = 0; j < B.length; j++) {
                    B[j] = A[j % A.length];
                }

                for (int j = 0; j < I.length / blockSize; j++) {
                    pkcs16adjust(I, j * blockSize, B);
                }

                int start = (i - 1) * digestSize;
                if (i == c) {
                    System.arraycopy(A, 0, dKey, start, dkeySize - start);
                } else {
                    System.arraycopy(A, 0, dKey, start, A.length);
                }
            }

            return dKey;
        }

        public static String decrypt(int encType, String b64_ciphertext, long user_id) throws Exception {
            byte[] keyBytes = new byte[] {
                (byte)0x16, (byte)0x08, (byte)0x09, (byte)0x6f, (byte)0x02, (byte)0x17, (byte)0x2b, (byte)0x08,
                (byte)0x21, (byte)0x21, (byte)0x0a, (byte)0x10, (byte)0x03, (byte)0x03, (byte)0x07, (byte)0x06
            };
            byte[] ivBytes = new byte[] {
                (byte)0x0f, (byte)0x08, (byte)0x01, (byte)0x00, (byte)0x19, (byte)0x47, (byte)0x25, (byte)0xdc,
                (byte)0x15, (byte)0xf5, (byte)0x17, (byte)0xe0, (byte)0xe1, (byte)0x15, (byte)0x0c, (byte)0x35
            };

            byte[] salt = genSalt(user_id, encType);
            byte[] key;
            String saltStr = new String(salt, StandardCharsets.UTF_8);
            if (keyCache.containsKey(saltStr)) {
                key = keyCache.get(saltStr);
            } else {
                key = deriveKey(keyBytes, salt, 2, 32);
                keyCache.put(saltStr, key);
            }

            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);

            byte[] ciphertext = java.util.Base64.getDecoder().decode(b64_ciphertext);
            if (ciphertext.length == 0) {
                return b64_ciphertext;
            }
            byte[] padded;
            try {
                padded = cipher.doFinal(ciphertext);
            } catch (javax.crypto.BadPaddingException e) {
                System.err.println("BadPaddingException during decryption, possibly due to incorrect key or data. Returning original ciphertext.");
                return b64_ciphertext;
            }


            int paddingLength = padded[padded.length - 1];
            if (paddingLength <= 0 || paddingLength > cipher.getBlockSize()) {
                throw new IllegalArgumentException("Invalid padding length: " + paddingLength);
            }

            byte[] plaintextBytes = new byte[padded.length - paddingLength];
            System.arraycopy(padded, 0, plaintextBytes, 0, plaintextBytes.length);


            return new String(plaintextBytes, StandardCharsets.UTF_8);

        }

        public static String encrypt(int encType, String plaintext, long user_id) throws Exception {
            byte[] keyBytes = new byte[] {
                (byte)0x16, (byte)0x08, (byte)0x09, (byte)0x6f, (byte)0x02, (byte)0x17, (byte)0x2b, (byte)0x08,
                (byte)0x21, (byte)0x21, (byte)0x0a, (byte)0x10, (byte)0x03, (byte)0x03, (byte)0x07, (byte)0x06
            };
            byte[] ivBytes = new byte[] {
                (byte)0x0f, (byte)0x08, (byte)0x01, (byte)0x00, (byte)0x19, (byte)0x47, (byte)0x25, (byte)0xdc,
                (byte)0x15, (byte)0x5, (byte)0x17, (byte)0xe0, (byte)0xe1, (byte)0x15, (byte)0x0c, (byte)0x35
            };

            byte[] salt = genSalt(user_id, encType);
            byte[] key;
            String saltStr = new String(salt, StandardCharsets.UTF_8);
            if (keyCache.containsKey(saltStr)) {
                key = keyCache.get(saltStr);
            } else {
                key = deriveKey(keyBytes, salt, 2, 32);
                keyCache.put(saltStr, key);
            }
            SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivParameterSpec = new IvParameterSpec(ivBytes);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String b64_ciphertext = java.util.Base64.getEncoder().encodeToString(ciphertext);
            return b64_ciphertext;
        }
    }

    static class KakaoDB extends Iris.Configurable {
        private SQLiteDatabase db = null;
        private long BOT_ID;
        private String BOT_NAME;


        public KakaoDB() {
            BOT_ID = Configurable.getBotId();
            BOT_NAME = Configurable.getBotName();

            try {
                db = SQLiteDatabase.openDatabase(DB_PATH + "/KakaoTalk.db", null, SQLiteDatabase.OPEN_READWRITE);
                db.execSQL("ATTACH DATABASE '" + DB_PATH + "/KakaoTalk2.db' AS db2");
            } catch (SQLiteException e) {
                System.err.println("SQLiteException: " + e.getMessage());
                System.err.println("You don't have a permission to access KakaoTalk Database.");
                System.exit(1);
            }
        }

        public List<String> getColumnInfo(String table) {
            List<String> cols = new ArrayList<>();
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT * FROM " + table + " LIMIT 1", null);
                if (cursor != null && cursor.moveToFirst()) {
                    String[] columnNames = cursor.getColumnNames();
                    for (String columnName : columnNames) {
                        cols.add(columnName);
                    }
                }
            } catch (SQLiteException e) {
                System.err.println("Error in getColumnInfo for table " + table + ": " + e.getMessage());
                return new ArrayList<>();
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return cols;
        }


        public List<String> getTableInfo() {
            List<String> tables = new ArrayList<>();
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT name FROM sqlite_schema WHERE type='table'", null);
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0));
                    }
                }
            } catch (SQLiteException e) {
                System.err.println("Error in getTableInfo: " + e.getMessage());
                return new ArrayList<>();
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return tables;
        }

        public String getNameOfUserId(long userId) {
            String dec_row_name = null;
            Cursor cursor = null;
            try {
                String sql;
                String[] stringUserId = {Long.toString(userId)};
                if (checkNewDb()) {
                    sql = "WITH info AS (SELECT ? AS user_id) " +
                            "SELECT COALESCE(open_chat_member.nickname, friends.name) AS name, " +
                            "COALESCE(open_chat_member.enc, friends.enc) AS enc " +
                            "FROM info " +
                            "LEFT JOIN db2.open_chat_member ON open_chat_member.user_id = info.user_id " +
                            "LEFT JOIN db2.friends ON friends.id = info.user_id;";
                } else {
                    sql = "SELECT name, enc FROM db2.friends WHERE id = ?";
                }
                cursor = db.rawQuery(sql, stringUserId);

                if (cursor != null && cursor.moveToNext()) {
                    String row_name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
                    String enc = cursor.getString(cursor.getColumnIndexOrThrow("enc"));
                    dec_row_name = Iris.KakaoDecrypt.decrypt(Integer.parseInt(enc), row_name, KakaoDecrypt.BOT_USER_ID);
                }

            } catch (SQLiteException e) {
                System.err.println("Error in getNameOfUserId: " + e.getMessage());
                return "";
            } catch (Exception e) {
                System.err.println("Decryption error in getNameOfUserId: " + e.getMessage());
                return "";
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return dec_row_name;
        }


        public String[] getUserInfo(long chatId, long userId) {
            String sender;
            if (userId == BOT_ID) {
                sender = BOT_NAME;
            } else {
                sender = getNameOfUserId(userId);
            }

            String room = sender;
            Cursor cursor = null;
            try {
                String sql = "SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?)";
                String[] selectionArgs = {String.valueOf(chatId)};
                cursor = db.rawQuery(sql, selectionArgs);

                if (cursor != null && cursor.moveToNext()) {
                    room = cursor.getString(0);
                }
            } catch (SQLiteException e) {
                System.err.println("Error in getUserInfo: " + e.getMessage());
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return new String[]{room, sender};
        }

        public Map<String, Object> getRowFromLogId(long logId) {
            Map<String, Object> rowMap = new HashMap<>();
            Cursor cursor = null;
            try {
                String sql = "SELECT * FROM chat_logs WHERE id = ?";
                String[] selectionArgs = {String.valueOf(logId)};
                cursor = db.rawQuery(sql, selectionArgs);
                if (cursor != null && cursor.moveToNext()) {
                    String[] columnNames = cursor.getColumnNames();
                    for (String columnName : columnNames) {
                        int columnIndex = cursor.getColumnIndexOrThrow(columnName);
                        rowMap.put(columnName, cursor.getString(columnIndex));
                    }
                }
            } catch (SQLiteException e) {
                System.err.println("Error in getRowFromLogId: " + e.getMessage());
                return null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return rowMap;
        }


        public Map<String, Object> logToDict(long logId) {
            Map<String, Object> dict = new HashMap<>();
            Cursor cursor = null;
            try {
                String sql = "SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 1";
                cursor = db.rawQuery(sql, null);
                if (cursor != null && cursor.moveToNext()) {
                    String[] columnNames = cursor.getColumnNames();
                    for (String columnName : columnNames) {
                        int columnIndex = cursor.getColumnIndexOrThrow(columnName);
                        dict.put(columnName, cursor.getString(columnIndex));
                    }
                }
            } catch (SQLiteException e) {
                System.err.println("Error in logToDict (getLastLog): " + e.getMessage());
                return null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return dict;
        }


        public boolean checkNewDb() {
            boolean isNewDb = false;
            Cursor cursor = null;
            try {
                cursor = db.rawQuery("SELECT name FROM db2.sqlite_master WHERE type='table' AND name='open_chat_member'", null);
                isNewDb = cursor.getCount() > 0;
            } catch (SQLiteException e) {
                System.err.println("Error in checkNewDb: " + e.getMessage());
                return false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            return isNewDb;
        }

        public void closeConnection() {
            if (db != null && db.isOpen()) {
                db.close();
                System.out.println("Database connection closed.");
            }
        }

        public SQLiteDatabase getConnection() {
            return this.db;
        }
    }

    static class ObserverHelper extends Iris.Configurable {
        private long lastLogId = 0;
        private long BOT_ID;
        private String BOT_NAME;
        private int BOT_HTTP_PORT;
        private String WEB_SERVER_ENDPOINT;

        public ObserverHelper() {
            BOT_ID = Configurable.getBotId();
            BOT_NAME = Configurable.getBotName();
            BOT_HTTP_PORT = Configurable.getBotSocketPort();
            WEB_SERVER_ENDPOINT = Configurable.getWebServerEndpoint();
        }

        private String makePostData(String decMsg, String room, String sender, JSONObject js) throws JSONException {
            JSONObject data = new JSONObject();
            data.put("msg", decMsg);
            data.put("room", room);
            data.put("sender", sender);
            data.put("json", js);
            return data.toString();
        }

        public void checkChange(Iris.KakaoDB db, String watchFile) {
            if (lastLogId == 0) {
                Map<String, Object> lastLog = db.logToDict(0);
                if (lastLog != null && lastLog.containsKey("_id")) {
                    lastLogId = Long.parseLong((String)lastLog.get("_id"));
                } else {
                    lastLogId = 0;
                }
                System.out.println("Initial lastLogId: " + lastLogId);
                return;
            }

            String sql = "select * from chat_logs where _id > ? order by _id asc";
            Cursor res = null;
            try {
                String[] selectionArgs = {String.valueOf(lastLogId)};
                res = db.getConnection().rawQuery(sql, selectionArgs);
                List<String> description = new ArrayList<>();
                if (res.getColumnNames() != null) {
                    for (String columnName : res.getColumnNames()) {
                        description.add(columnName);
                    }
                }


                while (res != null && res.moveToNext()) {
                    long currentLogId = res.getLong(res.getColumnIndexOrThrow("_id"));
                    if (currentLogId > lastLogId) {
                        lastLogId = currentLogId;
                        JSONObject logJson = new JSONObject();
                        for (String columnName : description) {
                            try {
                                logJson.put(columnName, res.getString(res.getColumnIndexOrThrow(columnName)));
                            } catch (JSONException e) {
                                System.err.println("JSONException while adding log data to JSON object: " + e.getMessage());
                                continue;
                            }
                        }


                        String enc_msg = res.getString(res.getColumnIndexOrThrow("message"));
                        String enc_attachment = res.getString(res.getColumnIndexOrThrow("attachment"));
                        long user_id = res.getLong(res.getColumnIndexOrThrow("user_id"));
                        int encType = 0;
                        try {
                            JSONObject v = new JSONObject(res.getString(res.getColumnIndexOrThrow("v")));
                            encType = v.getInt("enc");
                        } catch (JSONException e) {
                            System.err.println("Error parsing 'v' JSON for encType: " + e.getMessage());
                            encType = 0;
                        }


                        String dec_msg;
                        String dec_attachment = null;
                        try {
                            if (enc_msg == null || enc_msg.isEmpty() || enc_msg.equals("{}")){
                                dec_msg = "{}}";
                            } else {
                                dec_msg = Iris.KakaoDecrypt.decrypt(encType, enc_msg, user_id);
                                logJson.put("message", dec_msg);
                            }
                            if (enc_attachment == null || enc_attachment.isEmpty() || enc_attachment.equals("{}") || res.getColumnIndexOrThrow("attachment") == -1){
                                dec_attachment = "{}";
                            } else {
                                dec_attachment = Iris.KakaoDecrypt.decrypt(encType, enc_attachment, user_id);
                            }
                            logJson.put("attachment", dec_attachment);
                        } catch (Exception e) {
                            System.err.println("Decryption error for logId " + currentLogId + ": " + e.toString());
                            dec_msg = enc_msg;
                            dec_attachment = enc_attachment;
                        }

                        long chat_id = res.getLong(res.getColumnIndexOrThrow("chat_id"));
                        String[] userInfo = db.getUserInfo(chat_id, user_id);
                        String room = userInfo[0];
                        String sender = userInfo[1];
                        if (room.equals(BOT_NAME)) {
                            room = sender;
                        }
                        String postData;
                        try {
                            postData = makePostData(dec_msg, room, sender, logJson);
                            sendPostRequest(WEB_SERVER_ENDPOINT, postData);
                        } catch (JSONException e) {
                            System.err.println("JSON error creating post data: " + e.getMessage());
                        }
                        System.out.println("New message from " + sender + " in " + room + ": " + dec_msg);
                    }
                }
            } catch (SQLiteException e) {
                System.err.println("SQL error in checkChange: " + e.getMessage());
            } finally {
                if (res != null) {
                    res.close();
                }
            }
        }

        private void sendPostRequest(String urlStr, String jsonData) {
            System.out.println("Sending HTTP POST request to: " + urlStr);
            System.out.println("JSON Data being sent: " + jsonData);
            try {
                URL url = new URL(urlStr);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                con.setRequestProperty("Accept", "application/json");
                con.setDoOutput(true);

                try (OutputStream os = con.getOutputStream()) {
                    byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = con.getResponseCode();
                System.out.println("HTTP Response Code: " + responseCode);

                try (BufferedReader br = new BufferedReader(
                        new java.io.InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    String responseBody = response.toString();
                    System.out.println("HTTP Response Body: " + responseBody);
                } catch (IOException e) {
                    System.err.println("Error reading HTTP response body: " + e.getMessage());
                }
                con.disconnect();

            } catch (IOException e) {
                System.err.println("IO error sending POST request: " + e.getMessage());
            }
        }
    }
}