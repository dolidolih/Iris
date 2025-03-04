package party.qwer.iris;

import org.json.JSONObject;
import org.json.JSONException;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.IOException;
import java.io.FileReader;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.io.OutputStreamWriter;

public class Configurable {
    private static Configurable instance;
    private static JSONObject config;
    private static long BOT_ID_CONFIG = 0L; // default value, will be loaded from DB later or remain 0 if not found
    private static String BOT_NAME_CONFIG = "Iris"; // default bot name
    private static int BOT_HTTP_PORT_CONFIG = 3000; // default port
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

    public void loadConfig(String configFile) { // configFile is CONFIG_FILE_PATH
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            config = new JSONObject(sb.toString());
        } catch (IOException e) {
            System.out.println("config.json not found, creating default config.");
            config = createDefaultConfig();
            saveConfig(); // save default config to file
        } catch (JSONException e) {
            System.err.println("JSON parsing error in config.json, creating default config: " + e);
            config = createDefaultConfig();
            saveConfig(); // save default config to file
        }
        try {
            BOT_NAME_CONFIG = config.optString("bot_name", BOT_NAME_CONFIG); // Use default if not found
            BOT_HTTP_PORT_CONFIG = config.optInt("bot_http_port", BOT_HTTP_PORT_CONFIG); // Use default if not found
            WEB_SERVER_ENDPOINT_CONFIG = config.optString("web_server_endpoint", "http://172.17.0.1:5000/db"); // Default endpoint
            if (config.has("db_polling_rate")) {
                DB_POLLING_RATE_CONFIG = config.getLong("db_polling_rate");
            }
            if (config.has("message_send_rate")) {
                MESSAGE_SEND_RATE_CONFIG = config.getLong("message_send_rate");
            }

        } catch (JSONException e) {
            System.err.println("Error reading config values from JSON, using defaults: " + e);
        }
    }

    private JSONObject createDefaultConfig() {
        JSONObject defaultConfig = new JSONObject();
        try {
            defaultConfig.put("bot_name", BOT_NAME_CONFIG);
            defaultConfig.put("bot_http_port", BOT_HTTP_PORT_CONFIG);
            defaultConfig.put("web_server_endpoint", "http://172.17.0.1:5000/db"); // Default endpoint
            defaultConfig.put("db_polling_rate", DB_POLLING_RATE_CONFIG);
            defaultConfig.put("message_send_rate", MESSAGE_SEND_RATE_CONFIG);
        } catch (JSONException e) {
            e.printStackTrace(); // Log if default config creation fails, though unlikely
        }
        return defaultConfig;
    }


    private synchronized void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE_PATH);
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8))) {
            writer.print(config.toString(4));
        } catch (IOException e) {
            System.err.println("Error writing config to file: " + e);
        } catch (JSONException e) {
            System.err.println("JSON error while saving config: " + e);
        }
    }

    public long getBotId() { return BOT_ID_CONFIG; }
    public void setBotId(long botId) { BOT_ID_CONFIG = botId; }
    public String getBotName() { return BOT_NAME_CONFIG; }
    public synchronized void setBotName(String botName) {
        BOT_NAME_CONFIG = botName;
        try {
            config.put("bot_name", botName);
        } catch (JSONException e) {
            System.err.println("JSON error updating bot_name in config: " + e);
        }
        saveConfig();
        System.out.println("Bot name updated to: " + BOT_NAME_CONFIG);
    }


    public int getBotSocketPort() { return BOT_HTTP_PORT_CONFIG; }
    public synchronized void setBotSocketPort(int port) {
        BOT_HTTP_PORT_CONFIG = port;
        try {
            config.put("bot_http_port", port);
        } catch (JSONException e) {
            System.err.println("JSON error updating bot_http_port in config: " + e);
        }
        saveConfig();
        System.out.println("Bot port updated to: " + BOT_HTTP_PORT_CONFIG);
    }
    public String getWebServerEndpoint() { return WEB_SERVER_ENDPOINT_CONFIG; }
    public long getDbPollingRate() { return DB_POLLING_RATE_CONFIG; }
    public long getMessageSendRate() { return MESSAGE_SEND_RATE_CONFIG; }

    public synchronized void setWebServerEndpoint(String endpoint) {
        WEB_SERVER_ENDPOINT_CONFIG = endpoint;
        try {
            config.put("web_server_endpoint", endpoint);
        } catch (JSONException e) {
            System.err.println("JSON error updating web_server_endpoint in config: " + e);
        }
        saveConfig();
        System.out.println("WebServerEndpoint updated to: " + WEB_SERVER_ENDPOINT_CONFIG);
    }

    public synchronized void setDbPollingRate(long rate) {
        DB_POLLING_RATE_CONFIG = rate;
        try {
            config.put("db_polling_rate", rate);
        } catch (JSONException e) {
            System.err.println("JSON error updating db_polling_rate in config: " + e);
        }
        saveConfig();
        System.out.println("DbPollingRate updated to: " + DB_POLLING_RATE_CONFIG);
    }

    public synchronized void setMessageSendRate(long rate) {
        MESSAGE_SEND_RATE_CONFIG = rate;
        try {
            config.put("message_send_rate", rate);
        } catch (JSONException e) {
            System.err.println("JSON error updating message_send_rate in config: " + e);
        }
        saveConfig();
        System.out.println("MessageSendRate updated to: " + MESSAGE_SEND_RATE_CONFIG);
    }
}