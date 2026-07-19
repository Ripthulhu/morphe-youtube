/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

public final class PlaylistRoutes {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    /**
     * The web InnerTube host. The {@code next} endpoint must be reached through this host with a
     * WEB client context; see {@link #GET_WATCH_NEXT}.
     */
    private static final String YT_WEB_API_URL = "https://www.youtube.com/youtubei/v1/";

    private static final int CLIENT_ID = 3;
    private static final String CLIENT_NAME = "ANDROID";
    private static final String CLIENT_VERSION = "20.26.46";
    private static final String PACKAGE_NAME = "com.google.android.youtube";

    private static final int WEB_CLIENT_ID = 1;
    private static final String WEB_CLIENT_NAME = "WEB";
    private static final String WEB_CLIENT_VERSION = "2.20240726.00.00";
    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    public static final Route.CompiledRoute CREATE_PLAYLIST = new Route(
            Route.Method.POST, "playlist/create?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute GET_SET_VIDEO_ID = new Route(
            Route.Method.POST, "next?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute EDIT_PLAYLIST = new Route(
            Route.Method.POST, "browse/edit_playlist?fields=status,playlistEditResults"
    ).compile();

    public static final Route.CompiledRoute GET_PLAYLISTS = new Route(
            Route.Method.POST, "playlist/get_add_to_playlist?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute BROWSE_PLAYLIST = new Route(
            Route.Method.POST, "browse?prettyPrint=false"
    ).compile();

    /**
     * Path prefix shared by every field kept from the mix/radio playlist panel.
     */
    private static final String MIX_RENDERER = "contents.singleColumnWatchNextResults." +
            "playlist.playlist.contents.playlistPanelVideoRenderer.";

    /**
     * Mix/radio ({@code RD<videoId>}) playlist panel for a video, on the ANDROID context.
     *
     * <p>The mask keeps two things. {@code playerParams} is what the music-video detection in
     * RememberPlaybackSpeedPatch reads. The id/title/byline/duration fields are what
     * {@code list_mix} reads; they were added to the same mask rather than given a second route so
     * that both consumers share one cached request instead of issuing the watch-next call twice.
     *
     * <p>Written as explicit comma separated dotted paths rather than the shorter
     * {@code renderer(a,b,c)} grouping syntax: the grouping form is unverified against this
     * endpoint, and a mask this route rejects would break the music detection as well.</p>
     */
    public static final Route.CompiledRoute GET_MIX_PLAYLIST = new Route(
            Route.Method.POST,
            "next" +
                    "?fields=" + MIX_RENDERER +
                    "navigationEndpoint.coWatchWatchEndpointWrapperCommand." +
                    "watchEndpoint.watchEndpoint.playerParams" +
                    "," + MIX_RENDERER + "videoId" +
                    "," + MIX_RENDERER + "title" +
                    "," + MIX_RENDERER + "longBylineText" +
                    "," + MIX_RENDERER + "shortBylineText" +
                    "," + MIX_RENDERER + "lengthText" +
                    "&prettyPrint=false"
    ).compile();

    /**
     * Related/suggested videos for a watch page.
     *
     * <p>Deliberately unmasked. {@link #GET_MIX_PLAYLIST} points at the same {@code next} endpoint
     * but carries a {@code ?fields=} mask that keeps only the playlist panel, which strips the
     * secondary results entirely — it cannot be reused here.</p>
     *
     * <p>Must be sent with the WEB client context ({@link #webContext()}). The ANDROID context
     * returns the related shelf as Litho {@code elementRenderer} blobs with no parseable video
     * metadata at all; the WEB context returns {@code lockupViewModel} entries.</p>
     */
    public static final Route.CompiledRoute GET_WATCH_NEXT = new Route(
            Route.Method.POST, "next?prettyPrint=false"
    ).compile();

    private PlaylistRoutes() {
    }

    private static JSONObject androidContext() throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("deviceMake", Build.MANUFACTURER);
        client.put("deviceModel", Build.MODEL);
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        Locale localeDefault = Locale.getDefault();
        client.put("hl", localeDefault.getLanguage());
        client.put("gl", localeDefault.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    /**
     * WEB client context. Kept separate from {@link #androidContext()} rather than parameterised,
     * because the two are not interchangeable: device fields are meaningless for WEB, and the
     * response shape differs (lockupViewModel vs elementRenderer).
     */
    private static JSONObject webContext() throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", WEB_CLIENT_NAME);
        client.put("clientVersion", WEB_CLIENT_VERSION);
        Locale localeDefault = Locale.getDefault();
        String language = localeDefault.getLanguage();
        String country = localeDefault.getCountry();
        client.put("hl", language.isEmpty() ? "en" : language);
        client.put("gl", country.isEmpty() ? "US" : country);

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    public static byte[] getWatchNextBody(String videoId) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", webContext());
            body.put("videoId", videoId);
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getWatchNextBody failed", ex);
        }
        return new byte[0];
    }

    private static JSONObject getBaseContentJson() throws JSONException {
        JSONObject body = new JSONObject();
        body.put("context", androidContext());
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        return body;
    }

    public static byte[] createPlaylistBody(String videoId, String title) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("params", "CAQ%3D");
            body.put("title", title);
            JSONArray videoIds = new JSONArray();
            videoIds.put(videoId);
            body.put("videoIds", videoIds);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getSetVideoIdBody(String videoId, String playlistId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("videoId", videoId);
            body.put("playlistId", playlistId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getSetVideoIdBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] editPlaylistBody(String videoId, String playlistId, String setVideoId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);

            JSONObject action = new JSONObject();
            if (setVideoId != null && !setVideoId.isEmpty()) {
                action.put("action", "ACTION_REMOVE_VIDEO");
                action.put("setVideoId", setVideoId);
            } else {
                action.put("action", "ACTION_ADD_VIDEO");
                action.put("addedVideoId", videoId);
            }
            JSONArray actions = new JSONArray();
            actions.put(action);
            body.put("actions", actions);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "editPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getPlaylistsBody(String playlistId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);
            body.put("excludeWatchLater", false);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getPlaylistsBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getMixPlaylistBody(String videoId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("videoId", videoId);
            body.put("playlistId", "RD" + videoId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getMixPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] browsePlaylistBody(String playlistId) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", androidContext());
            body.put("browseId", "VL" + playlistId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "browsePlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] savePlaylistBody(String playlistId, String libraryId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);

            JSONObject action = new JSONObject();
            action.put("action", "ACTION_ADD_PLAYLIST");
            action.put("addedFullListId", libraryId);
            JSONArray actions = new JSONArray();
            actions.put(action);
            body.put("actions", actions);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "savePlaylistBody failed", ex);
        }
        return new byte[0];
    }

    /**
     * Connection for routes that require the WEB client, notably {@link #GET_WATCH_NEXT}.
     *
     * <p>The client-name header must be {@code 1} (WEB) and must agree with the clientName in the
     * body context; a mismatch makes YouTube fall back to a different response shape. The
     * X-GOOG-API-FORMAT-VERSION header used by the android connection is deliberately not sent.</p>
     *
     * <p>Auth headers are optional here — signed-out requests still return results, signed-in ones
     * are personalised — so an empty or null map is passed through without complaint.</p>
     */
    public static HttpURLConnection getWebConnection(Route.CompiledRoute route, Map<String, String> authHeaders) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_WEB_API_URL, route);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", WEB_USER_AGENT);
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(WEB_CLIENT_ID));
        connection.setRequestProperty("X-YouTube-Client-Version", WEB_CLIENT_VERSION);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

        applyAuthHeaders(connection, authHeaders);
        return connection;
    }

    private static void applyAuthHeaders(HttpURLConnection connection, @Nullable Map<String, String> authHeaders) {
        if (authHeaders == null) return;
        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isEmpty()) {
                connection.setRequestProperty(entry.getKey(), value);
            }
        }
    }

    public static HttpURLConnection getConnection(Route.CompiledRoute route, Map<String, String> authHeaders) throws IOException {
        String userAgent = String.format(Locale.US,
                "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                PACKAGE_NAME, CLIENT_VERSION, Build.VERSION.RELEASE,
                Locale.getDefault(), Build.MODEL, Build.ID);

        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        connection.setRequestProperty("X-GOOG-API-FORMAT-VERSION", "2");
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

        if (authHeaders != null) {
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    connection.setRequestProperty(entry.getKey(), value);
                }
            }
        }

        return connection;
    }
}
