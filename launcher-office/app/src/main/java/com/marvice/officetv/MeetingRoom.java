package com.marvice.officetv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Meeting-room plumbing: the laptop's HDMI input, the TV's Cast name, and the room's sample schedule.
 * The Present tile calls {@link #present}: one press to the laptop when it is plugged in, else the
 * meeting-room screen with Mirror first (no cable stays in this room).
 */
final class MeetingRoom {
    private static final String TAG = "MeetingRoom";
    static final TimeZone OFFICE_TZ = TimeZone.getTimeZone("Asia/Kolkata");
    /** The TV app that plays inputs, opened by explicit component (spec: a preloaded app may also claim the URI). */
    static final ComponentName TV_APP =
            new ComponentName("com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.TurnkeyUiMainActivity");

    private MeetingRoom() {}

    static String laptopInput(Context ctx) {
        return ctx.getString(R.string.room_laptop_input);
    }

    /** HDMI hot-plug state of the laptop port: CONNECTED, DISCONNECTED, or -1 when the TV cannot say. */
    static int laptopState(Context ctx) {
        try {
            TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
            return tim == null ? -1 : tim.getInputState(laptopInput(ctx));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Switches to the laptop's HDMI port. Returns false when the TV app refused, so the caller can fall back. */
    static boolean openLaptop(Context ctx) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(laptopInput(ctx));
        Intent view = new Intent(Intent.ACTION_VIEW, uri).setComponent(TV_APP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(view);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "laptop input refused: " + e.getClass().getSimpleName());
            return false;
        }
    }

    /** The Present tile: straight to the laptop when its cable is in, else the room screen with Mirror first. */
    static void present(Context ctx) {
        if (laptopState(ctx) == TvInputManager.INPUT_STATE_CONNECTED && openLaptop(ctx)) return;
        ctx.startActivity(new Intent(ctx, MeetingRoomActivity.class)
                .putExtra(MeetingRoomActivity.EXTRA_MODE, MeetingRoomActivity.MODE_HUB)
                .putExtra(MeetingRoomActivity.EXTRA_NO_CABLE, true));
    }

    /** The name laptops and phones see for Chrome Cast (Settings > Device name). */
    static String castName(Context ctx) {
        String n = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null || n.trim().isEmpty() ? ctx.getString(R.string.room_cast_name_fallback) : n.trim();
    }

    /** One meeting in the room's sample schedule: start and end in millis, title only. */
    static final class Slot {
        final long start, end;
        final String title;
        Slot(long start, long end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }

    /** Today's boardroom bookings from the sample data ("HH:mm|minutes|title"), in time order. */
    static java.util.List<Slot> todaysBookings(Context ctx) {
        java.util.List<Slot> out = new java.util.ArrayList<>();
        for (String row : ctx.getResources().getStringArray(R.array.sample_room_bookings)) {
            String[] p = row.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                String[] hm = p[0].split(":");
                Calendar c = Calendar.getInstance(OFFICE_TZ);
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                long start = c.getTimeInMillis();
                out.add(new Slot(start, start + Integer.parseInt(p[1]) * 60_000L, p[2]));
            } catch (NumberFormatException e) {
                Log.w(TAG, "skipping a malformed booking");
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    /** The booking under way now, or null. */
    static Slot current(Context ctx, long now) {
        for (Slot s : todaysBookings(ctx)) if (s.start <= now && now < s.end) return s;
        return null;
    }

    /** The next booking that has not started, or null. */
    static Slot next(Context ctx, long now) {
        for (Slot s : todaysBookings(ctx)) if (s.start > now) return s;
        return null;
    }
}
