package com.example.final_term.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Private on-device copy so profile avatars render immediately between screens. */
public final class AvatarCache {
    private AvatarCache() {}

    private static File file(Context context, String uid) {
        String safeUid = uid == null ? "" : uid.replaceAll("[^A-Za-z0-9_-]", "_");
        return new File(context.getApplicationContext().getFilesDir(), "avatar_" + safeUid + ".jpg");
    }

    @Nullable
    public static Bitmap load(Context context, String uid) {
        File image = file(context, uid);
        return image.isFile() ? BitmapFactory.decodeFile(image.getAbsolutePath()) : null;
    }

    public static void save(Context context, String uid, byte[] jpeg) throws IOException {
        File target = file(context, uid);
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(jpeg);
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                temporary.delete();
                throw new IOException("AVATAR_CACHE_REPLACE_FAILED");
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("AVATAR_CACHE_SAVE_FAILED");
            }
        }
    }
}
