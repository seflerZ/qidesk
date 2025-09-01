package com.qihua.bVNC;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.undatech.remoteClientUi.R;

public class GlobalPreferencesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(getString(com.freerdp.freerdpcore.R.string.settings_default));

        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new GlobalPreferencesFragment())
                .commit();
    }
}
