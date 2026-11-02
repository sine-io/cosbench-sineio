import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;

/** Failure-only peer: prove the archive CLI rejects timeouts and ignored ranges. */
public final class ArchiveEndpointFixture {
    public static void main(String[] args) throws Exception {
        var restored = ConcurrentHashMap.<String>newKeySet();
        var pending = ConcurrentHashMap.<String>newKeySet();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                boolean post = exchange.getRequestMethod().equals("POST");
                int status = 200;
                byte[] body = "archive replay failure fixture".getBytes(StandardCharsets.UTF_8);
                if (path.equals("/health")) {
                    body = "archive-failure-fixture".getBytes(StandardCharsets.UTF_8);
                } else if (post) {
                    exchange.getRequestBody().readAllBytes();
                    restored.add(path);
                    if (path.startsWith("/slow/")) Thread.sleep(5000);
                    body = new byte[0];
                } else if (!path.startsWith("/readable/")
                        && (!restored.contains(path) || (path.startsWith("/poll/") && pending.add(path)))) {
                    status = 403;
                    body = "<Error><Code>InvalidObjectState</Code><Message>Archived fixture</Message></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                }
                // Deliberately ignore Range; accepting this response would be a false pass.
                exchange.getResponseHeaders().set("Content-Type", status == 403 ? "application/xml" : "application/octet-stream");
                exchange.sendResponseHeaders(status, post ? -1 : body.length);
                if (!post) exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException disconnected) {
                // Deadline and interruption tests intentionally disconnect.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }
}
