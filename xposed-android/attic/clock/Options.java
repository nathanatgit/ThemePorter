package dev.nebula.clock;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

public class Options extends ContentProvider {
    static final Uri URI = Uri.parse("content://dev.nebula.clock.options/style");
    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("style", Context.MODE_PRIVATE);
    }
    public boolean onCreate() { return true; }
    public Bundle call(String method, String arg, Bundle extras) {
        if ("saveSize".equals(method)) {
            if (!"com.android.systemui".equals(getCallingPackage())) throw new SecurityException();
            int size = Math.max(75, Math.min(115, extras.getInt("size",100)));
            prefs(getContext()).edit().putInt("size",size).apply();
            getContext().getContentResolver().notifyChange(URI,null);
            return Bundle.EMPTY;
        }
        if (!"read".equals(method)) return null;
        SharedPreferences p = prefs(getContext());
        Bundle out = new Bundle();
        out.putInt("size", p.getInt("size", 100));
        out.putInt("tone", p.getInt("tone", 0));
        return out;
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
    public String getType(Uri u) { return null; }
    public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
}
