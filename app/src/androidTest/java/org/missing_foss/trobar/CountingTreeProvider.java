// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// A tree of documents over a plain directory that counts the queries made of
// it. The sync tests otherwise hand SyncEngine a file: folder, which never
// goes through the Storage Access Framework, so what a sync costs on a user's
// picked folder (one content-provider query per property read) is not visible
// to them. This puts a real tree URI in front of the engine.
//
// Three things about where it runs shape it:
//  - It is a plain ContentProvider answering the DocumentsContract URIs and
//    call() methods, not a DocumentsProvider: the platform refuses to attach a
//    DocumentsProvider that isn't protected by MANAGE_DOCUMENTS, which no app
//    can hold. DocumentFile and DocumentsContract only ever see the URIs.
//  - The system starts it in the TEST package's process, which has no Kotlin
//    standard library, hence Java and framework classes only.
//  - That process is not the app's, where the test code runs, and has its own
//    uid: the tree lives in this package's own cache directory, and the test
//    reaches it, and the query count, only through call().

package org.missing_foss.trobar;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class CountingTreeProvider extends ContentProvider {
    public static final String AUTHORITY = "org.missing_foss.trobar.test.tree";
    private static final String ROOT_ID = "root";

    // The platform's own call() names and extra, hidden in DocumentsContract;
    // createDocument, renameDocument and deleteDocument send exactly these.
    private static final String METHOD_CREATE = "android:createDocument";
    private static final String METHOD_RENAME = "android:renameDocument";
    private static final String METHOD_DELETE = "android:deleteDocument";
    private static final String EXTRA_URI = "uri";

    // The test's own calls.
    public static final String TEST_RESET = "test:reset";   // empty tree, count zero
    public static final String TEST_ADD = "test:add";       // arg: relative path; extra "size"
    public static final String TEST_COUNT = "test:count";   // returns "queries"
    public static final String TEST_ZERO = "test:zero";     // count zero, tree kept

    private static final String[] COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    };

    private final AtomicInteger queries = new AtomicInteger();
    private File base;

    public static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    @Override
    public boolean onCreate() {
        base = new File(getContext().getCacheDir(), "counting-tree");
        base.mkdirs();
        return true;
    }

    private File fileOf(String documentId) {
        return ROOT_ID.equals(documentId) ? base : new File(base, documentId.substring(ROOT_ID.length() + 1));
    }

    private String idOf(File file) {
        if (file.equals(base)) return ROOT_ID;
        return ROOT_ID + "/" + file.getAbsolutePath().substring(base.getAbsolutePath().length() + 1);
    }

    /** The document a URI names: tree/T/document/D[/children], document/D, or tree/T. */
    private static String documentIdOf(Uri uri) {
        List<String> segs = uri.getPathSegments();
        int i = segs.indexOf("document");
        return i >= 0 ? segs.get(i + 1) : segs.get(1);
    }

    // Only the columns asked for, in the order asked: DocumentFile asks for one
    // column and reads index 0, so a fixed column set answers the wrong thing.
    private void addRow(MatrixCursor cursor, String[] columns, File file) {
        boolean dir = file.isDirectory();
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String col : columns) {
            switch (col) {
                case Document.COLUMN_DOCUMENT_ID: row.add(idOf(file)); break;
                case Document.COLUMN_DISPLAY_NAME: row.add(file.equals(base) ? "root" : file.getName()); break;
                case Document.COLUMN_MIME_TYPE:
                    row.add(dir ? Document.MIME_TYPE_DIR : "application/octet-stream"); break;
                case Document.COLUMN_SIZE: row.add(dir ? null : file.length()); break;
                case Document.COLUMN_LAST_MODIFIED: row.add(file.lastModified()); break;
                case Document.COLUMN_FLAGS: row.add(dir
                    ? Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE
                    : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME);
                    break;
                default: row.add(null);
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        queries.incrementAndGet();
        File f = fileOf(documentIdOf(uri));
        String[] columns = projection != null ? projection : COLUMNS;
        MatrixCursor c = new MatrixCursor(columns);
        if ("children".equals(uri.getLastPathSegment())) {
            File[] children = f.listFiles();
            if (children != null) {
                Arrays.sort(children);
                for (File child : children) addRow(c, columns, child);
            }
        } else if (f.exists()) {
            addRow(c, columns, f);
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return fileOf(documentIdOf(uri)).isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileOf(documentIdOf(uri)), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    @SuppressWarnings("deprecation") // getParcelable(String) exists on every API level here
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        switch (method) {
            case TEST_RESET:
                deleteRecursively(base);
                base.mkdirs();
                queries.set(0);
                return out;
            case TEST_ZERO:
                queries.set(0);
                return out;
            case TEST_COUNT:
                out.putInt("queries", queries.get());
                return out;
            case TEST_ADD: {
                File f = new File(base, arg);
                f.getParentFile().mkdirs();
                try (FileOutputStream s = new FileOutputStream(f)) {
                    s.write(new byte[extras.getInt("size")]);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return out;
            }
            default:
                break;
        }
        Uri uri = extras == null ? null : (Uri) extras.getParcelable(EXTRA_URI);
        if (uri == null) return null;
        File f = fileOf(documentIdOf(uri));
        switch (method) {
            case METHOD_CREATE: {
                File child = new File(f, extras.getString(Document.COLUMN_DISPLAY_NAME));
                try {
                    if (Document.MIME_TYPE_DIR.equals(extras.getString(Document.COLUMN_MIME_TYPE))) child.mkdirs();
                    else child.createNewFile();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(uri, idOf(child)));
                return out;
            }
            case METHOD_RENAME: {
                File target = new File(f.getParentFile(), extras.getString(Document.COLUMN_DISPLAY_NAME));
                f.renameTo(target);
                out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(uri, idOf(target)));
                return out;
            }
            case METHOD_DELETE:
                deleteRecursively(f);
                return out;
            default:
                return null;
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
