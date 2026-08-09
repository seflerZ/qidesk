package com.qihua.bVNC;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.qihua.bVNC.R;

public class GlobalPreferencesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(getString(R.string.action_edit_default_settings));

        setContentView(R.layout.global_preferences_activity);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.globalPreferencesContainer, new GlobalPreferencesFragment())
                .commit();
    }
}
