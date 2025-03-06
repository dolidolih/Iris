// ConfigPageDocumentProvider.java
package party.qwer.iris;

import java.io.IOException;

public class ConfigPageDocumentProvider {
    public static String renderPage(Configurable config) throws IOException {
        String html = AssetManager.readFile("config.html");
        html = html.replace("CURRENT_WEB_ENDPOINT", config.getWebServerEndpoint());
        html = html.replace("CURRENT_BOT_NAME", config.getBotName());
        html = html.replace("CURRENT_DB_RATE", String.valueOf(config.getDbPollingRate()));
        html = html.replace("CURRENT_SEND_RATE", String.valueOf(config.getMessageSendRate()));
        html = html.replace("CURRENT_BOT_PORT", String.valueOf(config.getBotSocketPort()));
        return html;
    }
}