package com.qihua.bVNC;

import android.os.Bundle;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.qihua.bVNC.extrakeys.ExtraKeysConstants;

public class GlobalPreferencesFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle bundle, String s) {
        getPreferenceManager().setSharedPreferencesName(Constants.generalSettingsTag);
        setPreferencesFromResource(R.xml.global_preferences, s);

        addPreferencesFromResource(R.xml.global_preferences_exfrakeys);
        addPreferencesFromResource(R.xml.global_preferences_touchpad);
//      addPreferencesFromResource(R.xml.global_preferences_rdp);

        EditTextPreference horizontalExtraKeysPref = findPreference(Constants.horizontalExtraKeysPref);
        if (horizontalExtraKeysPref != null && "".equals(horizontalExtraKeysPref.getText())) {
            horizontalExtraKeysPref.setText(ExtraKeysConstants.DEFAULT_HOR_IVALUE_EXTRA_KEYS);
        }

        EditTextPreference verticalExtraKeysPref = findPreference(Constants.verticalExtraKeysPref);
        if (verticalExtraKeysPref != null && "".equals(verticalExtraKeysPref.getText())) {
            verticalExtraKeysPref.setText(ExtraKeysConstants.DEFAULT_VER_IVALUE_EXTRA_KEYS);
        }

        // Disable edge wheel preference in free version
        SwitchPreferenceCompat edgeWheelPref = findPreference(Constants.touchpadEdgeWheel);
        if (edgeWheelPref != null) {
            edgeWheelPref.setEnabled(BuildConfig.EDGE_ENABLED);
        }
    }
}