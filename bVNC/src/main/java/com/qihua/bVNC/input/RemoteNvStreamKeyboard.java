package com.qihua.bVNC.input;

import static com.undatech.opaque.util.GeneralUtils.debugLog;

import android.os.Handler;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.limelight.binding.input.KeyboardTranslator;
import com.qihua.bVNC.App;
import com.qihua.bVNC.RemoteCanvas;
import com.undatech.opaque.NvCommunicator;
import com.undatech.opaque.RfbConnectable;
import com.undatech.opaque.input.RdpKeyboardMapper;

public class RemoteNvStreamKeyboard extends RemoteKeyboard {
    private final static String TAG = "RemoteNvStreamKeyboard";
    protected KeyboardTranslator keyboardTranslator;
    protected RemoteCanvas canvas;
    private NvCommunicator nvcomm;

    public RemoteNvStreamKeyboard(RfbConnectable r, RemoteCanvas v, Handler h, boolean debugLog) {
        super(r, v.getContext(), h, debugLog);
        nvcomm = (NvCommunicator) r;
        canvas = v;
        keyboardTranslator = new KeyboardTranslator();
    }

    public boolean processLocalKeyEvent(int keyCode, KeyEvent evt, int additionalMetaState) {
        debugLog(App.debugLog, TAG, "processLocalKeyEvent: " + evt.toString() + " " + keyCode);
        // Drop repeated modifiers
        if (shouldDropModifierKeys(evt))
            return true;
        boolean isRepeat = evt.getRepeatCount() > 0;
        nvcomm.remoteKeyboardState.detectHardwareMetaState(evt);

        if (nvcomm != null && nvcomm.isInNormalProtocol()) {
            RemotePointer pointer = canvas.getPointer();
            boolean down = (evt.getAction() == KeyEvent.ACTION_DOWN) ||
                    (evt.getAction() == KeyEvent.ACTION_MULTIPLE);
            int metaState = additionalMetaState | convertEventMetaState(evt);

            if (keyCode == KeyEvent.KEYCODE_MENU)
                return true;                           // Ignore menu key

            if (pointer.hardwareButtonsAsMouseEvents(keyCode, evt, metaState | onScreenMetaState))
                return true;

            // Detect whether this event is coming from a default hardware keyboard.
            metaState = onScreenMetaState | metaState;

            if (keyCode == 0 && evt.getCharacters() != null /*KEYCODE_UNKNOWN*/) {
                String s = evt.getCharacters();
                nvcomm.writeClientCutText(s);

                return true;
            } else {
                // Send the key to be processed through the KeyboardMapper.
                keyCode = keyboardTranslator.translate(evt.getKeyCode(), evt.getDeviceId());

                // Update the meta-state with writeKeyEvent.
                if (down) {
                    nvcomm.writeKeyEvent(keyCode, metaState, down);
                    lastDownMetaState = metaState;
                } else {
                    nvcomm.writeKeyEvent(keyCode, lastDownMetaState, down);
                    lastDownMetaState = 0;
                }
            }



            return true;
        } else {
            return false;
        }
    }

    public void sendMetaKey(MetaKeyBean meta) {
        RemotePointer pointer = canvas.getPointer();
        int x = pointer.getX();
        int y = pointer.getY();

        if (meta.isMouseClick()) {
            //android.util.Log.i("RemoteRdpKeyboard", "is a mouse click");
            int button = meta.getMouseButtons();
            switch (button) {
                case RemoteVncPointer.MOUSE_BUTTON_LEFT:
                    pointer.leftButtonDown(x, y, meta.getMetaFlags() | onScreenMetaState);
                    break;
                case RemoteVncPointer.MOUSE_BUTTON_RIGHT:
                    pointer.rightButtonDown(x, y, meta.getMetaFlags() | onScreenMetaState);
                    break;
                case RemoteVncPointer.MOUSE_BUTTON_MIDDLE:
                    pointer.middleButtonDown(x, y, meta.getMetaFlags() | onScreenMetaState);
                    break;
                case RemoteVncPointer.MOUSE_BUTTON_SCROLL_UP:
                    pointer.scrollUp(x, y, -1, meta.getMetaFlags() | onScreenMetaState);
                    break;
                case RemoteVncPointer.MOUSE_BUTTON_SCROLL_DOWN:
                    pointer.scrollDown(x, y, -1, meta.getMetaFlags() | onScreenMetaState);
                    break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
            pointer.releaseButton(x, y, meta.getMetaFlags() | onScreenMetaState);

            //rfb.writePointerEvent(x, y, meta.getMetaFlags()|onScreenMetaState, button);
            //rfb.writePointerEvent(x, y, meta.getMetaFlags()|onScreenMetaState, 0);
        } else if (meta.equals(MetaKeyBean.keyCtrlAltDel)) {
            int savedMetaState = onScreenMetaState;
            // Update the metastate
            rfb.writeKeyEvent(0, RemoteKeyboard.CTRL_MASK | RemoteKeyboard.ALT_MASK, false);
            rfb.writeKeyEvent(0, savedMetaState, false);
        } else {
            sendKeySym(meta.getKeySym(), meta.getMetaFlags());
        }
    }
}
