package moe.zl.breakinfalsus.input;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.card.MaterialCardView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

import moe.zl.breakinfalsus.R;

public class SixKeyTouchLayout extends FrameLayout {

    public interface OnKeyStateChangeListener {
        void onKeyStateChanged(boolean[] keyStates);
    }

    private static final String[] KEY_LABELS = new String[]{"Shift", "A", "S", "D", "F", "Space"};
    private static final float[] DEFAULT_KEY_WIDTH_RATIOS = new float[]{1f, 1f, 1f, 1f, 1f, 1f};
    private static final long PRESS_IN_DURATION_MS = 72L;
    private static final long RELEASE_DURATION_MS = 210L;
    private static final int KEY_COUNT = KEY_LABELS.length;
    private static final float HOLD_EFFECT_SIZE_DP = 96f;
    private static final float PRESS_EFFECT_SIZE_DP = 138f;

    private final MaterialCardView[] keyViews = new MaterialCardView[KEY_COUNT];
    private final TextView[] keyLabels = new TextView[KEY_COUNT];
    private final View[] flashOverlays = new View[KEY_COUNT];
    private final AnimatorSet[] keyAnimators = new AnimatorSet[KEY_COUNT];
    private final boolean[] keyStates = new boolean[KEY_COUNT];
    private final float[] keyWidthRatios = DEFAULT_KEY_WIDTH_RATIOS.clone();
    private final float[] keyLeftBounds = new float[KEY_COUNT];
    private final float[] keyRightBounds = new float[KEY_COUNT];
    private final ArrayList<GifEffect> burstEffects = new ArrayList<>();
    private final SparseArray<TouchEmitter> emitters = new SparseArray<>();

    private final int idleCardColor = 0x66161b2b;
    private final int pressedCardColor = 0xff0f1726;
    private final int idleStrokeColor = 0x88304b83;
    private final int pressedStrokeColor = 0xff8fe3ff;
    private final int labelIdleColor = 0xd8f1f7ff;
    private final int labelPressedColor = 0xffffffff;

    private OnKeyStateChangeListener listener;
    private TextView logt;
    private boolean motionLogEnabled;
    private float chordBufferPx;
    private byte[] holdEffectBytes;
    private byte[] pressEffectBytes;

    public SixKeyTouchLayout(@NonNull Context context) {
        super(context);
        init(context, null);
    }

    public SixKeyTouchLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public SixKeyTouchLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setClipToPadding(false);
        setClipChildren(false);
        setBackgroundColor(0xff02040a);
        chordBufferPx = dp(12);
        holdEffectBytes = readRawBytes(R.drawable.bx);
        pressEffectBytes = readRawBytes(R.drawable.xbs);

        if (attrs != null) {
            TypedArray typedArray = context.obtainStyledAttributes(attrs, R.styleable.SixKeyTouchLayout);
            try {
                applyRatioString(typedArray.getString(R.styleable.SixKeyTouchLayout_keyWidthRatios));
            } finally {
                typedArray.recycle();
            }
        }

        for (int i = 0; i < keyViews.length; i++) {
            MaterialCardView keyView = new MaterialCardView(context);
            keyView.setCardBackgroundColor(idleCardColor);
            keyView.setRadius(dp(24));
            keyView.setStrokeWidth(dp(2));
            keyView.setStrokeColor(idleStrokeColor);
            keyView.setCardElevation(dp(2));
            keyView.setClickable(false);
            keyView.setFocusable(false);
            keyView.setUseCompatPadding(false);
            keyView.setPreventCornerOverlap(true);
            keyView.setCameraDistance(dp(960));

            View flashOverlay = new View(context);
            flashOverlay.setBackground(createFlashDrawable());
            flashOverlay.setAlpha(0f);
            flashOverlay.setScaleX(0.82f);
            flashOverlay.setScaleY(0.6f);
            flashOverlay.setTranslationY(dp(20));
            flashOverlay.setRotation(-4f);
            flashOverlays[i] = flashOverlay;
            keyView.addView(flashOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            TextView keyLabel = new TextView(context);
            keyLabel.setText(KEY_LABELS[i]);
            keyLabel.setTextColor(labelIdleColor);
            keyLabel.setTextSize(17f);
            keyLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            keyLabel.setGravity(Gravity.CENTER);
            keyLabel.setLetterSpacing(0.05f);
            keyLabels[i] = keyLabel;
            keyView.addView(keyLabel, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            keyViews[i] = keyView;
            addView(keyView);
        }

        logt = new TextView(context);
        logt.setTextColor(0xffffffff);
        logt.setBackgroundColor(0x66000000);
        logt.setPadding(dp(10), dp(8), dp(10), dp(8));
        logt.setGravity(Gravity.START | Gravity.TOP);
        logt.setTextSize(12f);
        logt.setVisibility(GONE);
        addView(logt);
    }

    public void setOnKeyStateChangeListener(OnKeyStateChangeListener listener) {
        this.listener = listener;
    }

    public void setMotionLogEnabled(boolean enabled) {
        motionLogEnabled = enabled;
        logt.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            logt.setText("");
        }
    }

    public void setChordBufferDp(float bufferDp) {
        chordBufferPx = Math.max(0f, bufferDp * getResources().getDisplayMetrics().density);
    }

    public void setKeyWidthRatios(@Nullable float[] ratios) {
        if (ratios == null || ratios.length != KEY_COUNT) {
            System.arraycopy(DEFAULT_KEY_WIDTH_RATIOS, 0, keyWidthRatios, 0, KEY_COUNT);
        } else {
            for (int i = 0; i < KEY_COUNT; i++) {
                keyWidthRatios[i] = ratios[i] > 0f ? ratios[i] : 1f;
            }
        }
        rebuildKeyBounds(getWidth());
        requestLayout();
        invalidate();
    }

    public boolean setKeyWidthRatios(@Nullable String ratios) {
        boolean parsed = applyRatioString(ratios);
        if (parsed) {
            rebuildKeyBounds(getWidth());
            requestLayout();
            invalidate();
        }
        return parsed;
    }

    @NonNull
    public float[] getKeyWidthRatios() {
        return keyWidthRatios.clone();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuildKeyBounds(w);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        int margin = dp(3);
        int gutter = dp(2);
        rebuildKeyBounds(width);
        for (int i = 0; i < keyViews.length; i++) {
            int childLeft = Math.round(keyLeftBounds[i]) + margin;
            int childRight = Math.round(keyRightBounds[i]) - margin;
            if (i > 0) {
                childLeft += gutter;
            }
            if (i < keyViews.length - 1) {
                childRight -= gutter;
            }
            if (childRight <= childLeft) {
                childRight = childLeft + 1;
            }
            keyViews[i].layout(childLeft, margin, childRight, height - margin);
        }
        logt.layout(0, 0, width, height);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        drawGifEffects(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean[] nextStates = new boolean[keyStates.length];
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_CANCEL) {
            int liftedPointer = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                    ? event.getActionIndex()
                    : -1;
            for (int i = 0; i < event.getPointerCount(); i++) {
                if (i == liftedPointer) {
                    continue;
                }
                updateStatesForPointer(nextStates, event.getX(i));
            }
        }
        updateEffectEmitters(event);
        boolean changed = applyKeyStates(nextStates);
        if (changed && listener != null) {
            listener.onKeyStateChanged(keyStates.clone());
        }
        if (motionLogEnabled) {
            logt.setText(buildMotionLog(event, nextStates));
        }
        if (hasActiveEffects()) {
            postInvalidateOnAnimation();
        }
        return true;
    }

    private void drawGifEffects(@NonNull Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        for (int i = burstEffects.size() - 1; i >= 0; i--) {
            GifEffect effect = burstEffects.get(i);
            if (!drawEffect(canvas, effect, now)) {
                burstEffects.remove(i);
            }
        }
        for (int i = 0; i < emitters.size(); i++) {
            TouchEmitter emitter = emitters.valueAt(i);
            if (emitter.holdEffect != null) {
                drawEffect(canvas, emitter.holdEffect, now);
            }
        }
        if (hasActiveEffects()) {
            postInvalidateOnAnimation();
        }
    }

    private boolean drawEffect(@NonNull Canvas canvas, @NonNull GifEffect effect, long now) {
        if (effect.movie == null) {
            return false;
        }
        int duration = effect.durationMs > 0 ? effect.durationMs : 1000;
        long elapsed = now - effect.startedAtMs;
        if (!effect.loop && elapsed >= duration) {
            return false;
        }
        int time = effect.loop ? (int) (elapsed % duration) : (int) Math.min(elapsed, duration - 1L);
        effect.movie.setTime(time);

        float scaleX = effect.width / effect.movie.width();
        float scaleY = effect.height / effect.movie.height();
        float drawLeft = effect.centerX - effect.width * 0.5f;
        float drawTop = effect.centerY - effect.height * 0.5f;

        canvas.save();
        canvas.translate(drawLeft, drawTop);
        canvas.scale(scaleX, scaleY);
        effect.movie.draw(canvas, 0f, 0f);
        canvas.restore();
        return true;
    }

    private boolean applyKeyStates(boolean[] nextStates) {
        boolean changed = false;
        for (int i = 0; i < keyStates.length; i++) {
            if (keyStates[i] != nextStates[i]) {
                keyStates[i] = nextStates[i];
                changed = true;
                animateKeyState(i, nextStates[i]);
            }
            keyViews[i].setCardBackgroundColor(keyStates[i] ? pressedCardColor : idleCardColor);
        }
        return changed;
    }

    private void animateKeyState(int index, boolean pressed) {
        if (keyAnimators[index] != null) {
            keyAnimators[index].cancel();
        }
        MaterialCardView keyView = keyViews[index];
        TextView keyLabel = keyLabels[index];
        View flashOverlay = flashOverlays[index];

        ValueAnimator strokeAnimator = ValueAnimator.ofArgb(
                keyView.getStrokeColorStateList() != null
                        ? keyView.getStrokeColorStateList().getDefaultColor()
                        : idleStrokeColor,
                pressed ? pressedStrokeColor : idleStrokeColor
        );
        strokeAnimator.addUpdateListener(animation ->
                keyView.setStrokeColor((Integer) animation.getAnimatedValue()));

        ValueAnimator labelColorAnimator = ValueAnimator.ofArgb(
                keyLabel.getCurrentTextColor(),
                pressed ? labelPressedColor : labelIdleColor
        );
        labelColorAnimator.addUpdateListener(animation ->
                keyLabel.setTextColor((Integer) animation.getAnimatedValue()));

        ValueAnimator strokeWidthAnimator = ValueAnimator.ofInt(
                keyView.getStrokeWidth(),
                pressed ? dp(3) : dp(2)
        );
        strokeWidthAnimator.addUpdateListener(animation ->
                keyView.setStrokeWidth((Integer) animation.getAnimatedValue()));

        ObjectAnimator keyScaleX = ObjectAnimator.ofFloat(
                keyView,
                View.SCALE_X,
                keyView.getScaleX(),
                pressed ? 0.975f : 1f
        );
        ObjectAnimator keyScaleY = ObjectAnimator.ofFloat(
                keyView,
                View.SCALE_Y,
                keyView.getScaleY(),
                pressed ? 0.945f : 1f
        );
        ObjectAnimator keyTranslationY = ObjectAnimator.ofFloat(
                keyView,
                View.TRANSLATION_Y,
                keyView.getTranslationY(),
                pressed ? dp(7) : 0f
        );
        ObjectAnimator keyRotationX = ObjectAnimator.ofFloat(
                keyView,
                View.ROTATION_X,
                keyView.getRotationX(),
                pressed ? 7f : 0f
        );
        ObjectAnimator keyElevation = ObjectAnimator.ofFloat(
                keyView,
                "cardElevation",
                keyView.getCardElevation(),
                pressed ? dp(10) : dp(2)
        );

        ObjectAnimator labelTranslationY = ObjectAnimator.ofFloat(
                keyLabel,
                View.TRANSLATION_Y,
                keyLabel.getTranslationY(),
                pressed ? dp(2) : 0f
        );
        ObjectAnimator labelScaleX = ObjectAnimator.ofFloat(
                keyLabel,
                View.SCALE_X,
                keyLabel.getScaleX(),
                pressed ? 0.985f : 1f
        );
        ObjectAnimator labelScaleY = ObjectAnimator.ofFloat(
                keyLabel,
                View.SCALE_Y,
                keyLabel.getScaleY(),
                pressed ? 0.985f : 1f
        );

        ObjectAnimator flashAlpha = pressed
                ? ObjectAnimator.ofFloat(flashOverlay, View.ALPHA, flashOverlay.getAlpha(), 0.92f, 0.18f)
                : ObjectAnimator.ofFloat(flashOverlay, View.ALPHA, flashOverlay.getAlpha(), 0f);
        ObjectAnimator flashScaleX = ObjectAnimator.ofFloat(
                flashOverlay,
                View.SCALE_X,
                flashOverlay.getScaleX(),
                pressed ? 1.07f : 0.82f
        );
        ObjectAnimator flashScaleY = ObjectAnimator.ofFloat(
                flashOverlay,
                View.SCALE_Y,
                flashOverlay.getScaleY(),
                pressed ? 1.16f : 0.6f
        );
        ObjectAnimator flashTranslationY = ObjectAnimator.ofFloat(
                flashOverlay,
                View.TRANSLATION_Y,
                flashOverlay.getTranslationY(),
                pressed ? dp(-4) : dp(20)
        );
        ObjectAnimator flashRotation = ObjectAnimator.ofFloat(
                flashOverlay,
                View.ROTATION,
                flashOverlay.getRotation(),
                pressed ? 0f : -4f
        );

        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                strokeAnimator,
                strokeWidthAnimator,
                labelColorAnimator,
                keyScaleX,
                keyScaleY,
                keyTranslationY,
                keyRotationX,
                keyElevation,
                labelTranslationY,
                labelScaleX,
                labelScaleY,
                flashAlpha,
                flashScaleX,
                flashScaleY,
                flashTranslationY,
                flashRotation
        );
        animatorSet.setDuration(pressed ? PRESS_IN_DURATION_MS : RELEASE_DURATION_MS);
        animatorSet.setInterpolator(pressed ? new DecelerateInterpolator(1.6f) : new OvershootInterpolator(1.1f));
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!pressed) {
                    flashOverlay.setAlpha(0f);
                }
                if (keyAnimators[index] == animation) {
                    keyAnimators[index] = null;
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                if (keyAnimators[index] == animation) {
                    keyAnimators[index] = null;
                }
            }
        });
        keyAnimators[index] = animatorSet;
        animatorSet.start();
    }

    private void updateStatesForPointer(boolean[] nextStates, float x) {
        if (getWidth() <= 0) {
            return;
        }
        float clampedX = Math.max(0f, Math.min(x, getWidth() - 1f));
        int index = findKeyIndex(clampedX);
        nextStates[index] = true;

        float leftEdge = keyLeftBounds[index];
        float rightEdge = keyRightBounds[index];
        if (clampedX - leftEdge <= chordBufferPx && index > 0) {
            nextStates[index - 1] = true;
        }
        if (rightEdge - clampedX <= chordBufferPx && index < keyStates.length - 1) {
            nextStates[index + 1] = true;
        }
    }

    private void updateEffectEmitters(@NonNull MotionEvent event) {
        int action = event.getActionMasked();
        int actionIndex = event.getActionIndex();

        if (action == MotionEvent.ACTION_CANCEL) {
            emitters.clear();
            return;
        }

        int liftedPointerId = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)
                ? event.getPointerId(actionIndex)
                : -1;

        for (int i = 0; i < event.getPointerCount(); i++) {
            int pointerId = event.getPointerId(i);
            if (pointerId == liftedPointerId) {
                continue;
            }
            float x = event.getX(i);
            float y = event.getY(i);
            TouchEmitter emitter = emitters.get(pointerId);
            boolean isNewEmitter = emitter == null;
            if (emitter == null) {
                emitter = new TouchEmitter(pointerId);
                emitters.put(pointerId, emitter);
            }
            emitter.x = x;
            emitter.y = y;
            if (isNewEmitter || (action == MotionEvent.ACTION_DOWN && i == actionIndex)
                    || (action == MotionEvent.ACTION_POINTER_DOWN && i == actionIndex)) {
                emitter.holdEffect = createGifEffect(holdEffectBytes, x, y, dpF(HOLD_EFFECT_SIZE_DP), true);
                GifEffect pressEffect = createGifEffect(pressEffectBytes, x, y, dpF(PRESS_EFFECT_SIZE_DP), false);
                if (pressEffect != null) {
                    burstEffects.add(pressEffect);
                }
            } else if (emitter.holdEffect != null) {
                emitter.holdEffect.centerX = x;
                emitter.holdEffect.centerY = y;
            }
        }

        if (liftedPointerId != -1) {
            emitters.remove(liftedPointerId);
        }
    }

    @Nullable
    private GifEffect createGifEffect(@Nullable byte[] bytes, float centerX, float centerY, float size, boolean loop) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        Movie movie = Movie.decodeByteArray(bytes, 0, bytes.length);
        if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
            return null;
        }
        GifEffect effect = new GifEffect();
        effect.movie = movie;
        effect.centerX = centerX;
        effect.centerY = centerY;
        effect.width = size;
        effect.height = size * (movie.height() / (float) movie.width());
        effect.loop = loop;
        effect.startedAtMs = SystemClock.uptimeMillis();
        effect.durationMs = movie.duration() > 0 ? movie.duration() : 1000;
        return effect;
    }

    private boolean hasActiveEffects() {
        return !burstEffects.isEmpty() || emitters.size() > 0;
    }

    private int findKeyIndex(float x) {
        for (int i = 0; i < keyRightBounds.length; i++) {
            if (x < keyRightBounds[i]) {
                return i;
            }
        }
        return keyRightBounds.length - 1;
    }

    private void rebuildKeyBounds(int width) {
        if (width <= 0) {
            return;
        }
        float totalRatio = 0f;
        for (float ratio : keyWidthRatios) {
            totalRatio += Math.max(0.01f, ratio);
        }
        float cursor = 0f;
        for (int i = 0; i < KEY_COUNT; i++) {
            keyLeftBounds[i] = cursor;
            cursor += width * (Math.max(0.01f, keyWidthRatios[i]) / totalRatio);
            keyRightBounds[i] = i == KEY_COUNT - 1 ? width : cursor;
        }
    }

    private boolean applyRatioString(@Nullable String ratios) {
        if (TextUtils.isEmpty(ratios)) {
            System.arraycopy(DEFAULT_KEY_WIDTH_RATIOS, 0, keyWidthRatios, 0, KEY_COUNT);
            return true;
        }
        String[] tokens = ratios.split("[:;,\\s]+");
        if (tokens.length != KEY_COUNT) {
            return false;
        }
        float[] parsed = new float[KEY_COUNT];
        for (int i = 0; i < KEY_COUNT; i++) {
            try {
                float value = Float.parseFloat(tokens[i]);
                parsed[i] = value > 0f ? value : 1f;
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        System.arraycopy(parsed, 0, keyWidthRatios, 0, KEY_COUNT);
        return true;
    }

    private String buildMotionLog(MotionEvent event, boolean[] nextStates) {
        StringBuilder builder = new StringBuilder();
        builder.append(actionToString(event.getActionMasked()))
                .append(" idx=")
                .append(event.getActionIndex())
                .append(" ptr=")
                .append(event.getPointerCount())
                .append('\n');
        for (int i = 0; i < event.getPointerCount(); i++) {
            builder.append('#')
                    .append(event.getPointerId(i))
                    .append(" (")
                    .append(Math.round(event.getX(i)))
                    .append(", ")
                    .append(Math.round(event.getY(i)))
                    .append(')');
            if (i == event.getActionIndex()) {
                builder.append(" *");
            }
            builder.append('\n');
        }
        builder.append("keys=");
        for (boolean state : nextStates) {
            builder.append(state ? '1' : '0');
        }
        builder.append(" buffer=")
                .append(Math.round(chordBufferPx / getResources().getDisplayMetrics().density))
                .append("dp ratios=");
        for (int i = 0; i < keyWidthRatios.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(String.format(Locale.US, "%.2f", keyWidthRatios[i]));
        }
        return builder.toString();
    }

    private String actionToString(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            case MotionEvent.ACTION_POINTER_DOWN:
                return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP:
                return "POINTER_UP";
            default:
                return "ACTION_" + action;
        }
    }

    private GradientDrawable createFlashDrawable() {
        int glow = ColorUtils.setAlphaComponent(pressedStrokeColor, 180);
        int mid = ColorUtils.setAlphaComponent(0xffffffff, 70);
        int fade = ColorUtils.setAlphaComponent(pressedStrokeColor, 0);
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{glow, mid, fade}
        );
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    @Nullable
    private byte[] readRawBytes(int resId) {
        try (InputStream stream = getResources().openRawResource(resId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float dpF(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class TouchEmitter {
        final int pointerId;
        float x;
        float y;
        GifEffect holdEffect;

        TouchEmitter(int pointerId) {
            this.pointerId = pointerId;
        }
    }

    private static final class GifEffect {
        Movie movie;
        float centerX;
        float centerY;
        float width;
        float height;
        boolean loop;
        long startedAtMs;
        int durationMs;
    }
}
