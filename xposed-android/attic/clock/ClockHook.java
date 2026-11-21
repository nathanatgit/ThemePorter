package dev.nebula.clock;

import android.app.Activity;
import android.database.ContentObserver;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.TextView;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.WeakHashMap;

public class ClockHook implements IXposedHookLoadPackage {
    static final String EDIT = "com.zte.mifavor.keyguard.personalclock.MyDepthLockScreenEditActivity";
    static final String UI = "com.android.systemui";
    private static final String SERVICE = "com.zte.mfvkeyguard";
    private final WeakHashMap<Activity, ResizeHandle> editors = new WeakHashMap<>();

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam app) {
        if (!app.packageName.equals(app.processName) ||
                !(UI.equals(app.packageName) || SERVICE.equals(app.packageName))) return;
        XposedHelpers.findAndHookMethod(TextView.class, "onAttachedToWindow", new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam param) {
                TextView digits = (TextView)param.thisObject;
                if (!(digits instanceof android.widget.TextClock ||
                        digits.getClass().getSimpleName().equals("TextClock"))) return;
                if (!isTimeField(digits)) return;
                Activity activity = activityOf(digits);
                if (activity != null && !EDIT.equals(activity.getClass().getName())) return;
                if (activity != null) {
                    Object main = XposedHelpers.getObjectField(activity, "mClockContentView");
                    if (!(main instanceof View) || !inside(digits, (View)main)) return;
                }
                new Binding(digits, activity).start();
            }
        });
        if (!UI.equals(app.packageName)) return;
        Class<?> editor = XposedHelpers.findClassIfExists(EDIT,app.classLoader);
        if (editor == null) return;
        XposedHelpers.findAndHookMethod(editor,"onCreate",Bundle.class,new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                Activity a=(Activity)p.thisObject;
                ResizeHandle handle=ResizeHandle.install(a);
                if (handle!=null) editors.put(a,handle);
            }
        });
        XposedHelpers.findAndHookMethod(editor,"clickApply",boolean.class,new XC_MethodHook() {
            protected void afterHookedMethod(MethodHookParam p) {
                ResizeHandle h=editors.get((Activity)p.thisObject);
                if (!p.hasThrowable() && h!=null) h.save();
            }
        });
        XposedHelpers.findAndHookMethod(editor,"onDestroy",new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam p) { editors.remove((Activity)p.thisObject); }
        });
    }
    static boolean inside(View v,View root) {
        for (ViewParent p=v.getParent();p instanceof View;p=p.getParent()) if(p==root)return true;
        return false;
    }
    static Activity activityOf(View v) {
        android.content.Context c=v.getContext();
        while(c instanceof android.content.ContextWrapper) {
            if(c instanceof Activity)return (Activity)c;
            android.content.Context next=((android.content.ContextWrapper)c).getBaseContext();
            if(c==next)break;
            c=next;
        }
        return null;
    }
    private boolean isTimeField(TextView v) {
        for (ViewParent p=v.getParent();p instanceof View;p=p.getParent()) {
            String n=p.getClass().getName();
            if (!(n.startsWith("com.zte.mifavor.keyguard.settings.LockScreenClock") ||
                  n.startsWith("com.zte.mfvkeyguard.clockView.single.LockScreenClock") ||
                  n.startsWith("com.zte.lockscreen.base.clockView.single.LockScreenClock"))) continue;
            for(String key:new String[]{"mClockView","mClockHours","mClockMinute","clockHourMinute"}) {
                java.lang.reflect.Field f=XposedHelpers.findFieldIfExists(p.getClass(),key);
                if (f!=null) try { if(f.get(p)==v)return true; } catch(IllegalAccessException ignored) {}
            }
        }
        return false;
    }
    static TextView firstDigit(View v) {
        if (v.getVisibility()!=View.VISIBLE) return null;
        if (XposedHelpers.getAdditionalInstanceField(v,"nebulaClockBinding")!=null) return (TextView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                TextView found=firstDigit(g.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }
    static void refreshDigits(View v) {
        Object b=XposedHelpers.getAdditionalInstanceField(v,"nebulaClockBinding");
        if(b instanceof Binding)((Binding)b).render();
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)refreshDigits(g.getChildAt(i));
        }
    }
    private class Binding extends ContentObserver implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
        final TextView digits;
        final Activity activity;
        int size=100,tone=0;
        boolean observing;
        Binding(TextView v,Activity a) { super(new Handler(Looper.getMainLooper())); digits=v;activity=a; }
        void start() {
            XposedHelpers.setAdditionalInstanceField(digits,"nebulaClockBinding",this);
            digits.addOnAttachStateChangeListener(this);
            digits.addOnLayoutChangeListener(this);
            try {
                digits.getContext().getContentResolver().registerContentObserver(Options.URI,false,this);
                observing=true;
                onChange(false);
            } catch(RuntimeException e) {Log.w("NebulaClock","Clock settings unavailable",e);}
            render();
            digits.post(() -> {if(digits.isAttachedToWindow())Backdrop.load(digits,activity);});
            Log.i("NebulaClock","Bound time field in "+android.app.Application.getProcessName()+" id="+digits.getId());
        }
        public void onChange(boolean self) {
            Bundle b=digits.getContext().getContentResolver().call(Options.URI,"read",null,null);
            if (b!=null){size=b.getInt("size",100);tone=b.getInt("tone",0);}
            render();
        }
        void render() {
            ResizeHandle h=editors.get(activity);
            if(h==null) for(ResizeHandle candidate:editors.values()) {
                if(inside(digits,candidate.clock)){h=candidate;break;}
            }
            Glass.paint(digits,h==null?size:h.percent,tone);
        }
        public void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob){render();}
        public void onViewAttachedToWindow(View v) {}
        public void onViewDetachedFromWindow(View v) {
            XposedHelpers.removeAdditionalInstanceField(v,"nebulaClockBinding");
            digits.removeOnAttachStateChangeListener(this);digits.removeOnLayoutChangeListener(this);
            if(observing) digits.getContext().getContentResolver().unregisterContentObserver(this);
        }
    }
}
