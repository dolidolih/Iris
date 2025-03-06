package party.qwer.iris;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KakaoDB {
    private SQLiteDatabase db = null;
    private static final String DB_PATH = "/data/data/com.kakao.talk/databases";


    public KakaoDB() {
        try {
            db = SQLiteDatabase.openDatabase(DB_PATH + "/KakaoTalk.db", null, SQLiteDatabase.OPEN_READWRITE);
            db.execSQL("ATTACH DATABASE '" + DB_PATH + "/KakaoTalk2.db' AS db2");
            getBotUserIdFromDB();
        } catch (SQLiteException e) {
            System.err.println("SQLiteException: " + e.getMessage());
            System.err.println("You don't have a permission to access KakaoTalk Database.");
            System.exit(1);
        }
    }

    public void getBotUserIdFromDB() {
        long botUserId;
        Cursor cursor = null;
        try {
            String sql = "SELECT user_id FROM chat_logs WHERE v LIKE '%\"isMine\":true%' ORDER BY _id DESC LIMIT 1;";
            cursor = db.rawQuery(sql, null);
            if (cursor != null && cursor.moveToFirst()) {
                botUserId = cursor.getLong(0);
                System.out.println("Bot user_id is detected: " + botUserId);
                Configurable.getInstance().setBotId(botUserId);
            } else {
                System.err.println("Warning: Bot user_id not found in chat_logs with isMine:true. Decryption might not work correctly.");
            }
        } catch (SQLiteException e) {
            System.err.println("SQLiteException while fetching bot user_id: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }


    public List<String> getColumnInfo(String table) {
        List<String> cols = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " LIMIT 1", null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String[] columnNames = cursor.getColumnNames();
                Collections.addAll(cols, columnNames);
            }
        } catch (SQLiteException e) {
            System.err.println("Error in getColumnInfo for table " + table + ": " + e.getMessage());
            return new ArrayList<>();
        }
        return cols;
    }


    public List<String> getTableInfo() {
        List<String> tables = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT name FROM sqlite_schema WHERE type='table'", null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0));
                }
            }
        } catch (SQLiteException e) {
            System.err.println("Error in getTableInfo: " + e.getMessage());
            return new ArrayList<>();
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
                dec_row_name = KakaoDecrypt.decrypt(Integer.parseInt(enc), row_name, Configurable.getInstance().getBotId());
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
        if (userId == Configurable.getInstance().getBotId()) {
            sender = Configurable.getInstance().getBotName();
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
        boolean isNewDb;
        try (Cursor cursor = db.rawQuery("SELECT name FROM db2.sqlite_master WHERE type='table' AND name='open_chat_member'", null)) {
            isNewDb = cursor.getCount() > 0;
        } catch (SQLiteException e) {
            System.err.println("Error in checkNewDb: " + e.getMessage());
            return false;
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

    public List<Map<String, Object>> executeQuery(String sqlQuery, String[] bindArgs) {
        List<Map<String, Object>> resultList = new ArrayList<>();
        try (Cursor cursor = getConnection().rawQuery(sqlQuery, bindArgs)) {
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
}