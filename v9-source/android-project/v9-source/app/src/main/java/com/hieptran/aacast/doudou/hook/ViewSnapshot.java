package com.carassistant.v9.hook;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Lưu trạng thái gốc của một view trước khi ẩn, để khôi phục chính xác.
 */
final class ViewSnapshot {

    private final View view;
    private final View reference;
    private final int referenceWidth;
    private final int referenceHeight;
    private final int visibility;
    private final int height;
    private final IdentityHashMap<View, Integer> siblingMargins = new IdentityHashMap<>();

    ViewSnapshot(View view, View reference) {
        this.view = view;
        this.reference = reference;
        this.referenceWidth = reference.getWidth();
        this.referenceHeight = reference.getHeight();
        this.visibility = view.getVisibility();
        this.height = view.getLayoutParams().height;

        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == view) {
                    continue;
                }
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    if (margins.height == ViewGroup.LayoutParams.MATCH_PARENT
                            && margins.bottomMargin == view.getHeight()) {
                        siblingMargins.put(child, margins.bottomMargin);
                    }
                }
            }
        }
    }

    /** True nếu view vẫn còn nguyên như lúc chụp (chưa bị layout lại). */
    boolean isStale() {
        return !view.isAttachedToWindow()
                || reference.getWidth() != referenceWidth
                || reference.getHeight() != referenceHeight;
    }

    /** Ẩn view. */
    void apply() {
        if (view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
        }
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }
        for (View child : siblingMargins.keySet()) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            if (margins.bottomMargin != 0) {
                margins.bottomMargin = 0;
                child.setLayoutParams(margins);
            }
        }
    }

    /** Khôi phục. */
    void restore() {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height == 0) {
            params.height = height;
            view.setLayoutParams(params);
        }
        if (view.getVisibility() == View.GONE) {
            view.setVisibility(visibility);
        }
        for (Map.Entry<View, Integer> entry : siblingMargins.entrySet()) {
            ViewGroup.MarginLayoutParams margins =
                    (ViewGroup.MarginLayoutParams) entry.getKey().getLayoutParams();
            if (margins.bottomMargin == 0) {
                margins.bottomMargin = entry.getValue();
                entry.getKey().setLayoutParams(margins);
            }
        }
    }

    /** Dùng cho log. */
    String describe() {
        try {
            Resources resources = view.getResources();
            return resources.getResourceEntryName(view.getId());
        } catch (RuntimeException e) {
            return "view@" + Integer.toHexString(System.identityHashCode(view));
        }
    }
}
