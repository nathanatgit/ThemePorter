package dev.nebula.clock;

import android.app.Activity;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

final class ResizeHandle extends View {
    final Activity editor;
    final View clock;
    final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    int percent,startPercent;
    float downY;
    boolean changed;
    final int[] rootXY=new int[2], clockXY=new int[2];

    static ResizeHandle install(Activity a) {
        Object area=XposedHelpers.getObjectField(a,"mClockContentView");
        ViewGroup root=a.findViewById(android.R.id.content);
        if (!(area instanceof View) || !(root instanceof FrameLayout)) return null;
        ResizeHandle h=new ResizeHandle(a,(View)area);
        root.addView(h,new FrameLayout.LayoutParams(h.dp(52),h.dp(44)));
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> h.position(root));
        h.post(() -> h.position(root));
        return h;
    }
    ResizeHandle(Activity a,View clock) {
        super(a);editor=a;this.clock=clock;
        Bundle b=a.getContentResolver().call(Options.URI,"read",null,null);
        percent=b==null?100:b.getInt("size",100);
        setContentDescription("Resize clock"); setFocusable(true);
    }
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void position(View root){
        android.widget.TextView digit=ClockHook.firstDigit(clock);
        if(digit==null || digit.getLayout()==null || !clock.isShown()){setVisibility(INVISIBLE);return;}
        digit.getLocationInWindow(clockXY);root.getLocationInWindow(rootXY);
        int right=clockXY[0]-rootXY[0]+digit.getCompoundPaddingLeft()
                + Math.round(digit.getLayout().getLineRight(0));
        int bottom=clockXY[1]-rootXY[1]+digit.getBaseline()+Math.round(digit.getPaint().descent());
        setX(Math.max(0,Math.min(root.getWidth()-getWidth(),right-dp(6))));
        setY(Math.max(0,Math.min(root.getHeight()-getHeight(),bottom-getHeight()/2)));
        setVisibility(VISIBLE);
    }
    protected void onDraw(Canvas c){
        float mid=getWidth()/2f, y=getHeight()/2f;
        pen.setColor(0xcc203342);c.drawRoundRect(mid-dp(22),y-dp(12),mid+dp(22),y+dp(12),dp(12),dp(12),pen);
        pen.setColor(0xffffffff);pen.setTextSize(dp(13));pen.setTextAlign(Paint.Align.CENTER);
        c.drawText(percent+"%",mid,y-(pen.ascent()+pen.descent())/2,pen);
    }
    public boolean onTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:
            downY=e.getRawY();startPercent=percent;getParent().requestDisallowInterceptTouchEvent(true);return true;
        case MotionEvent.ACTION_MOVE:
            setPercent(startPercent+Math.round((e.getRawY()-downY)*40/dp(120)));return true;
        case MotionEvent.ACTION_CANCEL:
            setPercent(startPercent);return true;
        case MotionEvent.ACTION_UP:
            performClick();return true;
        default:return false;
        }
    }
    public boolean performClick(){super.performClick();return true;}
    void setPercent(int value){
        int next=Math.max(75,Math.min(115,value));
        if(next==percent)return;
        percent=next;changed=true;ClockHook.refreshDigits(clock);invalidate();
        for(String name:new String[]{"mButtonApply","mButtonApplyLinkage"}){
            Object button=XposedHelpers.getObjectField(editor,name);
            if(button instanceof View)((View)button).setEnabled(true);
        }
    }
    void save(){
        if(!changed)return;
        Bundle data=new Bundle();data.putInt("size",percent);
        editor.getContentResolver().call(Options.URI,"saveSize",null,data);
        changed=false;
    }
}
