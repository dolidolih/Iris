package party.qwer.iris;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

public class AssetManager {
    public static String readFile(String filename) throws IOException {
        ClassLoader loader = AssetManager.class.getClassLoader();
        if (loader == null) {
            throw new RuntimeException("ClassLoader를 찾을 수 없습니다.");
        }
        String path = Paths.get("assets", filename).toString();

        try (InputStream stream = loader.getResource(path).openStream()) {
            int count;
            byte[] buffer = new byte[1024];
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream(stream.available());

            while (true) {
                count = stream.read(buffer);
                if (count <= 0) break;
                byteStream.write(buffer, 0, count);
            }

            return byteStream.toString();
        }
    }
}