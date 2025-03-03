package party.qwer.Iris;

import org.json.JSONObject;
import org.json.JSONException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.OutputStreamWriter;

public class Configurable {
    private static Configurable instance;
    private static JSONObject config;
    private static long BOT_ID_CONFIG;
    private static String BOT_NAME_CONFIG;
    private static int BOT_HTTP_PORT_CONFIG;
    private static String WEB_SERVER_ENDPOINT_CONFIG;
    private static long DB_POLLING_RATE_CONFIG = 100;
    private static long MESSAGE_SEND_RATE_CONFIG = 50;
    private static final String CONFIG_FILE_PATH = "/data/local/tmp/config.json";


    private Configurable() {}

    public static synchronized Configurable getInstance() {
        if (instance == null) {
            instance = new Configurable();
        }
        return instance;
    }

    public void loadConfig(String configFile) {
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
            BOT_NAME_CONFIG = config.getString("bot_name");
            BOT_HTTP_PORT_CONFIG = config.getInt("bot_http_port");
            WEB_SERVER_ENDPOINT_CONFIG = config.getString("web_server_endpoint");
            if (config.has("db_polling_rate")) {
                DB_POLLING_RATE_CONFIG = config.getLong("db_polling_rate");
            }
            if (config.has("message_send_rate")) {
                MESSAGE_SEND_RATE_CONFIG = config.getLong("message_send_rate");
            }
        } catch (JSONException e) {
            System.err.println("JSON parsing error in config.json: " + e.toString());
        }
    }


    private synchronized void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE_PATH);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            writer.print(config.toString(4));
        } catch (IOException e) {
            System.err.println("Error writing config to file: " + e.toString());
        } catch (JSONException e) {
            System.err.println("JSON error while saving config: " + e.toString());
        }
    }

    public long getBotId() { return KakaoDecrypt.BOT_USER_ID; }
    public void setBotId(long botId) { KakaoDecrypt.BOT_USER_ID = botId; }
    public String getBotName() { return BOT_NAME_CONFIG; }
    public int getBotSocketPort() { return BOT_HTTP_PORT_CONFIG; }
    public String getWebServerEndpoint() { return WEB_SERVER_ENDPOINT_CONFIG; }
    public long getDbPollingRate() { return DB_POLLING_RATE_CONFIG; }
    public long getMessageSendRate() { return MESSAGE_SEND_RATE_CONFIG; }

    public synchronized void setWebServerEndpoint(String endpoint) {
        WEB_SERVER_ENDPOINT_CONFIG = endpoint;
        try {
            config.put("web_server_endpoint", endpoint);
        } catch (JSONException e) {
            System.err.println("JSON error updating web_server_endpoint in config: " + e.toString());
        }
        saveConfig();
        System.out.println("WebServerEndpoint updated to: " + WEB_SERVER_ENDPOINT_CONFIG);
    }

    public synchronized void setDbPollingRate(long rate) {
        DB_POLLING_RATE_CONFIG = rate;
        try {
            config.put("db_polling_rate", rate);
        } catch (JSONException e) {
            System.err.println("JSON error updating db_polling_rate in config: " + e.toString());
        }
        saveConfig();
        System.out.println("DbPollingRate updated to: " + DB_POLLING_RATE_CONFIG);
    }

    public synchronized void setMessageSendRate(long rate) {
        MESSAGE_SEND_RATE_CONFIG = rate;
        try {
            config.put("message_send_rate", rate);
        } catch (JSONException e) {
            System.err.println("JSON error updating message_send_rate in config: " + e.toString());
        }
        saveConfig();
        System.out.println("MessageSendRate updated to: " + MESSAGE_SEND_RATE_CONFIG);
    }
}