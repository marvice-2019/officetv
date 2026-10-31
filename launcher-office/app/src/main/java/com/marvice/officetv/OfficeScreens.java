package com.marvice.officetv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** The office screens that live inside the launcher and sit in the dock like apps (dock_apps office:<id>). */
final class OfficeScreens {
    static final String[] IDS = {"dashboard", "present", "meetings"};

    private OfficeScreens() {}

    static String label(Context ctx, String id) {
        switch (id) {
            case "dashboard": return ctx.getString(R.string.office_dashboard);
            case "present": return ctx.getString(R.string.office_present);
            default: return ctx.getString(R.string.office_meetings);
        }
    }

    static int icon(String id) {
        switch (id) {
            case "dashboard": return R.drawable.ic_office_dashboard;
            case "present": return R.drawable.ic_office_present;
            default: return R.drawable.ic_office_meetings;
        }
    }

    /** Neutral glow: these tiles belong to both companies, so neither company's accent. */
    static int ambient(Context ctx, String id) {
        return ctx.getColor(R.color.office_text_2);
    }

    /** A 16:9 banner drawn once: graphite surface, the symbol centred. Same size as app banners (320x180). */
    static Drawable banner(Context ctx, String id) {
        int w = 320, h = 180, side = 84;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(ctx.getColor(R.color.office_surface_hi));
        c.drawRect(new RectF(0, 0, w, h), p);
        Drawable icon = ctx.getDrawable(icon(id));
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(ctx.getColor(R.color.office_text));
            icon.setBounds((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2);
            icon.draw(c);
        }
        return new BitmapDrawable(ctx.getResources(), bmp);
    }
}
