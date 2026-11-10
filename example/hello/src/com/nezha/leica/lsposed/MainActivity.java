package com.nezha.leica.lsposed;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Launcher activity. Programmatic UI (no layout XML), so it also works if a
 * module has no res/ directory at all; the strings come from res/values here
 * only to exercise the aapt2 resource + generated-R.java pipeline.
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(R.string.hello_body
                + "\n\npackage: " + getPackageName()
                + "\nentry class: " + HelloHookEntry.class.getName()
                + "\n\nOpen LSPosed Manager, enable this module and select scope"
                + " (e.g. com.miui.mediaeditor), then reboot.");
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(Color.DKGRAY);
        body.setPadding(0, pad, 0, 0);
        box.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        setContentView(scroll);
    }
}
