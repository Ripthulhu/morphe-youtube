/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import org.json.JSONArray;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Fetches the mix/radio playlist ({@code RD<videoId>}) for a video from the InnerTube
 * {@code next} endpoint, on the ANDROID client context.
 *
 * <p>Two consumers share this one request and its cache:</p>
 * <ul>
 *   <li>RememberPlaybackSpeedPatch, which only needs {@link #getResult()} — whether the mix marks
 *       the video as music.</li>
 *   <li>The {@code list_mix} provider operation, which needs {@link #getMixItems} — the panel
 *       entries with id, title, channel and duration.</li>
 * </ul>
 *
 * <p>The response JSON is what is cached, not a derived value, so a second consumer can read a
 * different projection of an already completed fetch without a second network call.</p>
 */
public class GetMixPlaylistRequest {
    /**
     * Maximum amount of time to block the UI from updates while waiting for network call to complete.
     *
     * Must be less than 5 seconds, as per:
     * https://developer.android.com/topic/performance/vitals/anr
     */
    private static final long MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH = 4500;

    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 10 * 1000; // 10 seconds

    private static final Map<String, GetMixPlaylistRequest> cache =
            Utils.createSizeRestrictedMap(30);

    public final String videoId;
    private final Future<JSONObject> future;

    private GetMixPlaylistRequest(String videoId, Map<String, String> requestHeader) {
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> sendRequest(videoId, requestHeader));
    }

    /**
     * @return the raw response, or null if the fetch failed, timed out or was interrupted.
     */
    @Nullable
    private JSONObject awaitResponse(long maxWaitMilliseconds) {
        try {
            final long maxWaitTime = Math.min(maxWaitMilliseconds, MAX_MILLISECONDS_TO_WAIT_FOR_FETCH);
            if (BaseSettings.DEBUG.get() && !future.isDone()) {
                Logger.printDebug(() -> "Waiting until fetch is complete: " + videoId
                        + " maxWait: " + maxWaitTime);
            }
            return future.get(maxWaitTime, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getResult timed out: " + videoId, ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getResult interrupted: " + videoId, ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getResult failure", ex);
        }

        return null;
    }

    /**
     * @return whether the mix marks this video as music. Null only if the fetch did not complete
     * in time, matching the original contract this method has always had.
     */
    public Boolean getResult() {
        // Speed override can be called concurrently while prefetch is still running.
        // If on main thread then must use shorter max wait otherwise Android can show ANR warning.
        final long maxWaitTime = Utils.isCurrentlyOnMainThread()
                ? MAX_MILLISECONDS_TO_BLOCK_UI_WAITING_FOR_FETCH
                : MAX_MILLISECONDS_TO_WAIT_FOR_FETCH;
        JSONObject json = awaitResponse(maxWaitTime);
        // A completed fetch that returned nothing is a definitive "not music", exactly as before
        // this class cached the response instead of the boolean.
        if (json == null && future.isDone()) {
            return false;
        }
        if (json == null) {
            return null;
        }
        return parseResponse(json);
    }

    /**
     * @return the mix entries, or null if the fetch failed, timed out or was interrupted. Null and
     * empty are distinct: empty means the response carried no usable playlist panel entries.
     */
    @Nullable
    public List<MixItem> getMixItems(long maxWaitMilliseconds) {
        if (!future.isDone() && Utils.isCurrentlyOnMainThread()) {
            Logger.printException(() -> "Blocking main thread waiting for mix playlist: " + videoId);
        }
        JSONObject json = awaitResponse(maxWaitMilliseconds);
        if (json == null) return null;
        return parseMixItems(json, videoId);
    }

    public static GetMixPlaylistRequest fetchRequestIfNeeded(String videoId, Map<String, String> requestHeader) {
        final String key = cacheKey(videoId, requestHeader);
        cache.computeIfAbsent(key, k -> new GetMixPlaylistRequest(videoId, requestHeader));
        return cache.get(key);
    }

    @Nullable
    public static GetMixPlaylistRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    /**
     * Drops one cached request, so a failure is not pinned for the life of the process.
     */
    public static void remove(String videoId, Map<String, String> requestHeader) {
        cache.remove(cacheKey(videoId, requestHeader));
    }

    /**
     * Authenticated and anonymous fetches of the same video are different requests and must not
     * share an entry. The anonymous key is the bare video id so that
     * {@link #getRequestForVideoId(String)} — which has no header argument and is called by the
     * playback speed patch, whose prefetch passes no headers — keeps finding its own entry.
     */
    private static String cacheKey(String videoId, @Nullable Map<String, String> requestHeader) {
        Objects.requireNonNull(videoId);
        return (requestHeader == null || requestHeader.isEmpty()) ? videoId : videoId + "|auth";
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject sendRequest(String videoId, Map<String, String> requestHeader) {
        Objects.requireNonNull(videoId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching get mix playlist, videoId: " + videoId);

        try {
            byte[] requestBody = PlaylistRoutes.getMixPlaylistBody(videoId);
            HttpURLConnection connection = PlaylistRoutes.getConnection(PlaylistRoutes.GET_MIX_PLAYLIST, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Get mix playlist failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "send failed", ex);
        } finally {
            Logger.printDebug(() -> "mix playlist items fetch took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    private static Boolean parseResponse(JSONObject json) {
        try {
            JSONObject singleColumnWatchNextResults = json.getJSONObject("contents")
                    .getJSONObject("singleColumnWatchNextResults");
            if (!singleColumnWatchNextResults.has("playlist")) {
                return false;
            }
            JSONObject playlist = singleColumnWatchNextResults.getJSONObject("playlist")
                    .getJSONObject("playlist");
            if (!(playlist.getJSONArray("contents").get(0) instanceof JSONObject firstPlaylistContent)) {
                return false;
            }
            JSONObject navigationEndpoint = firstPlaylistContent.getJSONObject("playlistPanelVideoRenderer")
                    .getJSONObject("navigationEndpoint");
            if (!navigationEndpoint.has("coWatchWatchEndpointWrapperCommand")) {
                return false;
            }
            JSONObject watchEndpoint = navigationEndpoint.getJSONObject("coWatchWatchEndpointWrapperCommand")
                    .getJSONObject("watchEndpoint")
                    .getJSONObject("watchEndpoint");

            if (!watchEndpoint.has("playerParams")) {
                return false;
            }

            return watchEndpoint.getString("playerParams").startsWith("8AUB");
        } catch (JSONException e) {
            Logger.printDebug(() -> "parseResponse failed: " + json, e);
        }

        return false;
    }

    /**
     * Parses the playlist panel into playable entries.
     *
     * <p>Degrades per field rather than per response: an entry with no id is dropped because it
     * cannot be played, but a missing title, channel or duration leaves that one field null and
     * keeps the entry.</p>
     *
     * @param sourceVideoId excluded from the result — the mix panel leads with the video the mix
     *                      was built from, and offering the caller the video it is already
     *                      playing back as a suggestion is noise.
     */
    private static List<MixItem> parseMixItems(JSONObject json, String sourceVideoId) {
        List<MixItem> items = new ArrayList<>();
        try {
            JSONObject singleColumnWatchNextResults = json.getJSONObject("contents")
                    .getJSONObject("singleColumnWatchNextResults");
            if (!singleColumnWatchNextResults.has("playlist")) {
                return items;
            }
            JSONArray contents = singleColumnWatchNextResults.getJSONObject("playlist")
                    .getJSONObject("playlist")
                    .getJSONArray("contents");

            Set<String> seenIds = new HashSet<>();
            seenIds.add(sourceVideoId);

            for (int i = 0, length = contents.length(); i < length; i++) {
                if (!(contents.opt(i) instanceof JSONObject content)) continue;
                JSONObject renderer = content.optJSONObject("playlistPanelVideoRenderer");
                if (renderer == null) continue;

                String id = renderer.optString("videoId", "");
                if (id.isEmpty() || !seenIds.add(id)) continue;

                String channel = readText(renderer.opt("longBylineText"));
                if (channel == null) {
                    channel = readText(renderer.opt("shortBylineText"));
                }
                items.add(new MixItem(
                        id,
                        readText(renderer.opt("title")),
                        channel,
                        readText(renderer.opt("lengthText"))));
            }
        } catch (JSONException ex) {
            Logger.printDebug(() -> "parseMixItems failed", ex);
        }
        return items;
    }

    /**
     * Reads an InnerTube text node. Both shapes appear in this response — {@code simpleText} on
     * some entries, {@code runs} on others — so both are handled instead of assuming one.
     */
    @Nullable
    private static String readText(@Nullable Object node) {
        if (!(node instanceof JSONObject text)) return null;

        String simpleText = text.optString("simpleText", "");
        if (!simpleText.trim().isEmpty()) return simpleText.trim();

        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return null;

        StringBuilder builder = new StringBuilder();
        for (int i = 0, length = runs.length(); i < length; i++) {
            if (runs.opt(i) instanceof JSONObject run) {
                builder.append(run.optString("text", ""));
            }
        }
        String joined = builder.toString().trim();
        return joined.isEmpty() ? null : joined;
    }

    /**
     * One entry of a mix. Mirrors GetSuggestionsRequest.Suggestion field for field so the provider
     * can render either list into the same JSON shape.
     */
    public static final class MixItem {
        public final String videoId;
        @Nullable
        public final String title;
        @Nullable
        public final String channel;
        @Nullable
        public final String duration;

        MixItem(String videoId, @Nullable String title, @Nullable String channel,
                @Nullable String duration) {
            this.videoId = Objects.requireNonNull(videoId);
            this.title = title;
            this.channel = channel;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return "MixItem{" + videoId + ", " + title + ", " + channel + ", " + duration + '}';
        }
    }
}
