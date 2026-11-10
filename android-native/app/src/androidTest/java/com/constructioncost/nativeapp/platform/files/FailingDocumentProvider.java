package com.constructioncost.nativeapp.platform.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// The standalone test APK provider cannot load Kotlin from the target APK.
public final class FailingDocumentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        if ("/failed.pdf".equals(uri.getPath())) return "application/pdf";
        if ("/unicode.txt".equals(uri.getPath())) return "text/plain";
        return null;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String path = uri.getPath();
        if (!java.util.Arrays.asList("/failed.pdf", "/unicode.txt", "/unnamed", "/large", "/denied").contains(path))
            throw new IllegalArgumentException("Unexpected test document");
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                row[index] = "/failed.pdf".equals(path) ? "interrupted.pdf" :
                    "/unicode.txt".equals(path) ? "Site notes \u2014 \u0cae\u0ca8\u0cc6 01.txt" : null;
            } else if (OpenableColumns.SIZE.equals(columns[index]) && "/failed.pdf".equals(path)) row[index] = 4096L;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, @NonNull String mode) throws FileNotFoundException {
        String path = uri.getPath();
        if ("/denied".equals(path)) throw new SecurityException("Synthetic provider access revoked");
        if (!java.util.Arrays.asList("/failed.pdf", "/unicode.txt", "/unnamed", "/large").contains(path) || !"r".equals(mode))
            throw new FileNotFoundException("Unexpected test document");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createReliablePipe(); }
        catch (IOException failure) { throw new FileNotFoundException(failure.getMessage()); }
        Thread writerThread = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                if ("/failed.pdf".equals(path)) {
                    output.write("%PDF-1.7\nsynthetic interrupted original".getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    pipe[1].closeWithError("injected SAF read failure");
                } else if ("/large".equals(path)) {
                    byte[] block = new byte[64 * 1024];
                    java.util.Arrays.fill(block, (byte) 42);
                    for (int index = 0; index < 208; index++) output.write(block);
                } else {
                    output.write("Synthetic provider document\n".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException failure) {
                try { pipe[1].closeWithError("injected SAF writer failure"); } catch (IOException ignored) { }
            }
        }, "failing-document-provider");
        writerThread.setDaemon(true);
        writerThread.start();
        return pipe[0];
    }
    @Override public Uri insert(@NonNull Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
