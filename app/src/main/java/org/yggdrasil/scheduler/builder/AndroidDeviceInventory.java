package org.yggdrasil.scheduler.builder;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Permission-light, privacy-minimized inventory of the host's execution capabilities.
 *
 * Deliberately excludes serial numbers, Android IDs, IMEI, phone numbers, account data,
 * location, Wi-Fi identifiers, installed-app inventories, and filesystem contents.
 */
public final class AndroidDeviceInventory {
    private final Context context;

    public AndroidDeviceInventory(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject collect() throws Exception {
        JSONObject result = new JSONObject();
        result.put("schema_version", 1);
        result.put("reported_at_epoch_ms", System.currentTimeMillis());
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("model", Build.MODEL);
        result.put("device", Build.DEVICE);
        result.put("android_release", Build.VERSION.RELEASE);
        result.put("api_level", Build.VERSION.SDK_INT);
        result.put("supported_abis", strings(Build.SUPPORTED_ABIS));
        result.put("available_processors", Runtime.getRuntime().availableProcessors());

        ActivityManager manager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) {
            manager.getMemoryInfo(memory);
            result.put("memory_total_bytes", memory.totalMem);
            result.put("memory_available_bytes", memory.availMem);
            result.put("memory_low", memory.lowMemory);
        } else {
            result.put("memory_status", "UNAVAILABLE");
        }

        File dataDirectory = Environment.getDataDirectory();
        StatFs storage = new StatFs(dataDirectory.getAbsolutePath());
        result.put("data_storage_total_bytes", storage.getTotalBytes());
        result.put("data_storage_available_bytes", storage.getAvailableBytes());

        result.put("collection_scope", "DEVICE_CAPABILITIES_ONLY_NO_PERSONAL_IDENTIFIERS");
        result.put("source", "ANDROID_PLATFORM_APIS");
        return result;
    }

    private static JSONArray strings(String[] values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            for (String value : values) array.put(value);
        }
        return array;
    }
}
