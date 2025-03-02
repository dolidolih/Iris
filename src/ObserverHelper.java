import android.os.IBinder;
import android.os.ServiceManager;
import android.app.IActivityManager;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.HashMap;
import java.util.Map;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.net.HttpURLConnection;
import java.io.OutputStream;
import java.net.URL;

public class ObserverHelper {
    private long lastLogId = 0;
    private String BOT_NAME;
    private final Configurable config = Configurable.getInstance();


    public ObserverHelper() {
        BOT_NAME = config.getBotName();
    }

    private String makePostData(String decMsg, String room, String sender, JSONObject js) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("msg", decMsg);
        data.put("room", room);
        data.put("sender", sender);
        data.put("json", js);
        return data.toString();
    }

    public void checkChange(KakaoDB db) {
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

        String countSql = "select count(*) from chat_logs where _id > ?";
        Cursor countRes = null;
        int newLogCount = 0;
        try {
            String[] selectionCountArgs = {String.valueOf(lastLogId)};
            countRes = db.getConnection().rawQuery(countSql, selectionCountArgs);
            if (countRes != null && countRes.moveToNext()) {
                newLogCount = countRes.getInt(0);
            }
        } catch (SQLiteException e) {
            System.err.println("SQL error in checkChange (count query): " + e.getMessage());
        } finally {
            if (countRes != null) {
                countRes.close();
            }
        }

        if (newLogCount > 0) {
            System.out.println("Detected " + newLogCount + " new log(s). Processing...");
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
                        int encType = 0;
                        String origin = "";
                        try {
                            JSONObject v = new JSONObject(res.getString(res.getColumnIndexOrThrow("v")));
                            encType = v.getInt("enc");
                            origin = v.getString("origin");
                        } catch (JSONException e) {
                            System.err.println("Error parsing 'v' JSON for encType: " + e.getMessage());
                            encType = 0;
                        }

                        if (origin.equals("SYNCMSG") || origin.equals("MCHATLOGS")){
                            continue;
                        }


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



                        String dec_msg;
                        String dec_attachment = null;
                        try {
                            if (enc_msg == null || enc_msg.isEmpty() || enc_msg.equals("{}")){
                                dec_msg = "{}}";
                            } else {
                                dec_msg = KakaoDecrypt.decrypt(encType, enc_msg, user_id);
                                logJson.put("message", dec_msg);
                            }
                            if (enc_attachment == null || enc_attachment.isEmpty() || enc_attachment.equals("{}") || res.getColumnIndexOrThrow("attachment") == -1){
                                dec_attachment = "{}";
                            } else {
                                dec_attachment = KakaoDecrypt.decrypt(encType, enc_attachment, user_id);
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
                            sendPostRequest(postData); // Call sendPostRequest without endpoint parameter
                        } catch (JSONException e) {
                            System.err.println("JSON error creating post data: " + e.getMessage());
                        }
                        System.out.println("New message from " + sender + " in " + room + ": " + dec_msg);

                    }
                }
            } catch (SQLiteException e) {
                System.err.println("SQL error in checkChange (data query): " + e.getMessage());
            } finally {
                if (res != null) {
                    res.close();
                }
            }
        }
    }

    // Modified sendPostRequest to fetch endpoint each time
    private void sendPostRequest(String jsonData) {
        String urlStr = Configurable.getInstance().getWebServerEndpoint(); // Get endpoint here
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