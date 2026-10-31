package com.marvice.officetv;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Batch 1 placeholder for Dashboard, Present and Meetings: a calm full-screen card saying what comes later.
 * Back returns to the tile that opened it (Home keeps its own focus).
 */
public class OfficeScreenActivity extends Activity {
    static final String EXTRA_SCREEN = "screen";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String id = getIntent().getStringExtra(EXTRA_SCREEN);
        boolean known = false;
        for (String s : OfficeScreens.IDS) known |= s.equals(id);
        if (!known) id = OfficeScreens.IDS[0];
        String title = OfficeScreens.label(this, id);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getColor(R.color.office_bg));

        TextView h = new TextView(this);
        h.setText(title);
        h.setTextColor(getColor(R.color.office_text));
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        h.setTypeface(getResources().getFont(R.font.outfit_light));
        root.addView(h);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.office_placeholder, title));
        sub.setTextColor(getColor(R.color.office_text_2));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        sub.setTypeface(getResources().getFont(R.font.inter_regular));
        sub.setPadding(0, 24, 0, 0);
        root.addView(sub);
        setContentView(root);
    }
}
