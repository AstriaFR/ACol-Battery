package cn.astriafr.acobattery;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Native overlays follow the same short navigation rhythm as WebView pages. */
final class PageMotion {
    private static final String CLOSING="aco-page-closing";
    private PageMotion(){}
    private static void reset(View view){view.setAlpha(1);view.setTranslationX(0);}
    static boolean closing(View view){return CLOSING.equals(view.getTag());}
    static void enter(View view){
        view.animate().withEndAction(null).cancel();view.setTag(null);reset(view);view.setVisibility(View.VISIBLE);
        if(!ValueAnimator.areAnimatorsEnabled())return;
        float density=view.getResources().getDisplayMetrics().density;
        view.setAlpha(.65f);view.setTranslationX(20*density);
        view.animate().alpha(1).translationX(0).setDuration(220).setInterpolator(new PathInterpolator(.22f,.8f,.25f,1)).start();
    }
    static void hide(View view){
        if(view==null||CLOSING.equals(view.getTag()))return;
        view.animate().withEndAction(null).cancel();
        if(view.getVisibility()!=View.VISIBLE||!view.isAttachedToWindow()||!ValueAnimator.areAnimatorsEnabled()){
            view.setVisibility(View.GONE);reset(view);return;
        }
        view.setTag(CLOSING);
        view.animate().alpha(0).translationX(14*view.getResources().getDisplayMetrics().density).setDuration(150)
            .setInterpolator(new PathInterpolator(.22f,.8f,.25f,1)).withEndAction(()->{view.setVisibility(View.GONE);view.setTag(null);reset(view);}).start();
    }
}
