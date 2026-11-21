package app.purechat;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地键值存储：设置项与会话列表都以 JSON 字符串整体读写。
 *
 * <p>使用应用私有的 SharedPreferences，卸载应用即随之清除，不做任何云端同步。
 */
public final class Store {

    /** 存储文件名。 */
    private static final String FILE = "purechat";

    private final SharedPreferences prefs;

    Store(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 读取键值；不存在时返回空串。 */
    String get(String key) {
        if (key == null) {
            return "";
        }
        String value = prefs.getString(key, "");
        return value == null ? "" : value;
    }

    /** 写入键值；value 为 null 时移除该键。 */
    void set(String key, String value) {
        if (key == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (value == null || value.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
        editor.apply();
    }

    /** 清空全部本地数据。 */
    void clear() {
        prefs.edit().clear().apply();
    }
}
