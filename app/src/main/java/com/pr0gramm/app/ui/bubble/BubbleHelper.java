package com.pr0gramm.app.ui.bubble;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.v7.widget.RecyclerView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.google.common.base.Optional;
import com.jakewharton.rxbinding.view.RxView;
import com.pr0gramm.app.BuildConfig;
import com.pr0gramm.app.R;
import com.pr0gramm.app.util.AndroidUtility;
import com.trello.rxlifecycle.RxLifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action1;
import rx.functions.Func0;

import static com.pr0gramm.app.Dagger.appComponent;
import static com.pr0gramm.app.util.AndroidUtility.atLeast;
import static com.pr0gramm.app.util.AndroidUtility.endAction;
import static com.pr0gramm.app.util.AndroidUtility.removeView;

/**
 * Builds a bubble that is positioned somewhere.
 */
public class BubbleHelper {
    private final View target;
    private String text = "no text";
    private int gravity = Gravity.LEFT;
    private ViewGroup root;

    private String hintName;
    private String requireShown;

    public BubbleHelper(View target) {
        this.target = target;
    }

    public BubbleHelper text(String text) {
        this.text = text;
        return this;
    }

    public BubbleHelper text(int text) {
        return text(target.getContext().getString(text));
    }

    public BubbleHelper gravity(int gravity) {
        this.gravity = gravity;
        return this;
    }

    public BubbleHelper root(ViewGroup root) {
        this.root = root;
        return this;
    }

    public BubbleHelper hintName(String name) {
        this.hintName = name;
        return this;
    }

    public BubbleHelper requireShown(String name) {
        this.requireShown = name;
        return this;
    }

    @Nullable
    public BubbleView show() {
        return show(this);
    }

    @Nullable
    private static BubbleView show(BubbleHelper builder) {
        Context context = builder.target.getContext();
        if (!wouldShow(context, builder.hintName)) {
            // we have shown this hint in the past.
            return null;
        }

        if (builder.requireShown != null && wouldShow(context, builder.requireShown)) {
            // the required parent was not shown.
            return null;
        }


        ViewGroup root = builder.root != null ? builder.root : rootOf(builder.target);
        BubbleView bubble = createBubbleView(root, builder.text, builder.gravity);

        // react to changes to the layout of the root.
        RxView.globalLayouts(root)
                /* add a timer, if we mess up something */
                .mergeWith(Observable
                        .interval(50, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread())
                        .map(ev -> (Void) null))

                /* look for scroll events */
                .mergeWith(parentScrolls(builder.target))

                /* update if bubble layout changes */
                .mergeWith(RxView.layoutChanges(bubble))

                /* calculate target position */
                .startWith((Void) null)
                .map(ev -> calculateBubblePosition(root, builder.target, bubble, builder.gravity))

                /* and update if changed */
                .distinctUntilChanged()
                .compose(RxLifecycle.<Point>bindView(bubble))

                /* and ignore any errors */
                .onErrorResumeNext(error -> {
                    AndroidUtility.removeView(bubble);
                    return Observable.empty();
                })

                .subscribe(point -> {
                    bubble.setTranslationX(point.x);
                    bubble.setTranslationY(point.y);
                });

        RxView.clicks(bubble).subscribe(v -> {
            markAsShown(bubble.getContext(), builder.hintName);

            bubble.animate()
                    .scaleX(0.9f).scaleY(0.9f)
                    .alpha(0.f)
                    .setStartDelay(0)
                    .setListener(endAction(() -> removeView(bubble)))
                    .start();
        });

        // lets go!
        bubble.setAlpha(0.f);
        bubble.setScaleX(0.9f);
        bubble.setScaleY(0.9f);
        root.addView(bubble);

        bubble.animate()
                .alpha(1.f)
                .scaleX(1.f)
                .scaleY(1.f)
                .setStartDelay(500)
                .start();

        return bubble;
    }

    private static final String SSO_PREFIX = BuildConfig.DEBUG
            ? (System.currentTimeMillis() + "__")
            : "bubbleHint__";

    public static boolean wouldShow(Context context, String name) {
        return false;

//        SingleShotService sso = appComponent(context).singleShotService();
//        return name == null || sso.test().isFirstTime(SSO_PREFIX + name);
    }

    private static void markAsShown(Context context, String name) {
        if (name != null) {
            appComponent(context).singleShotService().isFirstTime(SSO_PREFIX + name);
        }
    }

    private static Point calculateBubblePosition(ViewGroup root, View target, BubbleView bubble, int gravity) {
        // calculate position of the target
        Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
        root.offsetDescendantRectToMyCoords(target, rect);

        // update depending on gravity
        Point position = new Point(rect.left, rect.top);
        if (bubble.getWidth() > 0 && bubble.getHeight() > 0) {
            switch (gravity) {
                case Gravity.LEFT:
                    position.x += rect.width();
                    position.y += (rect.height() - bubble.getHeight()) / 2;
                    break;
                case Gravity.RIGHT:
                    position.x -= bubble.getWidth();
                    position.y += (rect.height() - bubble.getHeight()) / 2;
                    break;
                case Gravity.TOP:
                    position.y += rect.height();
                    position.x += (rect.width() - bubble.getWidth()) / 2;
                    break;
                case Gravity.BOTTOM:
                    position.y -= bubble.getHeight();
                    position.x += (rect.width() - bubble.getWidth()) / 2;
                    break;
            }
        }

        return position;
    }

    private static BubbleView createBubbleView(ViewGroup root, String text, int gravity) {
        // inflate view from xml
        int layout = layoutForGravity(gravity);
        BubbleView bubble = (BubbleView) LayoutInflater
                .from(root.getContext())
                .inflate(layout, root, false);

        bubble.setText(text);
        return bubble;
    }

    private static int layoutForGravity(int gravity) {
        switch (gravity) {
            case Gravity.LEFT:
                return R.layout.bubble_left;
            case Gravity.RIGHT:
                return R.layout.bubble_right;
            case Gravity.TOP:
                return R.layout.bubble_top;
            case Gravity.BOTTOM:
                return R.layout.bubble_bottom;
        }

        throw new IllegalArgumentException("Invalid gravity");
    }

    private static Observable<Void> parentScrolls(View view) {
        List<Observable<Void>> result = new ArrayList<>();
        if (atLeast(Build.VERSION_CODES.M)) {

            ViewParent vp = view.getParent();
            while (vp instanceof View) {
                result.add(RxView.scrollChangeEvents((View) vp).map(v -> (Void) null));
                vp = vp.getParent();
            }
        } else {
            // okay, we just add listeners to classes that support this.
            ViewParent vp = view.getParent();
            while (vp instanceof View) {
                if (vp instanceof RecyclerView) {
                    result.add(RecyclerViewOnScrollSubscription.onScroll((RecyclerView) vp));
                }

                vp = vp.getParent();
            }
        }

        return Observable.merge(result);
    }

    private static ViewGroup rootOf(View view) {
        ViewGroup parent = (ViewGroup) view.getParent();
        while (parent.getParent() instanceof ViewGroup)
            parent = (ViewGroup) parent.getParent();

        return parent;
    }

    public static Subscription reattach(Observable<Boolean> trigger, Func0<Optional<? extends View>> supplier) {
        return trigger.observeOn(AndroidSchedulers.mainThread()).subscribe(new Action1<Boolean>() {
            @Nullable
            private View bubble;

            @Override
            public void call(Boolean ev) {
                // remove any previous view
                AndroidUtility.removeView(bubble);
                bubble = null;

                if (ev != null && ev) {
                    bubble = supplier.call().orNull();
                }
            }
        });
    }

    public static BubbleHelper leftOf(View target) {
        return new BubbleHelper(target).gravity(Gravity.RIGHT);
    }

    public static BubbleHelper rightOf(View target) {
        return new BubbleHelper(target).gravity(Gravity.LEFT);
    }

    public static BubbleHelper below(View target) {
        return new BubbleHelper(target).gravity(Gravity.TOP);
    }

    public static BubbleHelper above(View target) {
        return new BubbleHelper(target).gravity(Gravity.BOTTOM);
    }

}