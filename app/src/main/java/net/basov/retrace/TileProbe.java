package net.basov.retrace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

import net.basov.retrace.core.TileMath;

// ----------------------------------------------------------------------
// The check of the map tile server
// ----------------------------------------------------------------------
//
// Android 6 does not hold all the root certificates that a new device holds.
// This check downloads one tile and records the root certificate of the
// server. The result tells if the map needs its own trust store on old
// devices. See section 7 of doc/PLAN.md.
//
// The check blocks the thread. Call it from a background thread only.
final class TileProbe {

    // The tile server of OpenStreetMap. Its usage policy asks for a
    // User-Agent that names the application.
    static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";

    // The Royal Observatory in Greenwich, at zoom level 15.
    static final double LATITUDE = 51.4779;
    static final double LONGITUDE = -0.0015;
    static final int ZOOM = 15;

    private static final int TIMEOUT_MS = 15000;

    // A tile is smaller than this limit. A larger answer is not a tile.
    private static final int MAX_BYTES = 1024 * 1024;

    // The result of one check.
    static final class Result {
        final String url;
        final int httpCode;
        final byte[] body;
        final long millis;
        final String rootIssuer;
        final IOException error;

        Result(String url, int httpCode, byte[] body, long millis,
               String rootIssuer, IOException error) {
            this.url = url;
            this.httpCode = httpCode;
            this.body = body;
            this.millis = millis;
            this.rootIssuer = rootIssuer;
            this.error = error;
        }

        boolean isTlsFailure() {
            return error instanceof SSLException;
        }
    }

    private TileProbe() {
    }

    static String userAgent(String versionName) {
        return "Retrace/" + versionName + " (Android; net.basov.retrace)";
    }

    static Result run(String userAgent) {
        String url = TileMath.tileUrl(TILE_URL, ZOOM, LATITUDE, LONGITUDE);
        long start = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", userAgent);

            int code = connection.getResponseCode();
            String issuer = rootIssuer(connection);
            InputStream in = code < 400 ? connection.getInputStream() : connection.getErrorStream();
            byte[] body = in == null ? new byte[0] : readAll(in);
            return new Result(url, code, body, elapsedMs(start), issuer, null);
        } catch (IOException e) {
            return new Result(url, -1, new byte[0], elapsedMs(start), null, e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // Returns the issuer of the last certificate in the chain of the server.
    // That issuer is the root certificate that the device trusts.
    private static String rootIssuer(HttpURLConnection connection) {
        if (!(connection instanceof HttpsURLConnection)) {
            return null;
        }
        try {
            Certificate[] chain = ((HttpsURLConnection) connection).getServerCertificates();
            if (chain.length == 0 || !(chain[chain.length - 1] instanceof X509Certificate)) {
                return null;
            }
            X509Certificate last = (X509Certificate) chain[chain.length - 1];
            return last.getIssuerX500Principal().getName();
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > MAX_BYTES) {
                    throw new IOException("The answer is larger than " + MAX_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1000000L;
    }
}
