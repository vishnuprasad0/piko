/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.shared.Logger;

import static app.morphe.extension.instagram.utils.IgStr.str;

/**
 * Runtime hook for the "Instants" (quicksnap) camera. Adds an in-camera button that opens a gallery
 * picker → preview → posts directly on the live QuickSnap ViewModel, no shutter press.
 * Camera host Activity: com.instagram.modal.TransparentModalActivity.
 */
@SuppressWarnings("unused")
public class InstantsHook {

    /** IGDS themed colour, falling back to a literal if the attr can't be resolved on this build. */
    static int themed(String attr, int fallback) {
        try {
            return UI.getThemedColour(attr);
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---- Activity tracker (lazy-init) ----
    private static volatile boolean sTrackerInited;
    private static volatile WeakReference<Activity> sResumedActivity;

    public static void initActivityTracker(Context context) {
        if (sTrackerInited) return;
        synchronized (InstantsHook.class) {
            if (sTrackerInited) return;
            sTrackerInited = true;
            Application app = (Application) context.getApplicationContext();
            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    sResumedActivity = new WeakReference<>(activity);
                    // Another modal (Stories camera, story viewer) coming to the front means the
                    // Instants camera is behind it — drop the confirmation so it can't be inherited.
                    if (isCameraActivity(activity) && !isInstantsCameraActivity(activity)) {
                        onInstantsCameraGone();
                    }
                    maybeAddOverlayButton(activity);
                }
                @Override public void onActivityPaused(Activity activity) {
                    // Don't remove here — opening the picker pauses the camera transiently.
                    // Removal happens on stop/destroy below.
                }
                @Override public void onActivityStopped(Activity activity) {
                    if (isInstantsCameraActivity(activity) || isOverlayHost(activity)) onInstantsCameraGone();
                }
                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {
                    if (isInstantsCameraActivity(activity) || isOverlayHost(activity)) onInstantsCameraGone();
                }
                @Override public void onActivityCreated(Activity activity, android.os.Bundle savedInstanceState) {}
            });
        }
    }

    private static boolean isCameraActivity(Activity activity) {
        return activity != null
                && activity.getClass().getName().equals("com.instagram.modal.TransparentModalActivity");
    }

    /** True only for the exact Activity instance the QuickSnap VM confirmed as the Instants camera.
     *  Stories reuses the same TransparentModalActivity class but never reaches that VM. */
    private static boolean isInstantsCameraActivity(Activity activity) {
        Activity confirmed = sInstantsCameraActivity != null ? sInstantsCameraActivity.get() : null;
        return activity != null && activity == confirmed;
    }

    /** True if [activity] is the one the overlay button was actually added to. */
    private static boolean isOverlayHost(Activity activity) {
        Activity host = sOverlayHost != null ? sOverlayHost.get() : null;
        return activity != null && activity == host;
    }

    /** The Instants camera Activity has stopped/been destroyed — tear down its button and signal. */
    private static void onInstantsCameraGone() {
        removeOverlayButton();
        sInstantsCameraActivity = null;
    }

    // ---- Overlay button management ----
    private static volatile View sOverlayButton;
    /** The Activity the button was added to, so it can be torn down even if confirmation was lost. */
    private static volatile WeakReference<Activity> sOverlayHost;
    /** The Activity instance the QuickSnap VM confirmed as the Instants camera. */
    private static volatile WeakReference<Activity> sInstantsCameraActivity;

    private static void maybeAddOverlayButton(Activity activity) {
        try {
            if (activity == null) return;
            if (!isCameraActivity(activity)) return;
            // TransparentModalActivity hosts the Instants camera, the Stories camera and the story
            // viewer alike; only Instants runs the QuickSnap VM. Bind to that exact instance — a
            // "seen recently" window would hand the button to whatever modal opens next.
            if (!isInstantsCameraActivity(activity)) return;
            if (sOverlayButton != null) return; // already added

            float density = activity.getResources().getDisplayMetrics().density;
            int padH = (int) (14 * density);
            int padV = (int) (9 * density);

            // A "+" glyph (drawn programmatically, no IG drawable dependency) plus a label on a
            // rounded background. addContentView layers it above the full-screen camera surface.
            int fg = themed("igds_color_primary_button_icon", 0xFFFFFFFF);
            TextView button = new TextView(activity);
            button.setText(str("piko_instants_add_from_gallery"));
            button.setTextColor(fg);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER_VERTICAL);
            Drawable plus = makePlusIcon(density, fg);
            int ic = (int) (16 * density);
            plus.setBounds(0, 0, ic, ic);
            button.setCompoundDrawables(plus, null, null, null);
            button.setCompoundDrawablePadding((int) (6 * density));
            button.setPadding(padH, padV, padH, padV);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(22 * density);
            bg.setColor(themed("igds_color_primary_button", 0xFF0095F6));
            button.setBackground(bg);
            button.setOnClickListener(v -> launchPickerFromOverlay(v.getContext()));

            // Top-right, with a margin clearing Instagram's own top-right button.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            lp.topMargin = (int) (108 * density);
            lp.rightMargin = (int) (20 * density);
            activity.addContentView(button, lp);

            sOverlayButton = button;
            sOverlayHost = new WeakReference<>(activity);
        } catch (Exception e) {
            Logger.printException(() -> "instants maybeAddOverlayButton failed", e);
        }
    }

    /** Builds a "+" icon on a bitmap, so there's no drawable resource to miss. */
    private static android.graphics.drawable.Drawable makePlusIcon(float density, int colour) {
        int s = (int) (24 * density);
        int bar = Math.max(2, (int) (2.5f * density));
        int margin = (int) (5 * density);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(colour);
        int mid = s / 2;
        c.drawRect(margin, mid - bar, s - margin, mid + bar, p);
        c.drawRect(mid - bar, margin, mid + bar, s - margin, p);
        return new android.graphics.drawable.BitmapDrawable(bmp);
    }

    private static void removeOverlayButton() {
        try {
            if (sOverlayButton == null) return;
            ViewGroup parent = (ViewGroup) sOverlayButton.getParent();
            if (parent != null) {
                parent.removeView(sOverlayButton);
            }
            sOverlayButton = null;
            sOverlayHost = null;
        } catch (Exception e) {
            Logger.printException(() -> "instants removeOverlayButton failed", e);
        }
    }

    // ---- Picker launch methods ----

    /** Launches the gallery picker from the in-camera button → preview → post. */
    public static void launchPickerFromOverlay(Context context) {
        try {
            if (context == null) return;
            Intent intent = new Intent(context, InstantsGalleryPickerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Logger.printException(() -> "instants launchPickerFromOverlay failed", t);
        }
    }

    // ---- Live ViewModel + auto-post (obfuscated names resolved at patch time — see names()) ----

    private static volatile WeakReference<Object> sLiveViewModel;

    /** Injected at the entry of QuickSnapCameraViewModel's instance methods (p0 = VM). */
    public static void noteLiveViewModel(Object vm) {
        if (vm == null) return;
        sLiveViewModel = new WeakReference<>(vm);
        // This VM only runs on the Instants camera, so it's the reliable moment to confirm the
        // Activity and add the button. May be called off the main thread, so hop to it for the view.
        Activity current = sResumedActivity != null ? sResumedActivity.get() : null;
        if (current != null && isCameraActivity(current)) {
            sInstantsCameraActivity = new WeakReference<>(current);
            if (sOverlayButton == null) current.runOnUiThread(() -> maybeAddOverlayButton(current));
        }
    }

    // Indices into names(). The patch resolves entries by placeholder text, not position, so the
    // array order isn't a contract.
    private static final int N_MARKER = 0;
    private static final int N_VM_UUID_FIELD = 1;
    private static final int N_VM_STATE_FLOW_FIELD = 2;
    private static final int N_STATE_ILK_FIELD = 3;
    private static final int N_VM_CTRL_FIELD = 4;
    private static final int N_CTRL_LOCK_METHOD = 5;
    private static final int N_SCOPE_CLASS = 6;
    private static final int N_SCOPE_METHOD = 7;
    private static final int N_DISPATCH_CLASS = 8;
    private static final int N_DISPATCH_METHOD = 9;
    private static final int N_VM_PRE_STEP_1 = 10;
    private static final int N_VM_PRE_STEP_2 = 11;

    /** Value of N_MARKER once patch-time resolution has committed. */
    private static final String MARKER_RESOLVED = "1";

    /**
     * Obfuscated field/method/class names the auto-post path reflects on, resolved at patch time
     * (§11) by instantsGalleryPatch from the target dex. Each entry is a sentinel the resolver
     * locates by value and rewrites; there is no last-known-good fallback, since obfuscated names
     * rotate on every R8 run. If resolution didn't run, the marker stays unset and
     * publishStagedInstant bails (see the check there). The patch also installs no hook unless every
     * name resolved, so this is a backstop rather than the primary guard.
     */
    private static String[] names() {
        return new String[] {
                "piko.instants.marker",           // patched to "1" — commit flag, written last
                "piko.instants.vmUuidField",      // VM capture-session UUID field (volatile String)
                "piko.instants.vmStateFlowField", // VM state-flow field (getValue() -> capture state)
                "piko.instants.stateIlkField",    // capture-state field holding publish params (IlK)
                "piko.instants.vmCtrlField",      // VM capture-controller field
                "piko.instants.ctrlLockMethod",   // no-arg void method on the controller: lock capture
                "piko.instants.scopeClass",       // coroutine-scope helper class (binary name)
                "piko.instants.scopeMethod",      // scope helper method
                "piko.instants.dispatchClass",    // coroutine dispatch class (binary name)
                "piko.instants.dispatchMethod",   // dispatch method
                "piko.instants.vmPreStep1",       // VM static pre-step 1: (VM)V
                "piko.instants.vmPreStep2",       // VM static pre-step 2: (VM, String)V
        };
    }

    /** Invokes the static method named {@code name} on {@code cls} with parameter types {@code sig},
     *  passing {@code args}. Best-effort: logs and swallows any failure so a missing/renamed step
     *  never aborts the auto-post attempt. */
    private static void invokeStaticByName(Class<?> cls, String name, String tag, Class<?>[] sig, Object... args) {
        try {
            java.lang.reflect.Method m = cls.getDeclaredMethod(name, sig);
            m.setAccessible(true);
            m.invoke(null, args);
        } catch (Throwable t) {
            Logger.printException(() -> "instants autopost: " + tag + " (" + name + ") skipped", t);
        }
    }

    private static Object declaredField(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    /** Center-crop to a square and scale — Instants are square (~1012px); a raw gallery image
     *  is the wrong size/aspect for the publish pipeline. */
    private static Bitmap toInstantSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
        int target = 1080;
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, target, target, true);
        if (cropped != scaled && cropped != src) cropped.recycle();
        return scaled;
    }

    /** Reconstructs the shutter's publish launch for the staged gallery bitmap, so no shutter press
     *  is needed. Mirrors the stock shutter branch's field reads and coroutine dispatch, using names
     *  resolved at patch time (§11). Fully guarded — any failure leaves the bitmap unposted.
     *  @return true if the publish launch was dispatched. */
    private static boolean publishStagedInstant(Context ctx, Bitmap rawBmp, Object vm) {
        try {
            String[] N = names(); // patch-time-resolved obfuscated names (§11)

            // If resolution never ran the sentinels are still in place — bail once instead of
            // reflecting our way to eleven silent misses.
            if (!MARKER_RESOLVED.equals(N[N_MARKER])) {
                Logger.printException(() -> "instants autopost: obfuscated-name resolution did not "
                        + "run for this Instagram build — auto-post unavailable");
                return false;
            }

            Bitmap bmp = toInstantSquare(rawBmp);
            Class<?> vmClass = vm.getClass();

            // Seed the capture-session UUID. The publish coroutine reads it before dispatching, so
            // without it the request is malformed and nothing posts.
            try {
                java.lang.reflect.Field f = vmClass.getDeclaredField(N[N_VM_UUID_FIELD]);
                f.setAccessible(true);
                if (f.get(vm) == null) {
                    f.set(vm, java.util.UUID.randomUUID().toString());
                }
            } catch (Throwable t) {
                Logger.printException(() -> "instants autopost: uuid seed failed", t);
            }

            // Mirror the shutter's pre-capture steps that build the "captured" VM state: two static
            // pre-steps, then the capture-controller lock. All best-effort, per-step logged.
            invokeStaticByName(vmClass, N[N_VM_PRE_STEP_1], "pre-step-1", new Class<?>[]{vmClass}, vm);
            invokeStaticByName(vmClass, N[N_VM_PRE_STEP_2], "pre-step-2",
                    new Class<?>[]{vmClass, String.class}, vm, "");
            try {
                Object ctrl = declaredField(vm, N[N_VM_CTRL_FIELD]);
                ctrl.getClass().getMethod(N[N_CTRL_LOCK_METHOD]).invoke(ctrl);
            } catch (Throwable t) {
                Logger.printException(() -> "instants autopost: capture-lock skipped", t);
            }

            // Render the bitmap into VM state — the only static (Context, Bitmap, VM) method, so
            // it's matched by signature and needs no obfuscated name.
            for (java.lang.reflect.Method m : vmClass.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && pt.length == 3
                        && pt[0] == Context.class && pt[1] == Bitmap.class && pt[2] == vmClass) {
                    m.setAccessible(true);
                    m.invoke(null, ctx, bmp, vm);
                    break;
                }
            }

            // ilk = ((state) vm.<stateFlow>.getValue()).<ilkField>
            Object stateFlow = declaredField(vm, N[N_VM_STATE_FLOW_FIELD]);
            Object state = stateFlow.getClass().getMethod("getValue").invoke(stateFlow);
            Object ilk = declaredField(state, N[N_STATE_ILK_FIELD]);

            // scope = scopeClass.scopeMethod(vm)
            Object scope = null;
            for (java.lang.reflect.Method m : Class.forName(N[N_SCOPE_CLASS]).getDeclaredMethods()) {
                if (m.getName().equals(N[N_SCOPE_METHOD]) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    scope = m.invoke(null, vm);
                    break;
                }
            }
            if (scope == null) return false;

            // oc = new onCaptured$4(ctx, bmp, vm, ilk, "", null, ts, ts) — unobfuscated nested class.
            Class<?> ocClass = Class.forName(
                    "com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel$onCaptured$4");
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> c : ocClass.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 8) { ctor = c; break; }
            }
            if (ctor == null) return false;
            ctor.setAccessible(true);
            long now = System.currentTimeMillis();
            Object oc = ctor.newInstance(ctx, bmp, vm, ilk, "", null, now, now);

            // dispatch: <dispatchClass>.<dispatchMethod>(oc, scope)
            boolean launched = false;
            for (java.lang.reflect.Method m : Class.forName(N[N_DISPATCH_CLASS]).getDeclaredMethods()) {
                if (m.getName().equals(N[N_DISPATCH_METHOD]) && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    m.invoke(null, oc, scope);
                    launched = true;
                    break;
                }
            }
            return launched;
        } catch (Throwable t) {
            Logger.printException(() -> "instants autopost failed", t);
            return false;
        }
    }

    /**
     * Called by the preview "Post" button: publishes the picked image on the live QuickSnap
     * ViewModel and, on success, finishes the camera so the user lands back on a refreshed tray.
     * @return true if the publish was dispatched; false if there was no live VM or it failed.
     */
    public static boolean stageAndPost(Context ctx, Bitmap bitmap) {
        boolean posted = false;
        try {
            // Strong ref for the duration of the post so it can't be GC'd mid-publish.
            Object vm = sLiveViewModel != null ? sLiveViewModel.get() : null;
            if (vm != null) {
                Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
                if (appCtx != null) posted = publishStagedInstant(appCtx, bitmap, vm);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "instants stageAndPost failed", t);
        }
        if (posted) finishInstantsCamera();
        return posted;
    }

    /** Close the Instants camera after a post — the silent publish doesn't drive the camera's own
     *  post-capture navigation. */
    private static void finishInstantsCamera() {
        try {
            Activity cam = sInstantsCameraActivity != null ? sInstantsCameraActivity.get() : null;
            if (cam != null) cam.runOnUiThread(cam::finish);
        } catch (Throwable t) {
            Logger.printException(() -> "instants finishInstantsCamera failed", t);
        }
    }

    // ---- Capture-hook entry ----

    /**
     * Injected at the entry of QuickSnapCameraViewModel$onCaptured$4's constructor. Bootstraps the
     * Activity tracker and records the live ViewModel, then returns the bitmap unchanged.
     */
    public static Bitmap onCapturedBitmap(Context context, Bitmap capturedBitmap, Object vm) {
        try {
            initActivityTracker(context.getApplicationContext());
            if (vm != null) noteLiveViewModel(vm);
        } catch (Throwable t) {
            Logger.printException(() -> "instants onCaptured hook failed", t);
        }
        return capturedBitmap;
    }
}
