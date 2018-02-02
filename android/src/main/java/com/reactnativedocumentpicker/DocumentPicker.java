package com.reactnativedocumentpicker;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import android.content.ContentUris;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/**
 * @see <a href="https://developer.android.com/guide/topics/providers/document-provider.html">android documentation</a>
 */
public class DocumentPicker extends ReactContextBaseJavaModule implements ActivityEventListener {
    private static final String NAME = "RNDocumentPicker";
    private static final int READ_REQUEST_CODE = 41;

    private static class Fields {
        private static final String FILE_SIZE = "fileSize";
        private static final String FILE_NAME = "fileName";
        private static final String TYPE = "type";
    }

    private Callback callback;

    public DocumentPicker(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addActivityEventListener(this);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void show(ReadableMap args, Callback callback) {
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        } else {
            intent = new Intent(Intent.ACTION_PICK);
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        if (!args.isNull("filetype")) {
            ReadableArray filetypes = args.getArray("filetype");
            if (filetypes.size() > 0) {
                intent.setType(filetypes.getString(0));
            }
        }

        this.callback = callback;

        getReactApplicationContext().startActivityForResult(intent, READ_REQUEST_CODE, Bundle.EMPTY);
    }

    // removed @Override temporarily just to get it working on RN0.33 and RN0.32 - will remove
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        onActivityResult(requestCode, resultCode, data);
    }

    // removed @Override temporarily just to get it working on RN0.33 and RN0.32
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != READ_REQUEST_CODE)
            return;

        if (resultCode != Activity.RESULT_OK) {
            callback.invoke("Bad result code: " + resultCode, null);
            return;
        }

        if (data == null) {
            callback.invoke("No data", null);
            return;
        }

        try {
            Uri uri = data.getData();
            callback.invoke(null, toMapWithMetadata(uri));
        } catch (Exception e) {
            Log.e(NAME, "Failed to read", e);
            callback.invoke(e.getMessage(), null);
        }
    }

    private WritableMap toMapWithMetadata(Uri uri) {
        WritableMap map;
        if(uri.toString().startsWith("/")) {
            map = metaDataFromFile(new File(uri.toString()));
        } else if (uri.toString().startsWith("http")) {
            map = metaDataFromUri(uri);
        } else {
            map = metaDataFromContentResolver(uri);
        }

        map.putString("uri", this.getPath(getReactApplicationContext(), uri));

        return map;
    }

    /**
    * Get a file path from a Uri. This will get the the path for Storage Access
    * Framework Documents, as well as the _data field for the MediaStore and
    * other file-based ContentProviders.
    *
    * @param context The context.
    * @param uri The Uri to query.
    * @author paulburke
    */
    public static String getPath(final ReactApplicationContext context, final Uri uri) {

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[] {
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    /**
    * Get the value of the data column for this Uri. This is useful for
    * MediaStore Uris, and other file-based ContentProviders.
    *
    * @param context The context.
    * @param uri The Uri to query.
    * @param selection (Optional) Filter used in the query.
    * @param selectionArgs (Optional) Selection arguments used in the query.
    * @return The value of the _data column, which is typically a file path.
    */
    public static String getDataColumn(ReactApplicationContext context, Uri uri, String selection,
            String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }


    /**
    * @param uri The Uri to check.
    * @return Whether the Uri authority is ExternalStorageProvider.
    */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
    * @param uri The Uri to check.
    * @return Whether the Uri authority is DownloadsProvider.
    */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
    * @param uri The Uri to check.
    * @return Whether the Uri authority is MediaProvider.
    */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    private WritableMap metaDataFromUri(Uri uri) {
        WritableMap map = Arguments.createMap();

        File outputDir = getReactApplicationContext().getCacheDir();
        try {
            File downloaded = download(uri, outputDir);

            map.putInt(Fields.FILE_SIZE, (int) downloaded.length());
            map.putString(Fields.FILE_NAME, downloaded.getName());
            map.putString(Fields.TYPE, mimeTypeFromName(uri.toString()));
        } catch (IOException e) {
            Log.e("DocumentPicker", "Failed to download file", e);
        }

        return map;
    }

    private WritableMap metaDataFromFile(File file) {
        WritableMap map = Arguments.createMap();

        if(!file.exists())
            return map;

        map.putInt(Fields.FILE_SIZE, (int) file.length());
        map.putString(Fields.FILE_NAME, file.getName());
        map.putString(Fields.TYPE, mimeTypeFromName(file.getAbsolutePath()));

        return map;
    }

    private WritableMap metaDataFromContentResolver(Uri uri) {
        WritableMap map = Arguments.createMap();

        ContentResolver contentResolver = getReactApplicationContext().getContentResolver();

        map.putString(Fields.TYPE, contentResolver.getType(uri));

        Cursor cursor = contentResolver.query(uri, null, null, null, null, null);

        try {
            if (cursor != null && cursor.moveToFirst()) {

                map.putString(Fields.FILE_NAME, cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)));

                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (!cursor.isNull(sizeIndex)) {
                    String size = cursor.getString(sizeIndex);
                    if (size != null)
                        map.putInt(Fields.FILE_SIZE, Integer.valueOf(size));
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return map;
    }

    private static File download(Uri uri, File outputDir) throws IOException {
        File file = File.createTempFile("prefix", "extension", outputDir);

        URL url = new URL(uri.toString());

        ReadableByteChannel channel = Channels.newChannel(url.openStream());
        try{
            FileOutputStream stream = new FileOutputStream(file);

            try {
                stream.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
                return file;
            } finally {
                stream.close();
            }
        } finally {
            channel.close();
        }
    }

    private static String mimeTypeFromName(String absolutePath) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(absolutePath);
        if (extension != null) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        } else {
            return null;
        }
    }

    // Required for RN 0.30+ modules than implement ActivityEventListener
    public void onNewIntent(Intent intent) {
    }
}
