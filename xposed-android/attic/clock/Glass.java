package dev.nebula.clock;

import android.graphics.*;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import java.util.WeakHashMap;

final class Glass {
    static final String[] NAMES = {"Frost", "Glacier", "Rose"};
    private static final int[] COLORS = {0xffcedfed, 0xff96c9ed, 0xffefbbce};
    private static final WeakHashMap<TextView, Glass> states = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> heights = new WeakHashMap<>();
    private float basePx, appliedPx, renderedEm;
    private int renderedTone = -1;
    private Bitmap wallpaper;
    private final int[] xy=new int[2];
    private int lastX=Integer.MIN_VALUE,lastY,rootW,rootH;
    static void backdrop(TextView v,Bitmap bitmap){
        Glass g=states.get(v);
        if(g==null || g.renderedTone<0)return;
        g.wallpaper=bitmap;
        g.lastX=Integer.MIN_VALUE;
        g.render(v,g.renderedEm,g.renderedTone);
    }

    static void paint(TextView digits, int percent, int tone) {
        Glass state = states.get(digits);
        if (state == null) { state = new Glass(); states.put(digits, state); }
        float px = digits.getTextSize();
        if (px <= 0) return;
        if (state.basePx == 0 || Math.abs(px - state.appliedPx) > .1f) state.basePx = px;
        float target = state.basePx * Math.max(75, Math.min(115, percent)) / 100f;
        resizeFixedHeight(digits, percent);
        if (digits.getParent() instanceof View) {
            View parent=(View)digits.getParent();
            if (parent.getId()!=View.NO_ID &&
                    "clock_area".equals(parent.getResources().getResourceEntryName(parent.getId())))
                resizeFixedHeight(parent,percent);
        }
        if (Math.abs(target - px) > .1f) digits.setTextSize(TypedValue.COMPLEX_UNIT_PX, target);
        state.appliedPx = digits.getTextSize();
        int tint = Math.max(0, Math.min(COLORS.length-1, tone));
        state.render(digits,target,tint);
    }
    private void render(TextView digits,float target,int tint) {
        digits.getLocationOnScreen(xy);
        int w=digits.getRootView().getWidth(),h=digits.getRootView().getHeight();
        if(renderedTone==tint && Math.abs(renderedEm-target)<.1f &&
                lastX==xy[0] && lastY==xy[1] && rootW==w && rootH==h)return;
        renderedTone=tint;renderedEm=target;
        lastX=xy[0];lastY=xy[1];rootW=w;rootH=h;
        if (Build.VERSION.SDK_INT >= 33) {
            digits.setRenderEffect(Frost.effect(this,target,COLORS[tint]));
        } else {
            digits.setTextColor(COLORS[tint]);
            digits.setShadowLayer(Math.max(1f,target*.025f),0,1,0xc0ffffff);
        }
    }

    private static void resizeFixedHeight(View view, int percent) {
        ViewGroup.LayoutParams lp=view.getLayoutParams();
        if(lp==null || lp.height<=0)return;
        int[] pair=heights.get(view);
        if(pair==null || pair[1]!=lp.height) {
            pair=new int[]{lp.height,lp.height};
            heights.put(view,pair);
        }
        pair[1]=Math.round(pair[0]*percent/100f);
        if(lp.height!=pair[1]){lp.height=pair[1];view.setLayoutParams(lp);}
    }

    private static class Frost {
        // Sample the glyph mask only. The translucent interior reveals the real wallpaper.
        static RenderEffect effect(Glass state,float em, int color) {
            RuntimeShader shader = new RuntimeShader(
                "uniform shader glyph; uniform shader scene; uniform float em; uniform float3 tint;" +
                "uniform float2 origin; uniform float stepPx; uniform float hasScene;" +
                "half4 main(float2 p) {" +
                "float a=glyph.eval(p).a, r=max(1.0,em*.013);" +
                "float2 x=float2(r,0), y=float2(0,r);" +
                "float l=glyph.eval(p-x).a, t=glyph.eval(p-y).a;" +
                "float h=glyph.eval(p+x).a, b=glyph.eval(p+y).a;" +
                "float blur=(4*a+2*(l+t+h+b)+glyph.eval(p-x-y).a+glyph.eval(p+x-y).a+" +
                "glyph.eval(p-x+y).a+glyph.eval(p+x+y).a)/16.0;" +
                "float rim=clamp(max(a-min(l,t),.48*(a-min(h,b)))*2.,0.,1.);" +
                "float band=clamp(.52+.38*cos(p.y/max(1.,em)*5.8),0.,1.);" +
                "float fill=a*(.40+.34*band); float alpha=max(fill,max(rim,.42*blur));" +
                "float2 q=p+origin; float2 dx=float2(stepPx,0),dy=dx.yx;" +
                "half4 bg=(4*scene.eval(q)+scene.eval(q+dx)+scene.eval(q-dx)+scene.eval(q+dy)+scene.eval(q-dy))*.125;" +
                "float3 body=mix(tint,mix(bg.rgb,tint,.46),hasScene*bg.a);" +
                "float3 rgb=mix(body,float3(1.),clamp(rim+.24*band,0.,1.));" +
                "return half4(rgb*alpha,alpha); }");
            shader.setFloatUniform("em",em);
            Bitmap image=state.wallpaper;
            Shader scene=new LinearGradient(0,0,1,1,color,color,Shader.TileMode.CLAMP);
            if(image!=null){
                BitmapShader bitmap=new BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                bitmap.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
                float scale=Math.max(state.rootW/(float)image.getWidth(),state.rootH/(float)image.getHeight());
                Matrix matrix=new Matrix();matrix.setScale(scale,scale);
                matrix.postTranslate((state.rootW-image.getWidth()*scale)*.5f,(state.rootH-image.getHeight()*scale)*.5f);
                bitmap.setLocalMatrix(matrix);scene=bitmap;
            }
            shader.setInputShader("scene",scene);
            shader.setFloatUniform("origin",(float)state.lastX,(float)state.lastY);
            shader.setFloatUniform("stepPx",Math.max(4f,em*.045f));
            shader.setFloatUniform("hasScene",image==null?0f:1f);
            shader.setFloatUniform("tint",Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f);
            return RenderEffect.createRuntimeShaderEffect(shader,"glyph");
        }
    }
}
