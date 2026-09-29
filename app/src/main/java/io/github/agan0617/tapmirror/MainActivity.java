package io.github.agan0617.tapmirror;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;

/**
 * 上面一條網址列＋「左右對調」開關，下面是 MirrorWebView。
 * 開關開著時，點畫面右邊等於點左邊、點左邊等於點右邊（拖曳、捲動、長按不受影響）。
 * ★ 把目前這頁存起來、≡ 列出已存的網頁切換（長按刪除）；開了已存的網頁後在同站翻頁，那筆網址會跟著更新。
 * 最後看的網址與開關狀態會記住，下次打開照舊；從 Chrome 用「分享」把網址丟過來也可以。
 */
public class MainActivity extends Activity {
    private static final String PREFS = "tapmirror";
    private static final String KEY_URL = "url";
    private static final String KEY_MIRROR = "mirror";
    private static final String KEY_BOOKMARKS = "bookmarks";

    private MirrorWebView web;
    private EditText urlBox;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;

        // ---- 上方：網址列＋開關 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (8 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));

        urlBox = new EditText(this);
        urlBox.setHint("網址");
        urlBox.setSingleLine(true);
        urlBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBox.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBox.setTextSize(14);
        urlBox.setSelectAllOnFocus(true);
        urlBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                go();
                return true;
            }
            return false;
        });
        bar.addView(urlBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(smallButton("前往", d, v -> go()));
        bar.addView(smallButton("★", d, v -> saveBookmark()));
        bar.addView(smallButton("≡", d, v -> showBookmarks()));

        Switch sw = new Switch(this);
        sw.setText("對調");
        sw.setTextSize(13);
        sw.setChecked(prefs.getBoolean(KEY_MIRROR, true));
        sw.setOnCheckedChangeListener((b, on) -> {
            web.setMirror(on);
            prefs.edit().putBoolean(KEY_MIRROR, on).apply();
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swLp.setMarginStart((int) (8 * d));
        bar.addView(sw, swLp);

        // ---- 下方：WebView ----
        web = new MirrorWebView(this);
        web.setMirror(sw.isChecked());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String scheme = req.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;   // 網頁都留在 App 裡
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                urlBox.setText(url);
                prefs.edit().putString(KEY_URL, url).apply();
            }

            /** 每次網址變動都會來（含網頁用 history.pushState 換網址、不重新載入的那種翻頁） */
            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                urlBox.setText(url);
                prefs.edit().putString(KEY_URL, url).apply();
                trackNavigation(url);
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String fromIntent = urlFromIntent(getIntent());
        if (fromIntent != null) {
            load(fromIntent);
        } else if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            String last = prefs.getString(KEY_URL, null);
            if (last != null) load(last);
            else urlBox.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null) load(url);
    }

    /** 從「用…開啟」（ACTION_VIEW）或「分享」（ACTION_SEND 的文字）挑出網址 */
    private static String urlFromIntent(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null) return null;
            Matcher m = Patterns.WEB_URL.matcher(text);
            if (m.find()) return m.group();
        }
        return null;
    }

    private Button smallButton(String text, float d, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (4 * d));
        b.setLayoutParams(lp);
        return b;
    }

    // ---- 已存網頁：SharedPreferences 裡一個 JSON 陣列 [{title,url}, ...] ----

    /**
     * 目前正在看的是哪一筆已存網頁（bookmarks 的 index，-1＝沒有）。
     * 開了已存網頁之後，在同一個網站裡翻頁就把那筆的網址更新成目前這頁，下次從 ≡ 打開會接著看；
     * 跳到別的網站、自己輸入新網址就停止追蹤。
     */
    private int activeBookmark = -1;

    private void trackNavigation(String url) {
        if (url == null || url.startsWith("about:") || url.startsWith("data:")) return;
        JSONArray arr = bookmarks();
        if (activeBookmark >= 0 && activeBookmark < arr.length()) {
            JSONObject b = arr.optJSONObject(activeBookmark);
            String old = b.optString("url");
            if (url.equals(old)) return;
            if (sameHost(old, url)) {
                try { b.put("url", url); } catch (JSONException ignored) { }
                saveBookmarks(arr);
                return;
            }
            activeBookmark = -1;   // 跑到別的網站去了
        }
        // 沒在追蹤：目前這頁若剛好就是某筆已存網址，接上去
        for (int i = 0; i < arr.length(); i++) {
            if (url.equals(arr.optJSONObject(i).optString("url"))) { activeBookmark = i; return; }
        }
    }

    private static boolean sameHost(String a, String b) {
        try {
            String ha = Uri.parse(a).getHost(), hb = Uri.parse(b).getHost();
            return ha != null && ha.equalsIgnoreCase(hb);
        } catch (Exception e) { return false; }
    }

    private JSONArray bookmarks() {
        try { return new JSONArray(prefs.getString(KEY_BOOKMARKS, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void saveBookmarks(JSONArray arr) {
        prefs.edit().putString(KEY_BOOKMARKS, arr.toString()).apply();
    }

    /** ★：把目前這頁存起來，名稱預設用網頁標題，可以改 */
    private void saveBookmark() {
        String url = web.getUrl();
        if (url == null || url.isEmpty() || url.startsWith("about:")) {
            Toast.makeText(this, "先打開一個網頁", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray arr = bookmarks();
        for (int i = 0; i < arr.length(); i++) {
            if (url.equals(arr.optJSONObject(i).optString("url"))) {
                activeBookmark = i;
                Toast.makeText(this, "這頁已經存過：" + arr.optJSONObject(i).optString("title") + "（翻頁會自動更新）", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        String title = web.getTitle();
        if (title == null || title.trim().isEmpty()) title = url;
        EditText nameBox = new EditText(this);
        nameBox.setText(title);
        nameBox.setSingleLine(true);
        nameBox.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        nameBox.setPadding(pad, nameBox.getPaddingTop(), pad, nameBox.getPaddingBottom());
        new AlertDialog.Builder(this)
                .setTitle("儲存這頁")
                .setMessage(url)
                .setView(nameBox)
                .setPositiveButton("儲存", (dlg, w) -> {
                    String name = nameBox.getText().toString().trim();
                    if (name.isEmpty()) name = url;
                    try {
                        arr.put(new JSONObject().put("title", name).put("url", url));
                    } catch (JSONException ignored) { }
                    saveBookmarks(arr);
                    activeBookmark = arr.length() - 1;
                    Toast.makeText(this, "已儲存：" + name, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** ≡：列出已存的網頁，點一下切換，長按刪除 */
    private void showBookmarks() {
        JSONArray arr = bookmarks();
        if (arr.length() == 0) {
            Toast.makeText(this, "還沒存任何網頁，按 ★ 儲存目前這頁", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] titles = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) titles[i] = arr.optJSONObject(i).optString("title");
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("已存的網頁（長按刪除）")
                .setItems(titles, (d, i) -> { activeBookmark = i; load(arr.optJSONObject(i).optString("url")); })
                .setNegativeButton("關閉", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getListView().setOnItemLongClickListener((parent, v, i, id) -> {
            new AlertDialog.Builder(this)
                    .setTitle("刪除「" + titles[i] + "」？")
                    .setMessage(arr.optJSONObject(i).optString("url"))
                    .setPositiveButton("刪除", (d2, w) -> {
                        arr.remove(i);
                        saveBookmarks(arr);
                        activeBookmark = -1;
                        dlg.dismiss();
                        Toast.makeText(this, "已刪除：" + titles[i], Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        }));
        dlg.show();
    }

    private void go() {
        String t = urlBox.getText().toString().trim();
        if (t.isEmpty()) return;
        if (!t.contains("://")) t = "https://" + t;
        activeBookmark = -1;
        load(t);
        urlBox.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(urlBox.getWindowToken(), 0);
        web.requestFocus();
    }

    private void load(String url) {
        urlBox.setText(url);
        web.loadUrl(url);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
