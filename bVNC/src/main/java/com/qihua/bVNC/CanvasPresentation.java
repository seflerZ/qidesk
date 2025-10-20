package com.qihua.bVNC;

import android.app.Presentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;

import com.qihua.bVNC.R;

public class CanvasPresentation extends Presentation {
    private RemoteCanvas canvas;

    public CanvasPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.canvas);

        canvas = findViewById(R.id.canvas);
//        canvas.setDefaultFocusHighlightEnabled(false);
    }

    public RemoteCanvas getCanvas() {
        return canvas;
    }
}
