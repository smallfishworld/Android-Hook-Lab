package dev.smallfish.hooklab.demo;

import android.app.Activity;
import android.os.Bundle;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Owned, deterministic test target: every button calls our own ImageProcessor.
 * No third-party target data, credentials, networking or hidden monitoring.
 */
public final class DemoActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(10, 17, 25);
    private static final int PANEL = Color.rgb(21, 31, 43);
    private static final int BORDER = Color.rgb(41, 56, 73);
    private static final int MINT = Color.rgb(103, 232, 192);
    private static final int PALE = Color.rgb(242, 246, 251);
    private static final int MUTED = Color.rgb(154, 171, 188);
    private static final int CORAL = Color.rgb(255, 170, 145);
    private final ImageProcessor processor = new ImageProcessor();
    private int callCount = 0;
    private int brightnessPercent = 25;
    private TextView output;
    private TextView sliderValue;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(27), dp(20), dp(30));
        scroll.addView(page);

        TextView overline = text("ANDROID HOOK LAB  /  SANDBOX", 11, MINT, true);
        page.addView(overline);
        page.addView(gap(10));
        page.addView(text("Demo Target", 30, PALE, true));
        page.addView(gap(8));
        page.addView(text("A controlled source of Java method calls for your Vector hooks. No private data is collected.", 13, MUTED, false));
        page.addView(gap(25));

        LinearLayout brightnessCard = card();
        brightnessCard.addView(text("01  /  IMAGE PROCESSOR", 11, MINT, true));
        brightnessCard.addView(gap(12));
        brightnessCard.addView(text("Brightness calls", 19, PALE, true));
        brightnessCard.addView(gap(9));
        brightnessCard.addView(text("Runs ImageProcessor.adjustBrightness(float,int) and emits a numeric result.", 12, MUTED, false));
        brightnessCard.addView(gap(12));
        sliderValue = text("Base brightness  ·  25%", 13, PALE, true);
        brightnessCard.addView(sliderValue);
        SeekBar brightness = new SeekBar(this);
        brightness.setMax(100);
        brightness.setProgress(brightnessPercent);
        brightness.setProgressTintList(ColorStateList.valueOf(MINT));
        brightness.setThumbTintList(ColorStateList.valueOf(MINT));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                brightnessPercent = value;
                sliderValue.setText("Base brightness  ·  " + value + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        brightnessCard.addView(brightness);
        Button run = button("RUN BRIGHTNESS TEST  ↗", true);
        run.setOnClickListener(v -> {
            float value = processor.adjustBrightness(brightnessPercent / 100.0f, 12);
            callCount++;
            output.setText("Call #" + callCount + "\nResult: " + value + " · delta +12%");
        });
        brightnessCard.addView(run);
        brightnessCard.addView(gap(9));
        Button intOverload = button("TEST INTEGER OVERLOAD", false);
        intOverload.setOnClickListener(v -> {
            int value = processor.adjustBrightness(100, 25);
            callCount++;
            output.setText("Call #" + callCount + "\\nInteger brightness: " + value);
        });
        brightnessCard.addView(intOverload);
        page.addView(brightnessCard);
        page.addView(gap(15));

        LinearLayout validationCard = card();
        validationCard.addView(text("02  /  VALIDATION", 11, MINT, true));
        validationCard.addView(gap(12));
        validationCard.addView(text("Normal & exception", 19, PALE, true));
        validationCard.addView(gap(9));
        validationCard.addView(text("Both controls invoke ImageProcessor.validateLevel(int); the second throws a handled exception.", 12, MUTED, false));
        validationCard.addView(gap(15));
        Button valid = button("VALIDATE LEVEL 42", false);
        valid.setOnClickListener(v -> {
            int result = processor.validateLevel(42);
            callCount++;
            output.setText("Call #" + callCount + "\nValidation passed: " + result);
        });
        validationCard.addView(valid);
        validationCard.addView(gap(9));
        Button exception = button("TRIGGER SAFE EXCEPTION", false);
        exception.setTextColor(CORAL);
        exception.setOnClickListener(v -> {
            try {
                processor.validateLevel(-1);
            } catch (IllegalArgumentException expected) {
                callCount++;
                output.setText("Call #" + callCount + "\nCaught IllegalArgumentException");
            }
        });
        validationCard.addView(exception);
        page.addView(validationCard);
        page.addView(gap(15));

        LinearLayout loadCard = card();
        loadCard.addView(text("03  /  CAPTURE LOAD", 11, MINT, true));
        loadCard.addView(gap(12));
        loadCard.addView(text("Burst of 100 calls", 19, PALE, true));
        loadCard.addView(gap(9));
        loadCard.addView(text("Generate 100 bounded numeric method calls on a worker thread. Useful for measuring event throughput.", 12, MUTED, false));
        loadCard.addView(gap(14));
        Button burst = button("RUN 100-CALL BURST", false);
        burst.setOnClickListener(v -> {
            burst.setEnabled(false);
            output.setText("Burst running...");
            new Thread(() -> {
                for (int i = 0; i < 100; ++i) {
                    processor.adjustBrightness(0.2f, i % 25);
                }
                runOnUiThread(() -> {
                    callCount += 100;
                    output.setText("Burst completed · 100 calls\nTotal attempts: " + callCount);
                    burst.setEnabled(true);
                });
            }, "hooklab-demo-burst").start();
        });
        loadCard.addView(burst);
        page.addView(loadCard);
        page.addView(gap(19));

        page.addView(text("LATEST DEMO ACTION", 11, MINT, true));
        page.addView(gap(8));
        output = text("Ready. Tap any test above, then switch to Hook Lab to inspect the intercepted calls.", 14, PALE, false);
        output.setBackground(box(Color.rgb(28, 42, 56), BORDER, 15));
        output.setPadding(dp(17), dp(18), dp(17), dp(18));
        page.addView(output);
        page.addView(gap(16));
        page.addView(text("Tip: enable the Android Hook Lab module in Vector, set scope to this Demo, then force-stop and relaunch this app.", 12, MUTED, false));
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private View gap(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return v;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(19), dp(17), dp(18));
        card.setBackground(box(PANEL, BORDER, 19));
        return card;
    }

    private TextView text(String message, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextColor(color);
        t.setTextSize(sizeSp);
        t.setLineSpacing(dp(2), 1.0f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? BACKGROUND : PALE);
        button.setBackground(box(primary ? MINT : Color.rgb(31, 47, 62),
            primary ? MINT : BORDER, 11));
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(46));
        button.setMinHeight(dp(46));
        return button;
    }
}
