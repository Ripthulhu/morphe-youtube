/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Fetches the related/suggested videos for a video id from the InnerTube {@code next} endpoint.
 *
 * <p>Shape notes, measured rather than inherited from older documentation:</p>
 * <ul>
 *   <li>The ANDROID client context is unusable — the related shelf comes back as Litho
 *       {@code elementRenderer} blobs with no parseable video metadata.</li>
 *   <li>The WEB client context works, but no longer returns {@code compactVideoRenderer}. It
 *       returns {@code lockupViewModel} entries, normally under
 *       {@code contents.twoColumnWatchNextResults.secondaryResults}. This class walks the whole
 *       response tree for the key instead of hardcoding that path, because the wrapper layers
 *       around it change often and a hardcoded path fails silently and completely.</li>
 * </ul>
 *
 * <p>Within a {@code lockupViewModel} the only signal separating the video title from the channel
 * name is field ORDER: the {@code metadata} subtree yields {@code content} strings in the order
 * title, channel, view count, age. There is no key that names either one. That makes the parse
 * inherently fragile, so it degrades per field — a missing title or channel becomes null on that
 * one suggestion instead of dropping the entry or failing the request.</p>
 */
public final class GetSuggestionsRequest {

    /**
     * Upper bound on the wait for a fetch. This is a network call on a binder thread in the
     * provider's case, so the caller supplies its own, shorter budget; this is the ceiling.
     */
    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 10 * 1000;

    /** Guard against a runaway walk over an unexpected response; the real tree is ~418KB. */
    private static final int MAX_TREE_DEPTH = 24;

    /** Only video lockups. Playlists and mixes appear in the same shelf with other content types. */
    private static final String CONTENT_TYPE_VIDEO = "LOCKUP_CONTENT_TYPE_VIDEO";

    private static final Pattern DURATION_PATTERN = Pattern.compile("\\d{1,3}(:[0-5]\\d){1,2}");

    private static final Map<String, GetSuggestionsRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(20));

    public final String videoId;
    private final Future<List<Suggestion>> future;

    private GetSuggestionsRequest(String videoId, Map<String, String> requestHeader) {
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, requestHeader));
    }

    public static GetSuggestionsRequest fetchRequestIfNeeded(String videoId, Map<String, String> requestHeader) {
        cache.computeIfAbsent(
                Objects.requireNonNull(videoId),
                k -> new GetSuggestionsRequest(videoId, requestHeader)
        );
        return cache.get(videoId);
    }

    @Nullable
    public static GetSuggestionsRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    public static void clear() {
        cache.clear();
    }

    /**
     * @return the suggestions, or null if the fetch failed, timed out or was interrupted. Null and
     * empty are distinct: empty means YouTube returned no usable video lockups.
     */
    @Nullable
    public List<Suggestion> getSuggestions() {
        return getSuggestions(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH);
    }

    @Nullable
    public List<Suggestion> getSuggestions(long maxWaitMilliseconds) {
        try {
            final long maxWaitTime = Math.min(maxWaitMilliseconds, MAX_MILLISECONDS_TO_WAIT_FOR_FETCH);
            if (!future.isDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Blocking main thread waiting for suggestions: " + videoId);
            }
            return future.get(maxWaitTime, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getSuggestions timed out: " + videoId, ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getSuggestions interrupted: " + videoId, ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getSuggestions failure: " + videoId, ex);
        }
        return null;
    }

    private static void handleConnectionError(String message, @Nullable Exception ex) {
        Logger.printInfo(() -> message, ex);
    }

    @Nullable
    private static JSONObject sendRequest(String videoId, Map<String, String> requestHeader) {
        Objects.requireNonNull(videoId);
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching watch next suggestions, videoId: " + videoId);

        try {
            byte[] requestBody = PlaylistRoutes.getWatchNextBody(videoId);
            if (requestBody.length == 0) {
                handleConnectionError("Suggestions request body could not be built", null);
                return null;
            }
            HttpURLConnection connection =
                    PlaylistRoutes.getWebConnection(PlaylistRoutes.GET_WATCH_NEXT, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            final int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                return Requester.parseJSONObject(connection);
            }
            handleConnectionError("Get suggestions failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "suggestions fetch for " + videoId
                    + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }

    @Nullable
    private static List<Suggestion> fetch(String videoId, Map<String, String> requestHeader) {
        JSONObject json = sendRequest(videoId, requestHeader);
        if (json == null) return null;
        return parseResponse(json);
    }

    @Nullable
    private static List<Suggestion> parseResponse(JSONObject json) {
        try {
            List<JSONObject> lockups = new ArrayList<>();
            collectObjectsForKey(json, "lockupViewModel", lockups, 0);

            List<Suggestion> suggestions = new ArrayList<>(lockups.size());
            Set<String> seenIds = new HashSet<>();

            for (JSONObject lockup : lockups) {
                Suggestion suggestion = parseLockup(lockup);
                // A lockup with no id is useless to the caller: it cannot be played.
                if (suggestion != null && seenIds.add(suggestion.videoId)) {
                    suggestions.add(suggestion);
                }
            }
            return suggestions;
        } catch (Exception ex) {
            Logger.printException(() -> "parseResponse failed", ex);
        }
        return null;
    }

    /**
     * Parses one lockup. Returns null only when the entry is not a usable video — everything else
     * degrades to a null field, because the caller can still play a suggestion whose duration or
     * channel could not be read.
     */
    @Nullable
    private static Suggestion parseLockup(JSONObject lockup) {
        String contentId = lockup.optString("contentId", "");
        if (contentId.isEmpty()) return null;

        // Playlists, mixes and shorts shelves also arrive as lockups. Filter on the declared type
        // rather than guessing from the fields present.
        String contentType = lockup.optString("contentType", "");
        if (!CONTENT_TYPE_VIDEO.equals(contentType)) return null;

        String title = null;
        String channel = null;
        Object metadata = lockup.opt("metadata");
        if (metadata != null) {
            List<String> contents = new ArrayList<>();
            // Order is the ONLY signal here: [title, channel, "248K views", "4 days ago"].
            // No key distinguishes them, so read positionally and guard the length rather than
            // indexing blindly — a metadata block with one entry must not throw.
            collectStringsForKey(metadata, "content", contents, 0);
            if (!contents.isEmpty()) title = emptyToNull(contents.get(0));
            if (contents.size() > 1) channel = emptyToNull(contents.get(1));
        }

        String duration = null;
        // The duration badge lives in the thumbnail overlay under contentImage. Prefer that
        // subtree, then fall back to the whole lockup, and in both cases require the string to
        // look like a duration so an unrelated "text" field cannot masquerade as one.
        Object contentImage = lockup.opt("contentImage");
        if (contentImage != null) {
            duration = findDurationText(contentImage);
        }
        if (duration == null) {
            duration = findDurationText(lockup);
        }

        return new Suggestion(contentId, title, channel, duration);
    }

    @Nullable
    private static String findDurationText(Object node) {
        List<String> texts = new ArrayList<>();
        collectStringsForKey(node, "text", texts, 0);
        for (String text : texts) {
            if (text != null && DURATION_PATTERN.matcher(text).matches()) return text;
        }
        return null;
    }

    /**
     * Depth-first walk collecting every object stored under [key], in document order.
     * Used instead of a fixed path because the wrappers above the lockups change between
     * responses while the lockup itself has been stable.
     */
    private static void collectObjectsForKey(Object node, String key, List<JSONObject> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = object.opt(name);
                if (key.equals(name) && value instanceof JSONObject match) {
                    out.add(match);
                    // Do not descend into a match: nested lockups would duplicate entries.
                    continue;
                }
                collectObjectsForKey(value, key, out, depth + 1);
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0, length = array.length(); i < length; i++) {
                collectObjectsForKey(array.opt(i), key, out, depth + 1);
            }
        }
    }

    /** Depth-first walk collecting every String stored under [key], in document order. */
    private static void collectStringsForKey(Object node, String key, List<String> out, int depth) {
        if (depth > MAX_TREE_DEPTH) return;
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = object.opt(name);
                if (key.equals(name) && value instanceof String text) {
                    out.add(text);
                } else {
                    collectStringsForKey(value, key, out, depth + 1);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0, length = array.length(); i < length; i++) {
                collectStringsForKey(array.opt(i), key, out, depth + 1);
            }
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One suggested video. Immutable, and deliberately not kotlin.Pair: the caller needs four
     * named fields and any of the last three can legitimately be null.
     */
    public static final class Suggestion {
        public final String videoId;
        @Nullable
        public final String title;
        @Nullable
        public final String channel;
        @Nullable
        public final String duration;

        Suggestion(String videoId, @Nullable String title, @Nullable String channel, @Nullable String duration) {
            this.videoId = Objects.requireNonNull(videoId);
            this.title = title;
            this.channel = channel;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return "Suggestion{" + videoId + ", " + title + ", " + channel + ", " + duration + '}';
        }
    }
}
