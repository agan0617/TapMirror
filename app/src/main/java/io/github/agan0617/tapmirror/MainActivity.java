package io.github.agan0617.tapmirror;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.Gravity;
import android.view.KeyEvent;
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

import java.util.regex.Matcher;

/**
 * 上面一條網址列＋「左右對調」開關，下面是 MirrorWebView。
 * 開關開著時，點畫面右邊等於點左邊、點左邊等於點右邊（拖曳、捲動、長按不受影響）。
 * 最後看的網址與開關狀態會記住，下次打開照舊；從 Chrome 用「分享」把網址丟過來也可以。
 */
public class MainActivity extends Activity {
    private static final String PREFS = "tapmirror";
    private static final String KEY_URL = "url";
    private static final String KEY_MIRROR = "mirror";

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

        Button goBtn = new Button(this);
        goBtn.setText("前往");
        goBtn.setTextSize(13);
        goBtn.setOnClickListener(v -> go());
        bar.addView(goBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

    private void go() {
        String t = urlBox.getText().toString().trim();
        if (t.isEmpty()) return;
        if (!t.contains("://")) t = "https://" + t;
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
