package coint.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Utility class for HTTP operations.
 */
public final class HttpUtil {

    private static final Logger LOG = LogManager.getLogger(HttpUtil.class);

    private static final int DEFAULT_CONNECT_TIMEOUT = 10000;
    private static final int DEFAULT_READ_TIMEOUT = 10000;

    private HttpUtil() {
        // Utility class
    }

    /**
     * Perform an asynchronous POST request with JSON body.
     *
     * @param urlString The URL to send the request to
     * @param jsonData  The JSON data to send
     * @return CompletableFuture with the response code
     */
    public static CompletableFuture<Integer> postJsonAsync(String urlString, String jsonData) {
        return CompletableFuture.supplyAsync(() -> postJson(urlString, jsonData));
    }

    /**
     * Perform a synchronous POST request with JSON body.
     *
     * @param urlString The URL to send the request to
     * @param jsonData  The JSON data to send
     * @return The HTTP response code, or -1 on error
     */
    public static int postJson(String urlString, String jsonData) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
            connection.setReadTimeout(DEFAULT_READ_TIMEOUT);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            LOG.debug("HTTP POST {} - Response: {}", urlString, responseCode);
            return responseCode;
        } catch (IOException e) {
            LOG.error("HTTP POST error for {}: {}", urlString, e.getMessage());
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Perform a synchronous GET request.
     *
     * @param urlString The URL to send the request to
     * @return The response body, or null on error
     */
    public static String get(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
            connection.setReadTimeout(DEFAULT_READ_TIMEOUT);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    return response.toString();
                }
            } else {
                LOG.warn("HTTP GET {} returned status {}", urlString, responseCode);
                return null;
            }
        } catch (IOException e) {
            LOG.error("HTTP GET error for {}: {}", urlString, e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Perform an asynchronous GET request.
     *
     * @param urlString The URL to send the request to
     * @return CompletableFuture with the response body
     */
    public static CompletableFuture<String> getAsync(String urlString) {
        return CompletableFuture.supplyAsync(() -> get(urlString));
    }
}
