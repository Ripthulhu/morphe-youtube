package app.morphe.extension.keep.api;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;

import app.morphe.extension.shared.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * External bridge for Voice Assistant. It intentionally exposes only {@link #call} and never
 * proxies arbitrary URI operations. Keep's own provider remains the source of truth for storage
 * and sync.
 */
public final class KeepApiProvider extends ContentProvider {
    private static final int API_VERSION = 1;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_TITLE = 1_000;
    private static final int MAX_TEXT = 20_000;
    private static final int MAX_ITEMS = 200;
    private static final int MAX_PREVIEW = 240;
    private static final String KEEP_PROVIDER_CLASS =
            "com.google.android.apps.keep.shared.provider.KeepProviderImpl";
    private static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            "app.ripthulhu.voiceassistant"));
    private static final AtomicLong LAST_UUID_TIME = new AtomicLong();

    private volatile String internalAuthority;
    private volatile Schema schema;

    @Override
    public boolean onCreate() {
        return getContext() != null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isAllowedCaller()) return failure("FORBIDDEN", "Caller is not allowed.");
        if (method == null || method.length() > 64) return failure("VALIDATION", "Invalid method.");
        try {
            Schema currentSchema = schema();
            JSONObject result;
            switch (method) {
                case "capabilities":
                    result = capabilities(currentSchema);
                    break;
                case "list_documents":
                    result = listDocuments(currentSchema, extras);
                    break;
                case "get_document":
                    result = document(currentSchema, requiredId(extras, "document_id"));
                    break;
                case "create_note":
                    result = createDocument(currentSchema, extras, 0);
                    break;
                case "create_list":
                    result = createDocument(currentSchema, extras, 1);
                    break;
                case "update_document":
                    result = updateDocument(currentSchema, extras);
                    break;
                case "add_item":
                    result = addItem(currentSchema, extras);
                    break;
                case "update_item":
                    result = updateItem(currentSchema, extras);
                    break;
                case "delete_item":
                    result = deleteItem(currentSchema, extras);
                    break;
                case "delete_document":
                    result = deleteDocument(currentSchema, extras);
                    break;
                default:
                    return failure("VALIDATION", "Unknown method.");
            }
            return success(result);
        } catch (ApiException exception) {
            return failure(exception.code, exception.getMessage());
        } catch (SecurityException exception) {
            return failure("FORBIDDEN", "Keep rejected the request.");
        } catch (OperationApplicationException exception) {
            return failure("UNAVAILABLE", "Keep could not apply the change.");
        } catch (Exception exception) {
            return failure("INTERNAL", "Keep API operation failed.");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }

    private JSONObject capabilities(Schema currentSchema) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("api_version", API_VERSION);
        result.put("operations", new JSONArray(Arrays.asList("capabilities", "list_documents", "get_document",
                "create_note", "create_list", "update_document", "add_item", "update_item", "delete_item", "delete_document")));
        result.put("labels", false);
        result.put("internal_authority", currentSchema.authority);
        return result;
    }

    private JSONObject listDocuments(Schema s, Bundle extras) throws Exception {
        String type = optionalString(extras, "type", "all");
        if (!"all".equals(type) && !"note".equals(type) && !"list".equals(type)) throw validation("Invalid type.");
        int limit = boundedInt(extras, "limit", 50, 1, MAX_LIMIT);
        int offset = boundedInt(extras, "offset", 0, 0, 10_000);
        String query = optionalString(extras, "query", null);
        if (query != null && (query.length() == 0 || query.length() > 256)) throw validation("Invalid query.");
        Boolean pinned = optionalBoolean(extras, "pinned");
        boolean archived = optionalBoolean(extras, "include_archived") != null && optionalBoolean(extras, "include_archived");
        boolean trashed = optionalBoolean(extras, "include_trashed") != null && optionalBoolean(extras, "include_trashed");
        Set<Long> matchingItemParents = query == null ? null : matchingItemParents(s, query);

        StringBuilder where = new StringBuilder(s.treeDeleted + "=0");
        ArrayList<String> args = new ArrayList<>();
        if (!"all".equals(type)) { where.append(" AND ").append(s.treeType).append("=?"); args.add("note".equals(type) ? "0" : "1"); }
        if (!archived) where.append(" AND ").append(s.treeArchived).append("=0");
        if (!trashed) where.append(" AND ").append(s.treeTrashed).append("=0");
        if (pinned != null) { where.append(" AND ").append(s.treePinned).append("=?"); args.add(pinned ? "1" : "0"); }
        JSONArray documents = new JSONArray();
        Cursor cursor = internalQuery(s.treeUri(), null, where.toString(), args.toArray(new String[0]), s.treeUpdated + " DESC");
        try {
            int skipped = 0;
            while (cursor.moveToNext() && documents.length() < limit) {
                long rowId = longValue(cursor, s.treeId);
                if (query != null && !containsIgnoreCase(nullableValue(cursor, s.treeTitle), query)
                        && !matchingItemParents.contains(rowId)) continue;
                if (skipped++ < offset) continue;
                JSONObject row = summary(s, cursor);
                row.put("preview", preview(s, rowId));
                row.remove("row_id");
                documents.put(row);
            }
        } finally { cursor.close(); }
        return new JSONObject().put("documents", documents).put("limit", limit).put("offset", offset);
    }

    private JSONObject document(Schema s, String uuid) throws Exception {
        JSONObject result = documentWithRow(s, uuid);
        result.remove("row_id");
        return result;
    }

    private JSONObject documentWithRow(Schema s, String uuid) throws Exception {
        Cursor cursor = internalQuery(s.treeUri(), null, s.treeUuid + "=? AND " + s.treeDeleted + "=0", new String[]{uuid}, null);
        try {
            if (!cursor.moveToFirst()) throw new ApiException("NOT_FOUND", "Document was not found.");
            return documentFromCursor(s, cursor);
        } finally { cursor.close(); }
    }

    private JSONObject documentFromCursor(Schema s, Cursor cursor) throws Exception {
        JSONObject result = summary(s, cursor);
        long rowId = longValue(cursor, s.treeId);
        result.put("title", value(cursor, s.treeTitle));
        JSONObject metadata = new JSONObject();
        metadata.put("archived", boolValue(cursor, s.treeArchived));
        metadata.put("trashed", boolValue(cursor, s.treeTrashed));
        metadata.put("pinned", boolValue(cursor, s.treePinned));
        metadata.put("color", nullableValue(cursor, s.treeColor));
        result.put("metadata", metadata);
        JSONArray items = new JSONArray();
        Cursor itemCursor = internalQuery(s.itemUri(), null, s.itemParent + "=? AND " + s.itemDeleted + "=0",
                new String[]{Long.toString(rowId)}, s.itemOrder + " DESC");
        try {
            while (itemCursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", value(itemCursor, s.itemUuid));
                item.put("text", nullableValue(itemCursor, s.itemText));
                item.put("checked", boolValue(itemCursor, s.itemChecked));
                item.put("revision", longValue(itemCursor, s.itemUpdated));
                items.put(item);
            }
        } finally { itemCursor.close(); }
        if (intValue(cursor, s.treeType) == 0) result.put("text", items.length() == 0 ? "" : items.getJSONObject(0).getString("text"));
        else result.put("items", items);
        result.put("labels", new JSONArray());
        return result;
    }

    private JSONObject createDocument(Schema s, Bundle extras, int type) throws Exception {
        String title = optionalString(extras, "title", "");
        validateText(title, MAX_TITLE, "title");
        ArrayList<String> texts = type == 0 ? new ArrayList<>(Arrays.asList(optionalString(extras, "text", ""))) : itemTexts(extras);
        for (String text : texts) validateText(text, MAX_TEXT, "item");
        String color = optionalString(extras, "color", null);
        validateColor(color);
        Boolean pinned = optionalBoolean(extras, "pinned");
        Boolean archived = optionalBoolean(extras, "archived");
        long now = System.currentTimeMillis();
        long accountId = accountId(s);
        String documentUuid = keepUuid();
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(s.treeUri()).withValues(treeValues(s, accountId, documentUuid, title, type, pinned, archived, false, color, now)).build());
        for (int i = 0; i < texts.size(); i++) {
            ContentProviderOperation.Builder operation = ContentProviderOperation.newInsert(s.itemUri())
                    .withValues(itemValues(s, accountId, texts.get(i), false, (long) (texts.size() - i) * 1024L, now))
                    .withValueBackReference(s.itemParent, 0);
            operations.add(operation.build());
        }
        internalBatch(s.authority, operations);
        return document(s, documentUuid);
    }

    private JSONObject updateDocument(Schema s, Bundle extras) throws Exception {
        String uuid = requiredId(extras, "document_id");
        JSONObject existing = documentWithRow(s, uuid);
        checkRevision(extras, existing.getLong("revision"));
        ContentValues values = new ContentValues();
        if (has(extras, "title")) { String title = requiredString(extras, "title"); validateText(title, MAX_TITLE, "title"); values.put(s.treeTitle, title); }
        putBoolean(values, s.treePinned, optionalBoolean(extras, "pinned"));
        putBoolean(values, s.treeArchived, optionalBoolean(extras, "archived"));
        putBoolean(values, s.treeTrashed, optionalBoolean(extras, "trashed"));
        if (has(extras, "color")) { String color = optionalString(extras, "color", null); validateColor(color); putNullable(values, s.treeColor, color); }
        if (values.size() == 0 && !has(extras, "text")) throw validation("No document fields supplied.");
        if (values.size() > 0) touch(s, values);
        if (values.size() > 0) internalUpdate(entityUri(s.treeUri(), existing.getLong("row_id")), values, null, null);
        if (has(extras, "text")) {
            if (!"note".equals(existing.getString("type"))) throw validation("Text may only be set on notes.");
            String text = requiredString(extras, "text"); validateText(text, MAX_TEXT, "text");
            updateNoteText(s, existing.getLong("row_id"), text);
        }
        return document(s, uuid);
    }

    private JSONObject addItem(Schema s, Bundle extras) throws Exception {
        JSONObject document = documentWithRow(s, requiredId(extras, "document_id"));
        checkRevision(extras, document.getLong("revision"));
        if (!"list".equals(document.getString("type"))) throw validation("Items may only be added to lists.");
        String text = requiredString(extras, "text"); validateText(text, MAX_TEXT, "text");
        Boolean checked = optionalBoolean(extras, "checked");
        long order = nextItemOrder(s, document.getLong("row_id"));
        ContentValues values = itemValues(s, accountId(s), text, checked != null && checked, order, System.currentTimeMillis());
        values.put(s.itemParent, document.getLong("row_id"));
        internalInsert(s.itemUri(), values);
        return document(s, document.getString("id"));
    }

    private JSONObject updateItem(Schema s, Bundle extras) throws Exception {
        JSONObject document = documentWithRow(s, requiredId(extras, "document_id"));
        checkRevision(extras, document.getLong("revision"));
        String itemId = requiredId(extras, "item_id");
        Cursor cursor = internalQuery(s.itemUri(), null, s.itemUuid + "=? AND " + s.itemParent + "=? AND " + s.itemDeleted + "=0",
                new String[]{itemId, Long.toString(document.getLong("row_id"))}, null);
        long rowId;
        try { if (!cursor.moveToFirst()) throw new ApiException("NOT_FOUND", "Item was not found."); rowId = longValue(cursor, s.itemId); }
        finally { cursor.close(); }
        ContentValues values = new ContentValues();
        if (has(extras, "text")) { String text = requiredString(extras, "text"); validateText(text, MAX_TEXT, "text"); values.put(s.itemText, text); }
        putBoolean(values, s.itemChecked, optionalBoolean(extras, "checked"));
        if (values.size() == 0) throw validation("No item fields supplied.");
        touchItem(s, values);
        internalUpdate(entityUri(s.itemUri(), rowId), values, null, null);
        return document(s, document.getString("id"));
    }

    private JSONObject deleteItem(Schema s, Bundle extras) throws Exception {
        JSONObject document = documentWithRow(s, requiredId(extras, "document_id"));
        checkRevision(extras, document.getLong("revision"));
        String itemId = requiredId(extras, "item_id");
        Cursor cursor = internalQuery(s.itemUri(), new String[]{s.itemId}, s.itemUuid + "=? AND " + s.itemParent + "=? AND " + s.itemDeleted + "=0",
                new String[]{itemId, Long.toString(document.getLong("row_id"))}, null);
        long rowId;
        try { if (!cursor.moveToFirst()) throw new ApiException("NOT_FOUND", "Item was not found."); rowId = longValue(cursor, s.itemId); }
        finally { cursor.close(); }
        internalDelete(entityUri(s.itemUri(), rowId), null, null);
        return document(s, document.getString("id"));
    }

    private JSONObject deleteDocument(Schema s, Bundle extras) throws Exception {
        String uuid = requiredId(extras, "document_id");
        JSONObject document = documentWithRow(s, uuid);
        checkRevision(extras, document.getLong("revision"));
        Boolean permanent = optionalBoolean(extras, "permanent");
        if (permanent != null && permanent) {
            // Confirmed Keep endpoint shape for immediate tree deletion.
            internalDelete(s.treeUri().buildUpon().appendPath("delete_immediately")
                    .appendPath(Long.toString(document.getLong("row_id"))).build(), null, null);
            return new JSONObject().put("id", uuid).put("deleted", true).put("permanent", true);
        }
        ContentValues values = new ContentValues();
        values.put(s.treeTrashed, 1);
        touch(s, values);
        internalUpdate(entityUri(s.treeUri(), document.getLong("row_id")), values, null, null);
        return document(s, uuid);
    }

    private void updateNoteText(Schema s, long parentId, String text) throws Exception {
        Cursor cursor = internalQuery(s.itemUri(), new String[]{s.itemId}, s.itemParent + "=? AND " + s.itemDeleted + "=0", new String[]{Long.toString(parentId)}, s.itemOrder + " DESC");
        try {
            if (cursor.moveToFirst()) {
                ContentValues values = new ContentValues(); values.put(s.itemText, text); touchItem(s, values);
                internalUpdate(entityUri(s.itemUri(), longValue(cursor, s.itemId)), values, null, null);
            } else {
                ContentValues values = itemValues(s, accountId(s), text, false, nextItemOrder(s, parentId), System.currentTimeMillis());
                values.put(s.itemParent, parentId);
                internalInsert(s.itemUri(), values);
            }
        } finally { cursor.close(); }
    }

    private JSONObject summary(Schema s, Cursor cursor) throws JSONException {
        JSONObject row = new JSONObject();
        row.put("id", value(cursor, s.treeUuid));
        row.put("row_id", longValue(cursor, s.treeId));
        row.put("revision", longValue(cursor, s.treeUpdated));
        row.put("type", intValue(cursor, s.treeType) == 0 ? "note" : "list");
        row.put("title", nullableValue(cursor, s.treeTitle));
        return row;
    }

    private String preview(Schema s, long parentId) throws Exception {
        Cursor cursor = internalQuery(s.itemUri(), new String[]{s.itemText}, s.itemParent + "=? AND " + s.itemDeleted + "=0", new String[]{Long.toString(parentId)}, s.itemOrder + " DESC");
        try { return cursor.moveToFirst() ? truncate(nullableValue(cursor, s.itemText), MAX_PREVIEW) : ""; }
        finally { cursor.close(); }
    }

    private Set<Long> matchingItemParents(Schema s, String query) throws Exception {
        Set<Long> matches = new HashSet<>();
        Cursor cursor = internalQuery(s.itemUri(), new String[]{s.itemParent, s.itemText}, s.itemDeleted + "=0", null, null);
        try {
            while (cursor.moveToNext()) {
                if (containsIgnoreCase(nullableValue(cursor, s.itemText), query)) {
                    matches.add(longValue(cursor, s.itemParent));
                }
            }
        } finally { cursor.close(); }
        return matches;
    }

    private long accountId(Schema s) throws Exception {
        String where = s.accountDeleted == null ? null : s.accountDeleted + "=0";
        if (s.accountEnabled != null) where = where == null ? s.accountEnabled + "=1" : where + " AND " + s.accountEnabled + "=1";
        Cursor cursor = internalQuery(s.accountUri(), null, where, null, null);
        try { if (!cursor.moveToFirst()) throw new ApiException("UNAVAILABLE", "No enabled Keep account is available."); return longValue(cursor, s.accountId); }
        finally { cursor.close(); }
    }

    private ContentValues treeValues(Schema s, long accountId, String uuid, String title, int type, Boolean pinned, Boolean archived, boolean trashed, String color, long now) {
        ContentValues values = new ContentValues();
        values.put(s.treeAccount, accountId); values.put(s.treeUuid, uuid); values.put(s.treeTitle, title); values.put(s.treeType, type);
        values.put(s.treeParent, 0); values.put(s.treeOrder, now); values.put(s.treeDeleted, 0); values.put(s.treeTrashed, trashed ? 1 : 0);
        putBoolean(values, s.treePinned, pinned); putBoolean(values, s.treeArchived, archived); putNullable(values, s.treeColor, color); touch(s, values);
        putIfPresent(values, s.treeCreated, now); putIfPresent(values, s.treeEdited, now);
        return values;
    }

    private ContentValues itemValues(Schema s, long accountId, String text, boolean checked, long order, long now) {
        ContentValues values = new ContentValues();
        // KeepProvider generates list_item.uuid in its wire format when it is omitted.
        values.put(s.itemAccount, accountId); values.put(s.itemText, text); values.put(s.itemChecked, checked ? 1 : 0);
        values.put(s.itemOrder, order); values.put(s.itemDeleted, 0); touchItem(s, values); putIfPresent(values, s.itemCreated, now);
        return values;
    }

    private long nextItemOrder(Schema s, long parentId) throws Exception {
        Cursor cursor = internalQuery(s.itemUri(), new String[]{s.itemOrder}, s.itemParent + "=? AND " + s.itemDeleted + "=0", new String[]{Long.toString(parentId)}, s.itemOrder + " ASC");
        try {
            if (!cursor.moveToFirst()) return 1024L;
            long lowest = longValue(cursor, s.itemOrder);
            if (lowest <= Long.MIN_VALUE + 1024L) throw new ApiException("UNAVAILABLE", "List ordering range is exhausted.");
            return lowest - 1024L;
        } finally { cursor.close(); }
    }

    private Schema schema() throws Exception {
        Schema cached = schema;
        if (cached != null) return cached;
        synchronized (this) {
            if (schema != null) return schema;
            String authority = internalAuthority();
            Schema discovered = new Schema(authority, columns(Uri.parse("content://" + authority + "/tree_entity")), columns(Uri.parse("content://" + authority + "/list_item")), columns(Uri.parse("content://" + authority + "/account")));
            schema = discovered;
            return discovered;
        }
    }

    private String internalAuthority() throws Exception {
        String cached = internalAuthority;
        if (cached != null) return cached;
        Context context = getContext();
        ProviderInfo info = context.getPackageManager().getProviderInfo(new ComponentName(context.getPackageName(), KEEP_PROVIDER_CLASS), 0);
        if (info.authority == null || info.authority.length() == 0) throw new ApiException("UNAVAILABLE", "Keep provider authority is unavailable.");
        internalAuthority = info.authority;
        return info.authority;
    }

    private Set<String> columns(Uri uri) throws Exception {
        Cursor cursor = internalQuery(uri, null, "0", null, null);
        try { return new HashSet<>(Arrays.asList(cursor.getColumnNames())); }
        finally { cursor.close(); }
    }

    private Cursor internalQuery(final Uri uri, final String[] projection, final String selection, final String[] args, final String order) throws Exception { return withIdentity(new ResolverCall<Cursor>() { public Cursor call() { Cursor cursor = getContext().getContentResolver().query(uri, projection, selection, args, order); if (cursor == null) throw new IllegalStateException("Keep returned no cursor"); return cursor; } }); }
    private Uri internalInsert(final Uri uri, final ContentValues values) throws Exception { return withIdentity(new ResolverCall<Uri>() { public Uri call() { Uri result = getContext().getContentResolver().insert(uri, values); if (result == null) throw new IllegalStateException("Keep rejected insert"); return result; } }); }
    private int internalUpdate(final Uri uri, final ContentValues values, final String selection, final String[] args) throws Exception { return withIdentity(new ResolverCall<Integer>() { public Integer call() { return getContext().getContentResolver().update(uri, values, selection, args); } }); }
    private int internalDelete(final Uri uri, final String selection, final String[] args) throws Exception { return withIdentity(new ResolverCall<Integer>() { public Integer call() { return getContext().getContentResolver().delete(uri, selection, args); } }); }
    private ContentProviderResult[] internalBatch(final String authority, final ArrayList<ContentProviderOperation> operations) throws Exception { return withIdentity(new ResolverCall<ContentProviderResult[]>() { public ContentProviderResult[] call() throws Exception { return getContext().getContentResolver().applyBatch(authority, operations); } }); }
    private <T> T withIdentity(ResolverCall<T> call) throws Exception { long token = Binder.clearCallingIdentity(); try { return call.call(); } finally { Binder.restoreCallingIdentity(token); } }

    private interface ResolverCall<T> { T call() throws Exception; }

    /**
     * The entire access control for this provider. It is declared exported="true" with no
     * android:permission, and every method routes through call(), which consults this first.
     *
     * Matching is by package name against a compile-time set. That is adequate here but not
     * self-evidently so: a package name is only an identity while the real owner is installed,
     * since two apps cannot share one. Signature pinning would close that, at the cost of binding
     * this APK to a specific signing key — worth doing once the caller has a stable release key,
     * not while it is signed with a regenerable debug keystore.
     *
     * The rejection is logged with the package that was refused. Without it a stale allowlist is
     * indistinguishable from a broken provider from the caller's side: every method returns the
     * same FORBIDDEN, and finding the cause means decompiling the installed APK.
     */
    private boolean isAllowedCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return true;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages == null) {
            Logger.printDebug(() -> "Keep API refused uid " + Binder.getCallingUid() + ": no packages for uid");
            return false;
        }
        for (String packageName : packages) if (ALLOWED_PACKAGES.contains(packageName)) return true;
        final String refused = TextUtils.join(",", packages);
        Logger.printDebug(() -> "Keep API refused " + refused + "; allowed: " + TextUtils.join(",", ALLOWED_PACKAGES));
        return false;
    }

    private static Uri entityUri(Uri base, long rowId) { return base.buildUpon().appendPath(Long.toString(rowId)).build(); }
    private static void touch(Schema s, ContentValues values) { values.put(s.treeDirty, 1); putIfPresent(values, s.treeUpdated, System.currentTimeMillis()); }
    private static void touchItem(Schema s, ContentValues values) { values.put(s.itemDirty, 1); putIfPresent(values, s.itemUpdated, System.currentTimeMillis()); }
    private static void putBoolean(ContentValues values, String column, Boolean value) { if (value != null && column != null) values.put(column, value ? 1 : 0); }
    private static void putNullable(ContentValues values, String column, String value) { if (column != null) { if (value == null) values.putNull(column); else values.put(column, value); } }
    private static void putIfPresent(ContentValues values, String column, long value) { if (column != null) values.put(column, value); }
    private static boolean has(Bundle extras, String key) { return extras != null && extras.containsKey(key); }
    private static String requiredString(Bundle extras, String key) throws ApiException { String value = optionalString(extras, key, null); if (value == null) throw validation("Missing " + key + "."); return value; }
    private static String requiredId(Bundle extras, String key) throws ApiException { String value = requiredString(extras, key); if (value.length() > 200 || !value.matches("[A-Za-z0-9._-]+")) throw validation("Invalid " + key + "."); return value; }
    private static String optionalString(Bundle extras, String key, String fallback) throws ApiException { if (!has(extras, key)) return fallback; Object value = extras.get(key); if (!(value instanceof String)) throw validation("Invalid " + key + "."); return (String) value; }
    private static Boolean optionalBoolean(Bundle extras, String key) throws ApiException { if (!has(extras, key)) return null; Object value = extras.get(key); if (!(value instanceof Boolean)) throw validation("Invalid " + key + "."); return (Boolean) value; }
    private static int boundedInt(Bundle extras, String key, int fallback, int min, int max) throws ApiException { if (!has(extras, key)) return fallback; Object value = extras.get(key); if (!(value instanceof Integer) || (Integer) value < min || (Integer) value > max) throw validation("Invalid " + key + "."); return (Integer) value; }
    private static ArrayList<String> itemTexts(Bundle extras) throws Exception { Object raw = extras == null ? null : extras.get("items_json"); if (raw == null && extras != null) raw = extras.get("items"); JSONArray array; if (raw instanceof String) array = new JSONArray((String) raw); else if (raw instanceof ArrayList) { array = new JSONArray(); for (Object value : (ArrayList<?>) raw) array.put(value); } else throw validation("items_json must be a JSON array or ArrayList."); if (array.length() > MAX_ITEMS) throw validation("Too many items."); ArrayList<String> values = new ArrayList<>(); for (int i = 0; i < array.length(); i++) { Object value = array.get(i); if (!(value instanceof String)) throw validation("Invalid item."); values.add((String) value); } return values; }
    private static void validateText(String value, int max, String name) throws ApiException { if (value == null || value.length() > max) throw validation("Invalid " + name + "."); }
    private static void validateColor(String color) throws ApiException { if (color != null && !color.matches("[A-Z_]+")) throw validation("Invalid color."); }
    private static void checkRevision(Bundle extras, long current) throws ApiException { if (!has(extras, "expected_revision")) return; Object value = extras.get("expected_revision"); if (!(value instanceof Long) && !(value instanceof Integer)) throw validation("Invalid expected_revision."); if (((Number) value).longValue() != current) throw new ApiException("CONFLICT", "Document has changed."); }
    private static String value(Cursor cursor, String column) { return cursor.getString(cursor.getColumnIndexOrThrow(column)); }
    private static String nullableValue(Cursor cursor, String column) { int index = cursor.getColumnIndexOrThrow(column); return cursor.isNull(index) ? null : cursor.getString(index); }
    private static long longValue(Cursor cursor, String column) { return cursor.getLong(cursor.getColumnIndexOrThrow(column)); }
    private static int intValue(Cursor cursor, String column) { return cursor.getInt(cursor.getColumnIndexOrThrow(column)); }
    private static boolean boolValue(Cursor cursor, String column) { return cursor.getInt(cursor.getColumnIndexOrThrow(column)) != 0; }
    private static boolean containsIgnoreCase(String value, String query) { return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT)); }
    private static String truncate(String value, int max) { return value == null ? "" : value.length() <= max ? value : value.substring(0, max); }
    private static String keepUuid() {
        long now = System.currentTimeMillis();
        long previous;
        long time;
        do {
            previous = LAST_UUID_TIME.get();
            time = Math.max(now, previous + 1);
        } while (!LAST_UUID_TIME.compareAndSet(previous, time));
        return Long.toHexString(time) + "." + Long.toHexString(UUID.randomUUID().getLeastSignificantBits());
    }
    private static ApiException validation(String message) { return new ApiException("VALIDATION", message); }
    private static Bundle success(JSONObject result) { Bundle bundle = new Bundle(); bundle.putBoolean("ok", true); bundle.putInt("api_version", API_VERSION); bundle.putString("result_json", result.toString()); return bundle; }
    private static Bundle failure(String code, String message) { Bundle bundle = new Bundle(); bundle.putBoolean("ok", false); bundle.putInt("api_version", API_VERSION); bundle.putString("error_code", code); bundle.putString("error_message", message); return bundle; }

    private static final class ApiException extends Exception { final String code; ApiException(String code, String message) { super(message); this.code = code; } }

    private static final class Schema {
        final String authority, treeId, treeUuid, treeTitle, treeType, treeAccount, treeParent, treeOrder, treeDeleted, treeTrashed, treeArchived, treePinned, treeColor, treeUpdated, treeDirty, treeCreated, treeEdited;
        final String itemId, itemUuid, itemText, itemParent, itemAccount, itemOrder, itemDeleted, itemChecked, itemUpdated, itemDirty, itemCreated;
        final String accountId, accountEnabled, accountDeleted;
        Schema(String authority, Set<String> tree, Set<String> item, Set<String> account) throws ApiException {
            this.authority = authority;
            treeId = required(tree, "_id"); treeUuid = required(tree, "uuid"); treeTitle = required(tree, "title"); treeType = required(tree, "type"); treeAccount = required(tree, "account_id"); treeParent = required(tree, "parent_id"); treeOrder = required(tree, "order_in_parent"); treeDeleted = required(tree, "is_deleted"); treeTrashed = required(tree, "is_trashed"); treeArchived = required(tree, "is_archived"); treePinned = required(tree, "is_pinned"); treeColor = optional(tree, "color_name", "color"); treeUpdated = required(tree, "time_last_updated"); treeDirty = required(tree, "is_dirty"); treeCreated = optional(tree, "time_created"); treeEdited = optional(tree, "user_edited_timestamp");
            itemId = required(item, "_id"); itemUuid = required(item, "uuid"); itemText = required(item, "text", "content", "title"); itemParent = required(item, "list_parent_id", "parent_id", "tree_entity_id"); itemAccount = required(item, "account_id"); itemOrder = required(item, "order_in_parent"); itemDeleted = required(item, "is_deleted"); itemChecked = required(item, "is_checked", "checked"); itemUpdated = required(item, "time_last_updated"); itemDirty = required(item, "is_dirty"); itemCreated = optional(item, "time_created");
            accountId = required(account, "_id"); accountEnabled = optional(account, "is_keep_service_enabled", "is_enabled", "enabled"); accountDeleted = optional(account, "is_deleted");
        }
        Uri treeUri() { return Uri.parse("content://" + authority + "/tree_entity"); }
        Uri itemUri() { return Uri.parse("content://" + authority + "/list_item"); }
        Uri accountUri() { return Uri.parse("content://" + authority + "/account"); }
        private static String required(Set<String> columns, String... names) throws ApiException { String value = optional(columns, names); if (value == null) throw new ApiException("UNAVAILABLE", "This Keep version has an unsupported provider schema."); return value; }
        private static String optional(Set<String> columns, String... names) { for (String name : names) if (columns.contains(name)) return name; return null; }
    }
}
