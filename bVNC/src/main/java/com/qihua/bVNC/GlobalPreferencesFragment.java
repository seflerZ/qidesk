package com.qihua.bVNC;

import android.os.Bundle;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.alibaba.fastjson2.util.StringUtils;
import com.qihua.bVNC.extrakeys.ExtraKeysConstants;
import com.undatech.remoteClientUi.R;

public class GlobalPreferencesFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        getPreferenceManager().setSharedPreferencesName(Constants.generalSettingsTag);
        setPreferencesFromResource(R.xml.global_preferences, s);
        if (Utils.isVnc(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_vnc);
        } else if (Utils.isRdp(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_exfrakeys);
            addPreferencesFromResource(R.xml.global_preferences_touchpad);
//            addPreferencesFromResource(R.xml.global_preferences_rdp);
        } else if (Utils.isSpice(getContext())) {
            addPreferencesFromResource(R.xml.global_preferences_spice);
        }

        EditTextPreference horizontalExtraKeysPref = findPreference(Constants.horizontalExtraKeysPref);
        if (horizontalExtraKeysPref != null && "".equals(horizontalExtraKeysPref.getText())) {
            horizontalExtraKeysPref.setText(ExtraKeysConstants.DEFAULT_HOR_IVALUE_EXTRA_KEYS);
        }

        EditTextPreference verticalExtraKeysPref = findPreference(Constants.verticalExtraKeysPref);
        if (verticalExtraKeysPref != null && "".equals(verticalExtraKeysPref.getText())) {
            verticalExtraKeysPref.setText(ExtraKeysConstants.DEFAULT_VER_IVALUE_EXTRA_KEYS);
        }
    }
}