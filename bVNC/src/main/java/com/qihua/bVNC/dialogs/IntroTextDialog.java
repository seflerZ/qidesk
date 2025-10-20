/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2010 Michael A. MacDonald
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.qihua.bVNC.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout.LayoutParams;
import android.widget.TextView;

import com.qihua.bVNC.Constants;
import com.qihua.bVNC.Database;
import com.qihua.bVNC.Utils;
import com.qihua.bVNC.R;
//import com.umeng.commonsdk.UMConfigure;

import kotlin.UNumbersKt;

/**
 * @author Michael A. MacDonald
 *
 */
public class IntroTextDialog extends Dialog {

    static IntroTextDialog dialog = null;
    private PackageInfo packageInfo;
    private Database database;
    private boolean donate = false;

    /**
     * @param context -- Containing dialog
     */
    private IntroTextDialog(Activity context, Database database) {
        super(context);
        setOwnerActivity(context);
        this.database = database;
    }

    public static boolean showIntroTextIfNecessary(Activity context, Database database, boolean show) {
        boolean hidePrivacyTag = Utils.querySharedPreferenceBoolean(context, Constants.hidePrivacyTag);

        dialog = new IntroTextDialog(context, database);
        if (show && !hidePrivacyTag) {
            dialog.setCancelable(false);
            dialog.show();
            return true;
        }

        if (show) {
            // At this time, user has granted permission to collect anonymous user data, start the SDK now
//            UMConfigure.init(context, "68086a69bc47b67d83466552", "DEFAULT", UMConfigure.DEVICE_TYPE_PHONE, "");
        }

        return false;
    }

    public static void showIntroText(Activity context, Database database) {
        if (dialog == null) {
            dialog = new IntroTextDialog(context, database);
        }

        dialog.show();
    }

    /* (non-Javadoc)
     * @see android.app.Dialog#onCreate(android.os.Bundle)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String pkgName = Utils.pName(this.getContext());

        // do not use original's donate text
        donate = false;

        setContentView(R.layout.intro_dialog);
        getWindow().setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);

        Context context = this.getContext();
        String title = getContext().getResources().getString(R.string.intro_title);

        StringBuilder sb = new StringBuilder(title);
        setTitle(sb);
        sb.delete(0, sb.length());

        sb.append(getContext().getResources().getString(R.string.rdp_intro_text));

        sb.append("\n");
        sb.append(getContext().getResources().getString(R.string.intro_version_text));
        TextView introTextView = (TextView) findViewById(R.id.textIntroText);
        introTextView.setText(Html.fromHtml(sb.toString()));
        introTextView.setMovementMethod(LinkMovementMethod.getInstance());
        ((Button) findViewById(R.id.buttonCloseIntro)).setOnClickListener(new View.OnClickListener() {

            /* (non-Javadoc)
             * @see android.view.View.OnClickListener#onClick(android.view.View)
             */
            @Override
            public void onClick(View v) {
                showAgain(true);

                SystemClock.sleep(200);

                android.os.Process.killProcess(android.os.Process.myPid());
            }

        });

        Button buttonCloseIntroDontShow = (Button) findViewById(R.id.buttonCloseIntroDontShow);
//        if (donate) {
//            buttonCloseIntroDontShow.setVisibility(View.GONE);
//        } else {
            buttonCloseIntroDontShow.setOnClickListener(new View.OnClickListener() {

                /* (non-Javadoc)
                 * @see android.view.View.OnClickListener#onClick(android.view.View)
                 */
                @Override
                public void onClick(View v) {
                    showAgain(false);
                }

            });
//        }
    }

    /* (non-Javadoc)
     * @see android.app.Dialog#onCreateOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getOwnerActivity().getMenuInflater().inflate(R.menu.intro_dialog_menu, menu);
        // Disabling Manual/Wiki Menu item as the original does not correspond to this project anymore.
        /*
        menu.findItem(R.id.itemOpenDoc).setOnMenuItemClickListener(new OnMenuItemClickListener() {

            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Utils.showDocumentation(getOwnerActivity());
                dismiss();
                return true;
            }
        });
        */
        menu.findItem(R.id.itemClose).setOnMenuItemClickListener(new OnMenuItemClickListener() {

            @Override
            public boolean onMenuItemClick(MenuItem item) {
                showAgain(true);
                return true;
            }
        });
        menu.findItem(R.id.itemDontShowAgain).setOnMenuItemClickListener(new OnMenuItemClickListener() {

            @Override
            public boolean onMenuItemClick(MenuItem item) {
                showAgain(false);
                return true;
            }
        });
        return true;
    }

    /*
     * (non-Javadoc)
     * @see android.app.Dialog#onBackPressed()
     */
    @Override
    public void onBackPressed() {
        showAgain(true);
    }

    private void showAgain(boolean show) {
        Utils.setSharedPreferenceBoolean(getContext(), Constants.hidePrivacyTag, !show);
        dismiss();
        dialog = null;
    }
}
