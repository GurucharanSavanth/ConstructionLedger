package com.constructioncost.nativeapp.platform.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// The standalone test APK provider cannot load Kotlin from the target APK.
public final class FailingDocumentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "/failed.pdf".equals(uri.getPath()) ? "application/pdf" : null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!"/failed.pdf".equals(uri.getPath())) throw new IllegalArgumentException("Unexpected test document");
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = "interrupted.pdf";
            else if (OpenableColumns.SIZE.equals(columns[index])) row[index] = 4096L;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"/failed.pdf".equals(uri.getPath()) || !"r".equals(mode)) throw new FileNotFoundException("Unexpected test document");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
        Thread writerThread = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write("%PDF-1.7\nsynthetic interrupted original".getBytes(StandardCharsets.US_ASCII));
                output.flush();
                pipe[1].closeWithError("injected SAF read failure");
            } catch (IOException failure) {
                try { pipe[1].closeWithError("injected SAF writer failure"); } catch (IOException ignored) { }
            }
        }, "failing-document-provider");
        writerThread.setDaemon(true);
        writerThread.start();
        return pipe[0];
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
