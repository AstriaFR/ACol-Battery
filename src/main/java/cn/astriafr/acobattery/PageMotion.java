package cn.astriafr.acobattery;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/** Native overlays follow the same short navigation rhythm as WebView pages. */
final class PageMotion {
    private static final PathInterpolator EASE=new PathInterpolator(.2f,.75f,.25f,1);
    private PageMotion(){}
    private static void slideIn(View view,int offsetDp,int duration){
        view.animate().cancel();
        if(!ValueAnimator.areAnimatorsEnabled()){view.setTranslationX(0);return;}
        view.setTranslationX(offsetDp*view.getResources().getDisplayMetrics().density);
        view.animate().translationX(0).setDuration(duration).setInterpolator(EASE).start();
    }
    static void enter(View view){
        view.setVisibility(View.VISIBLE);
        slideIn(view,8,135);
    }
    static void hide(View view){
        if(view==null||view.getVisibility()!=View.VISIBLE)return;
        view.animate().cancel();
        view.setVisibility(View.GONE);view.setTranslationX(0);
        if(!view.isAttachedToWindow()||!(view.getParent() instanceof ViewGroup))return;
        ViewGroup host=(ViewGroup)view.getParent();
        int removed=host.indexOfChild(view);
        for(int i=host.getChildCount()-1;i>=0;i--){
            View child=host.getChildAt(i);
            if(child.getVisibility()!=View.VISIBLE)continue;
            if(i<removed)slideIn(child,-6,110);
            break;
        }
    }
}
