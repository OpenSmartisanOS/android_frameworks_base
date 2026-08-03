/*
 * Copyright (C) 2026 OpenSmartisanOS
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import com.android.systemui.res.R;

/** SmartisanOS header layout backed by the current SystemUI QS lifecycle. */
public class SosQuickStatusBarHeader extends QuickStatusBarHeader {
    public SosQuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        View search = findViewById(R.id.sos_qs_search);
        if (search != null) {
            search.setOnClickListener(v -> launchSearch());
        }
    }

    @Override
    void updateResources() {
        ViewGroup.LayoutParams headerLp = getLayoutParams();
        if (headerLp != null) {
            headerLp.height = getResources().getDimensionPixelSize(
                    R.dimen.sos_qs_header_outer_height);
            setLayoutParams(headerLp);
        }

        if (mHeaderQsPanel != null) {
            MarginLayoutParams panelLp = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
            panelLp.width = 0;
            panelLp.height = 0;
            panelLp.topMargin = 0;
            panelLp.setMarginStart(0);
            panelLp.setMarginEnd(0);
            mHeaderQsPanel.setLayoutParams(panelLp);
            mHeaderQsPanel.setVisibility(GONE);
        }
        super.setVisibility(INVISIBLE);
    }

    @Override
    public void setVisibility(int visibility) {
        // The root shade header is shared by both horizontal pages. This view remains measured
        // as the QS top spacer but must never draw a second clock/search row.
        super.setVisibility(INVISIBLE);
    }

    private void launchSearch() {
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // The visual affordance is retained on builds without a search provider.
        }
    }
}
