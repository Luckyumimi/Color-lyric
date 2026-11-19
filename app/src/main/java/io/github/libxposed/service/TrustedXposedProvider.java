package io.github.libxposed.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;

/** Keeps the API 102 provider contract while authenticating the system Binder caller. */
public final class TrustedXposedProvider extends ContentProvider {
    @Override public boolean onCreate() {
        // This app targets API 37. Keep the API 102 library's clear-notification behavior.
        RemotePreferences.shouldNotifyCleared = true;
        return true;
    }

    /** A failed commit can still change the library's local snapshot. Reconnect from the daemon. */
    public static SharedPreferences readPreferences(XposedService service, String group) throws RemoteException {
        return RemotePreferences.newInstance(service, group);
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"SendBinder".equals(method)) return null;
        int uid = Binder.getCallingUid();
        if (uid != 0 && uid != Process.SYSTEM_UID) {
            throw new SecurityException("Only the Xposed daemon can connect");
        }
        if (extras == null || extras.getBinder("binder") == null) return null;
        XposedServiceHelper.onBinderReceived(extras.getBinder("binder"));
        return new Bundle();
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
