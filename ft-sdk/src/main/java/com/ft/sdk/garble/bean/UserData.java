package com.ft.sdk.garble.bean;

import androidx.annotation.Nullable;

import com.ft.sdk.garble.manager.SingletonGson;
import com.ft.sdk.garble.utils.Constants;
import com.ft.sdk.garble.utils.LogUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashMap;

/**
 * BY huangDianHua
 * DATE:2020-01-06 18:13
 * User attributes supplied to {@link com.ft.sdk.FTSdk#bindRumUserData(UserData)}.
 */
public class UserData {
    private static final String TAG = Constants.LOG_TAG_PREFIX + "UserData";

    /**
     * User name
     */
    private String name;
    /**
     * User ID
     */
    private String id;
    /**
     * User email
     */
    private String email;

    /**
     * Set data extension key-value pairs
     */
    private HashMap<String, String> exts;

    /** Returns the display name, or null when unset. */
    public String getName() {
        return name;
    }

    /** Sets the display name to attach to RUM events for the bound user. */
    public void setName(String name) {
        this.name = name;
    }

    /** Returns the application-defined user ID, or null when unset. */
    public String getId() {
        return id;
    }

    /** Sets the ID used to identify the signed-in user across RUM events. */
    public void setId(String id) {
        this.id = id;
    }

    /** Returns the configured map of custom user attributes, or null when unset. */
    public HashMap<String, String> getExts() {
        return exts;
    }

    /** Returns the email address, or null when unset. */
    public String getEmail() {
        return email;
    }

    /** Sets the optional email address to include with the bound user. */
    public void setEmail(String email) {
        this.email = email;
    }

    /** Sets the string attributes to bind alongside the user ID, name and email. */
    public void setExts(HashMap<String, String> exts) {
        this.exts = exts;
    }

    /**
     * Convert {@link #exts} data from json string
     *
     * @param data json string data content
     */
    public void setExtsWithJsonString(String data) {
        try {
            Type type = new TypeToken<HashMap<String, String>>() {
            }.getType();
            exts = SingletonGson.getInstance().fromJson(data, type);
        } catch (Exception e) {
            LogUtils.e(TAG, LogUtils.getStackTraceString(e));
        }
    }


    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof UserData) {
            UserData temp = (UserData) obj;
            if (id == null || temp.id == null || !id.equals(temp.id)) {
                if (!(id == null && temp.id == null)) {
                    return false;
                }
            }

            if (name == null || temp.name == null || !name.equals(temp.name)) {
                if (!(name == null && temp.name == null)) {
                    return false;
                }
            }

            if (exts == null || temp.exts == null || !exts.toString().equals(temp.exts.toString())) {
                return exts == null && temp.exts == null;
            }
            return true;
        } else {
            return false;
        }
    }
}
