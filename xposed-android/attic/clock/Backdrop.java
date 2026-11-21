package dev.nebula.clock;

import android.app.Activity;
import android.app.WallpaperManager;
import android.graphics.*;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.XposedHelpers;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only the wallpaper layer is sampled. A small bitmap supplies a soft backdrop inside the glyphs. */
final class Backdrop {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static Bitmap cached;
    private static int cachedId=-1;
    static void load(TextView digits, Activity activity) {
        if(activity!=null && ClockHook.EDIT.equals(activity.getClass().getName())){
            View wallpaper=(View)XposedHelpers.getObjectField(activity,"mDepthBackWallPaper");
            if(wallpaper==null || wallpaper.getWidth()<1 || wallpaper.getHeight()<1)return;
            Bitmap image=Bitmap.createBitmap(Math.max(1,wallpaper.getWidth()/20),
                    Math.max(1,wallpaper.getHeight()/20),Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(image);
            c.scale(image.getWidth()/(float)wallpaper.getWidth(),image.getHeight()/(float)wallpaper.getHeight());
            try {wallpaper.draw(c);Glass.backdrop(digits,image);}
            catch(RuntimeException e){Log.d("NebulaClock","Wallpaper preview unavailable",e);}
            return;
        }
        worker.execute(() -> {
            try {
                WallpaperManager wm=WallpaperManager.getInstance(digits.getContext());
                int flag=WallpaperManager.FLAG_LOCK;
                int id=wm.getWallpaperId(flag);
                if(id<0){flag=WallpaperManager.FLAG_SYSTEM;id=wm.getWallpaperId(flag);}
                if(id<0)return;
                if(cachedId!=id || cached==null){
                    try(ParcelFileDescriptor fd=wm.getWallpaperFile(flag)){
                        if(fd==null)return;
                        BitmapFactory.Options opt=new BitmapFactory.Options();
                        opt.inSampleSize=16;
                        Bitmap result=BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(),null,opt);
                        if(result==null)return;
                        cached=result;cachedId=id;
                    }
                }
                Bitmap result=cached;
                digits.post(() -> {if(digits.isAttachedToWindow())Glass.backdrop(digits,result);});
            } catch(Exception e){Log.d("NebulaClock","Static wallpaper unavailable",e);}
        });
    }
}
