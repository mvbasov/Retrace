package net.basov.retrace;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

// ----------------------------------------------------------------------
// The check of the map tile server
// ----------------------------------------------------------------------
//
// This screen shows the version and runs TileProbe. The menu of the list of
// tracks opens it. It stays until a test on Android 6 shows the result of
// the certificate check. See section 7 of doc/PLAN.md.
//
// The activity builds its views in Java. Thus the APK holds no layout file
// for this screen.
public final class TileProbeActivity extends ThemedActivity {

    private TextView resultView;
    private ImageView tileView;
    private Button probeButton;

    // The activity can close while the probe thread runs. The thread then
    // does not touch the views.
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildViews());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private ScrollView buildViews() {
        int pad = dp(16);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        column.addView(title);

        TextView version = new TextView(this);
        version.setText(getString(R.string.version, Labels.versionName(this)));
        column.addView(version);

        TextView intro = new TextView(this);
        intro.setText(R.string.intro);
        intro.setPadding(0, pad, 0, pad);
        column.addView(intro);

        probeButton = new Button(this);
        probeButton.setText(R.string.probe_button);
        probeButton.setOnClickListener(v -> startProbe());
        column.addView(probeButton);

        resultView = new TextView(this);
        resultView.setPadding(0, pad, 0, pad);
        resultView.setTextIsSelectable(true);
        column.addView(resultView);

        tileView = new ImageView(this);
        column.addView(tileView, new LinearLayout.LayoutParams(dp(256), dp(256)));

        TextView attribution = new TextView(this);
        attribution.setText(R.string.osm_attribution);
        attribution.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        column.addView(attribution);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void startProbe() {
        probeButton.setEnabled(false);
        resultView.setText(R.string.probe_running);
        tileView.setImageDrawable(null);

        final String userAgent = TileProbe.userAgent(Labels.versionName(this));
        Thread worker = new Thread(() -> {
            final TileProbe.Result result = TileProbe.run(userAgent);
            runOnUiThread(() -> {
                if (!destroyed) {
                    showResult(result);
                }
            });
        }, "tile-probe");
        worker.start();
    }

    private void showResult(TileProbe.Result result) {
        probeButton.setEnabled(true);

        StringBuilder text = new StringBuilder();
        if (result.error != null) {
            text.append(getString(R.string.probe_fail, describe(result.error)));
            if (result.isTlsFailure()) {
                text.append("\n\n").append(getString(R.string.probe_tls_fail));
            }
        } else if (result.httpCode >= 200 && result.httpCode < 300) {
            text.append(getString(R.string.probe_ok,
                    result.httpCode, result.body.length, result.millis));
            showTile(result.body, text);
        } else {
            text.append(getString(R.string.probe_http_error, result.httpCode, result.millis));
        }
        if (result.rootIssuer != null) {
            text.append("\n").append(getString(R.string.probe_issuer, result.rootIssuer));
        }
        text.append("\n").append(result.url);
        resultView.setText(text);
    }

    private void showTile(byte[] body, StringBuilder text) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(body, 0, body.length);
        if (bitmap == null) {
            text.append("\n").append(getString(R.string.probe_no_image));
        } else {
            tileView.setImageBitmap(bitmap);
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        String name = e.getClass().getSimpleName();
        return message == null ? name : name + ": " + message;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics()));
    }
}
