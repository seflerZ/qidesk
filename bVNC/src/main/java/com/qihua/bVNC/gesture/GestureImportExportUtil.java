package com.qihua.bVNC.gesture;

import android.content.Context;
import android.util.Log;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.undatech.opaque.util.FileUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class GestureImportExportUtil {
    private static final String TAG = "GestureImportExportUtil";
    private static final String GESTURES_DIR = "gestures";
    private static final String ACTIONS_DIR = "actions";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CONNECTIONS_FILE = "connections.json";
    private static final String CONNECTIONS_FILE_XML = "connections.xml";

    /**
     * Exports gestures for the given connection IDs to a ZIP output stream.
     * Also writes connection data (JSON/XML) to the ZIP.
     *
     * @param context The context
     * @param connectionIds Array of connection IDs to export gestures for
     * @param connectionDataFileName Filename for connection data in ZIP (e.g., "connections.json" or "connections.xml")
     * @param connectionData InputStream of connection data to write to ZIP
     * @param out OutputStream to write ZIP to
     */
    public static void exportGesturesAndConnections(Context context, String[] connectionIds,
            String connectionDataFileName, InputStream connectionData, OutputStream out) throws IOException {
        Log.d(TAG, "Exporting gestures for " + connectionIds.length + " connections");

        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out));

        // Write connection data
        if (connectionData != null) {
            ZipEntry connEntry = new ZipEntry(connectionDataFileName);
            zos.putNextEntry(connEntry);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = connectionData.read(buffer)) != -1) {
                zos.write(buffer, 0, len);
            }
            zos.closeEntry();
        }

        // Create manifest mapping original connId -> gesture filenames
        Map<String, GestureFiles> manifest = new HashMap<>();

        // Write gesture files
        File gesturesDir = context.getDir(GESTURES_DIR, Context.MODE_PRIVATE);
        File actionsDir = context.getDir(ACTIONS_DIR, Context.MODE_PRIVATE);

        for (String connId : connectionIds) {
            File gestureFile = new File(gesturesDir, connId + "_gestures.dat");
            File actionFile = new File(actionsDir, connId + "_actions.dat");

            GestureFiles files = new GestureFiles();
            if (gestureFile.exists()) {
                String entryName = GESTURES_DIR + "/" + connId + "_gestures.dat";
                files.gestureFile = entryName;
                addFileToZip(zos, gestureFile, entryName);
            }
            if (actionFile.exists()) {
                String entryName = ACTIONS_DIR + "/" + connId + "_actions.dat";
                files.actionFile = entryName;
                addFileToZip(zos, actionFile, entryName);
            }
            if (files.gestureFile != null || files.actionFile != null) {
                manifest.put(connId, files);
            }
        }

        // Write manifest
        ZipEntry manifestEntry = new ZipEntry(MANIFEST_FILE);
        zos.putNextEntry(manifestEntry);
        Writer writer = new OutputStreamWriter(zos);
        writer.write(JSON.toJSONString(manifest));
        writer.flush();
        zos.closeEntry();

        zos.finish();
        zos.close();
        Log.d(TAG, "Export completed");
    }

    private static void addFileToZip(ZipOutputStream zos, File file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                zos.write(buffer, 0, len);
            }
        }
        zos.closeEntry();
    }

    /**
     * Imports gestures from a ZIP input stream, mapping old connection IDs to new ones.
     *
     * @param context The context
     * @param oldToNewIdMap Map of old connection ID -> new connection ID
     * @param connectionDataFileName Filename expected for connection data (e.g., "connections.json" or "connections.xml")
     * @param in InputStream to read ZIP from
     * @return InputStream of connection data extracted from ZIP (for further processing)
     */
    public static InputStream importGesturesAndConnections(Context context,
            Map<String, String> oldToNewIdMap, String connectionDataFileName, InputStream in) throws IOException {
        Log.d(TAG, "Importing gestures, mapping " + oldToNewIdMap.size() + " connections");

        // First, extract the ZIP to temp files
        File tempDir = new File(context.getCacheDir(), "gesture_import_temp");
        if (tempDir.exists()) {
            deleteDirectoryRecursively(tempDir);
        }
        tempDir.mkdirs();

        Map<String, GestureFiles> manifest = extractZipToDir(in, tempDir);

        // Read connection data stream
        File connDataFile = new File(tempDir, connectionDataFileName);
        FileInputStream connDataStream = new FileInputStream(connDataFile);

        // Process gestures
        File gesturesDir = context.getDir(GESTURES_DIR, Context.MODE_PRIVATE);
        File actionsDir = context.getDir(ACTIONS_DIR, Context.MODE_PRIVATE);

        for (Map.Entry<String, String> entry : oldToNewIdMap.entrySet()) {
            String oldId = entry.getKey();
            String newId = entry.getValue();

            GestureFiles files = manifest.get(oldId);
            if (files == null) continue;

            // Copy gesture file with new ID
            if (files.gestureFile != null) {
                File src = new File(tempDir, files.gestureFile);
                File dest = new File(gesturesDir, newId + "_gestures.dat");
                FileUtils.copyFile(src, dest.getPath());
            }

            // Copy action file with new ID
            if (files.actionFile != null) {
                File src = new File(tempDir, files.actionFile);
                File dest = new File(actionsDir, newId + "_actions.dat");
                FileUtils.copyFile(src, dest.getPath());
            }
        }

        // Clean up temp dir
        deleteDirectoryRecursively(tempDir);

        Log.d(TAG, "Import completed");
        return connDataStream;
    }

    /**
     * Imports gestures by matching old connections to new ones using positional matching.
     * The manifest entries are assumed to be in the same order as the connections in newConnections.
     * This is used for fresh imports where existingBefore is empty.
     *
     * @param context The context
     * @param newConnections Map of new connection ID -> Connection object (ordered by insertion)
     * @param connectionDataFileName Filename of connection data in ZIP
     * @param in InputStream to read ZIP from
     * @return InputStream of connection data extracted from ZIP (for further processing)
     */
    public static InputStream importGesturesAndConnectionsByContent(Context context,
            Map<String, com.undatech.opaque.Connection> newConnections,
            String connectionDataFileName, InputStream in) throws IOException {
        Log.d(TAG, "Importing gestures by positional matching for " + newConnections.size() + " connections");

        // First, extract the ZIP to temp files
        File tempDir = new File(context.getCacheDir(), "gesture_import_temp");
        if (tempDir.exists()) {
            deleteDirectoryRecursively(tempDir);
        }
        tempDir.mkdirs();

        Map<String, GestureFiles> manifest = extractZipToDir(in, tempDir);

        // Read connection data stream
        File connDataFile = new File(tempDir, connectionDataFileName);
        FileInputStream connDataStream = new FileInputStream(connDataFile);

        // Build ordered list of new connection IDs
        List<String> orderedNewIds = new java.util.ArrayList<>(newConnections.keySet());

        // Process gestures - match manifest entries to new connections by position
        File gesturesDir = context.getDir(GESTURES_DIR, Context.MODE_PRIVATE);
        File actionsDir = context.getDir(ACTIONS_DIR, Context.MODE_PRIVATE);

        int mappedCount = 0;
        int manifestIndex = 0;
        for (Map.Entry<String, GestureFiles> manifestEntry : manifest.entrySet()) {
            GestureFiles files = manifestEntry.getValue();
            if (files.gestureFile == null && files.actionFile == null) continue;

            if (manifestIndex < orderedNewIds.size()) {
                String newId = orderedNewIds.get(manifestIndex);

                // Copy gesture file with new ID
                if (files.gestureFile != null) {
                    File src = new File(tempDir, files.gestureFile);
                    File dest = new File(gesturesDir, newId + "_gestures.dat");
                    FileUtils.copyFile(src, dest.getPath());
                    Log.d(TAG, "Copied gesture file to " + dest.getPath());
                }

                // Copy action file with new ID
                if (files.actionFile != null) {
                    File src = new File(tempDir, files.actionFile);
                    File dest = new File(actionsDir, newId + "_actions.dat");
                    FileUtils.copyFile(src, dest.getPath());
                    Log.d(TAG, "Copied action file to " + dest.getPath());
                }

                mappedCount++;
                manifestIndex++;
            } else {
                Log.w(TAG, "More gesture entries than connections, skipping excess");
                break;
            }
        }

        // Clean up temp dir
        deleteDirectoryRecursively(tempDir);

        Log.d(TAG, "Import completed, mapped " + mappedCount + " gesture sets");
        return connDataStream;
    }

    /**
     * Reads the manifest from an existing ZIP without fully extracting.
     * Used to get the list of connection IDs that have gestures in the export.
     */
    public static Map<String, GestureFiles> readManifestFromZip(InputStream zipIn) throws IOException {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zipIn));
        Map<String, GestureFiles> manifest = null;

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (MANIFEST_FILE.equals(entry.getName())) {
                String json = readStreamToString(zis);
                manifest = JSON.parseObject(json, new TypeReference<Map<String, GestureFiles>>() {}.getType());
                break;
            }
        }
        zis.close();
        return manifest != null ? manifest : new HashMap<>();
    }

    public static Map<String, GestureFiles> extractZipToDir(InputStream in, File destDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in));
        Map<String, GestureFiles> manifest = new HashMap<>();

        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(destDir, entry.getName());

            // Create parent directories
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
            zis.closeEntry();

            // Parse manifest as we extract
            if (MANIFEST_FILE.equals(entry.getName())) {
                String json = readStreamToString(new FileInputStream(outFile));
                manifest = JSON.parseObject(json, new TypeReference<Map<String, GestureFiles>>() {}.getType());
            }
        }
        zis.close();
        return manifest;
    }

    private static String readStreamToString(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, len));
        }
        return sb.toString();
    }

    private static void deleteDirectoryRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursively(child);
                }
            }
        }
        file.delete();
    }

    public static class GestureFiles {
        public String gestureFile;
        public String actionFile;
    }
}