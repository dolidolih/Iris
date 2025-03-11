// ConfigPageDocumentProvider.java
package party.qwer.iris;

import java.io.IOException;

public class PageRenderer {
    public static String renderDashboard() throws IOException {
        String html = AssetManager.readFile("dashboard.html");
        html = html.replace("CURRENT_WEB_ENDPOINT", Configurable.INSTANCE.getWebServerEndpoint());
        html = html.replace("CURRENT_BOT_NAME", Configurable.INSTANCE.getBotName());
        html = html.replace("CURRENT_DB_RATE", String.valueOf(Configurable.INSTANCE.getDbPollingRate()));
        html = html.replace("CURRENT_SEND_RATE", String.valueOf(Configurable.INSTANCE.getMessageSendRate()));
        html = html.replace("CURRENT_BOT_PORT", String.valueOf(Configurable.INSTANCE.getBotSocketPort()));
        return html;
    }
}