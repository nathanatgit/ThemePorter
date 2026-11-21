package dev.nebula.clock;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

public class Editor extends Activity {
    int size, tone;
    TextView digits, sizeLabel;
    LinearLayout panel, tones;
    FrameLayout preview;
    static final int WHITE = 0xfff4f6fa;
    int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    TextView label(String text, int sp) {
        TextView v = new TextView(this);
        v.setText(text); v.setTextColor(WHITE); v.setTextSize(sp); v.setGravity(Gravity.CENTER);
        return v;
    }
    TextView button(String name, int color, View.OnClickListener action) {
        TextView v = label(name, 15);
        v.setPadding(dp(16),0,dp(16),0); v.setBackground(shape(color,24));
        v.setOnClickListener(action); v.setFocusable(true);
        return v;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences p = Options.prefs(this);
        size = state == null ? p.getInt("size",100) : state.getInt("size");
        tone = state == null ? p.getInt("tone",0) : state.getInt("tone");
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff0b0d11);
        panel.setPadding(dp(18),dp(10),dp(18),dp(16));
        setContentView(panel);
        LinearLayout bar = new LinearLayout(this);
        panel.addView(bar,-1,dp(48));
        bar.addView(button("Cancel",0xff32343a,v -> finish()),-2,-1);
        bar.addView(label("Lock screen",18),new LinearLayout.LayoutParams(0,-1,1));
        bar.addView(button("Apply",0xff1269b7,v -> {
            p.edit().putInt("size",size).putInt("tone",tone).apply();
            getContentResolver().notifyChange(Options.URI,null);
            Toast.makeText(this,"Clock style saved",Toast.LENGTH_SHORT).show();
            finish();
        }),-2,-1);
        preview = new FrameLayout(this);
        LinearLayout.LayoutParams frame = new LinearLayout.LayoutParams(-1,0,1);
        frame.setMargins(dp(22),dp(18),dp(22),dp(16));
        panel.addView(preview,frame);
        GradientDrawable backdrop = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff11263a,0xff45687e,0xff182a36});
        backdrop.setCornerRadius(dp(26)); preview.setBackground(backdrop); preview.setClipToOutline(true);
        LinearLayout clock = new LinearLayout(this);
        clock.setGravity(Gravity.CENTER); clock.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        position.topMargin = dp(32); preview.addView(clock,position);
        TextClock date = new TextClock(this); date.setFormat12Hour("EEE, MMM d"); date.setFormat24Hour("EEE, MMM d");
        date.setTextColor(0xffdde6ef); date.setGravity(Gravity.CENTER); clock.addView(date);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm"); time.setFormat24Hour("HH:mm"); time.setTextSize(58);
        time.setIncludeFontPadding(false); time.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        time.setGravity(Gravity.CENTER); clock.addView(time); digits = time;
        digits.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> Glass.paint(digits,size,tone));
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL); sheet.setPadding(dp(16),dp(12),dp(16),dp(10));
        sheet.setBackground(shape(0xff202329,24)); panel.addView(sheet,-1,-2);
        TextView title = label("Clock finish",17); title.setGravity(Gravity.START);
        sheet.addView(title);
        tones = new LinearLayout(this); sheet.addView(tones,-1,dp(54));
        for (int i = 0; i < Glass.NAMES.length; i++) {
            final int index = i;
            TextView choice = button(Glass.NAMES[i],0xff343941,v -> { tone = index; refresh(); });
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0,dp(44),1);
            slot.setMargins(dp(2),dp(5),dp(2),0); tones.addView(choice,slot);
        }
        sizeLabel = label("",15); sizeLabel.setGravity(Gravity.START); sheet.addView(sizeLabel);
        SeekBar slider = new SeekBar(this); slider.setContentDescription("Clock size");
        slider.setMax(40); slider.setProgress(size-75); sheet.addView(slider,-1,dp(46));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b,int progress,boolean user) {
                size = progress + 75; refresh();
            }
        });
        refresh();
    }
    void refresh() {
        Glass.paint(digits,size,tone);
        sizeLabel.setText("Clock size  ·  " + size + "%");
        for (int i = 0; i < tones.getChildCount(); i++) {
            View v = tones.getChildAt(i); v.setSelected(i == tone);
            v.setBackground(shape(i == tone ? 0xff3c6283 : 0xff353a41,18));
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putInt("size",size); state.putInt("tone",tone);
    }
}
