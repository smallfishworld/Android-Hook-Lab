package dev.smallfish.hooklab.demo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class DemoActivity extends Activity {
    private final ImageProcessor imageProcessor = new ImageProcessor();
    private int counter = 0;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setPadding(38, 54, 38, 38);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView heading = new TextView(this);
        heading.setText("Hook Lab · Demo App");
        heading.setTextSize(24);
        root.addView(heading);

        TextView guidance = new TextView(this);
        guidance.setText("Activate Android Hook Lab in Vector, scope it to this demo, then force-stop and relaunch this app. Calls are generated only when you tap.");
        guidance.setPadding(0, 16, 0, 20);
        root.addView(guidance);

        TextView result = new TextView(this);
        result.setTextSize(16);
        result.setText("Waiting for a test call…");

        Button success = new Button(this);
        success.setText("Call adjustBrightness() + validateLevel()");
        success.setOnClickListener(view -> {
            counter++;
            float value = imageProcessor.adjustBrightness(0.25f, counter % 20);
            int validated = imageProcessor.validateLevel(counter % 101);
            result.setText("Call #" + counter + " · value = " + value + " · level = " + validated);
        });
        root.addView(success);

        Button failure = new Button(this);
        failure.setText("Trigger a controlled exception");
        failure.setOnClickListener(view -> {
            try {
                imageProcessor.validateLevel(-1);
            } catch (IllegalArgumentException expected) {
                result.setText("Caught expected IllegalArgumentException");
            }
        });
        root.addView(failure);
        root.addView(result);
        setContentView(root);
    }
}
