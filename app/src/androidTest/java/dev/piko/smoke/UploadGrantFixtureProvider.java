package dev.piko.smoke;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Runs under the test APK's UID, so the app must actually hold a URI grant. */
public final class UploadGrantFixtureProvider extends DocumentsProvider {
    public static final String AUTHORITY = "dev.piko.debug.test.upload-documents";
    private File fixture;

    @Override public boolean onCreate() {
        fixture = new File(getContext().getCacheDir(), "upload-grant-fixture.bin");
        try (FileOutputStream output = new FileOutputStream(fixture)) {
            output.write("fixture".getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            Object value = null;
            if (DocumentsContract.Root.COLUMN_ROOT_ID.equals(column)) value = "fixture";
            if (DocumentsContract.Root.COLUMN_DOCUMENT_ID.equals(column)) value = "root";
            if (DocumentsContract.Root.COLUMN_TITLE.equals(column)) value = "Upload permission fixture";
            if (DocumentsContract.Root.COLUMN_FLAGS.equals(column)) value = 0;
            row.add(value);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(documentColumns(projection));
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(documentColumns(projection));
        if ("root".equals(parentDocumentId)) addDocument(cursor, "file");
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        return "root".equals(parentDocumentId) && ("root".equals(documentId) || "file".equals(documentId));
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"file".equals(documentId) || !"r".equals(mode)) throw new FileNotFoundException(documentId);
        return ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private String[] documentColumns(String[] projection) {
        return projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
        };
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        boolean root = "root".equals(id);
        if (!root && !"file".equals(id)) throw new FileNotFoundException(id);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(column)) value = id;
            if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(column)) value = root ? "uploads" : "fixture.bin";
            if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(column)) value = root ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream";
            if (DocumentsContract.Document.COLUMN_SIZE.equals(column)) value = root ? 0L : fixture.length();
            if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(column)) value = 1L;
            if (DocumentsContract.Document.COLUMN_FLAGS.equals(column)) value = 0;
            row.add(value);
        }
    }
}
