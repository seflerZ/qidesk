package com.qihua.bVNC;

import android.content.ContentValues;
import android.content.Context;
import android.os.PersistableBundle;
import android.widget.ImageView;

import com.google.android.gms.common.internal.ResourceUtils;
import com.undatech.opaque.Connection;
import com.qihua.bVNC.R;

public class NewConnection implements Connection {
    @Override
    public String getLabel() {
        return "$NEW$";
    }

    @Override
    public String getId() {
        return "NC";
    }

    @Override
    public String getRuntimeId() {
        return "$NC";
    }

    @Override
    public void setRuntimeId(String id) {

    }

    @Override
    public String getNickname() {
        return null;
    }

    @Override
    public void setNickname(String nickname) {

    }

    @Override
    public int getConnectionType() {
        return 0;
    }

    @Override
    public void setConnectionType(int connectionType) {

    }

    @Override
    public String getConnectionTypeString() {
        return null;
    }

    @Override
    public void setConnectionTypeString(String connectionTypeString) {

    }

    @Override
    public String getInputMode() {
        return null;
    }

    @Override
    public void setInputMode(String inputMode) {

    }

    @Override
    public int getExtraKeysToggleType() {
        return 0;
    }

    @Override
    public void setExtraKeysToggleType(int extraKeysToggleType) {

    }

    @Override
    public String getHostname() {
        return null;
    }

    @Override
    public void setHostname(String hostname) {

    }

    @Override
    public String getVmname() {
        return null;
    }

    @Override
    public void setVmname(String vmname) {

    }

    @Override
    public String getUserName() {
        return null;
    }

    @Override
    public void setUserName(String user) {

    }

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public void setPassword(String password) {

    }

    @Override
    public boolean getKeepPassword() {
        return false;
    }

    @Override
    public void setKeepPassword(boolean keepPassword) {

    }

    @Override
    public String getOtpCode() {
        return null;
    }

    @Override
    public void setOtpCode(String otpCode) {

    }

    @Override
    public String getFilename() {
        return null;
    }

    @Override
    public void setFilename(String filename) {

    }

    @Override
    public boolean isRotationEnabled() {
        return false;
    }

    @Override
    public void setRotationEnabled(boolean rotationEnabled) {

    }

    @Override
    public boolean isRequestingNewDisplayResolution() {
        return false;
    }

    @Override
    public void setRequestingNewDisplayResolution(boolean requestingNewDisplayResolution) {

    }

    @Override
    public boolean isAudioPlaybackEnabled() {
        return false;
    }

    @Override
    public void setAudioPlaybackEnabled(boolean audioPlaybackEnabled) {

    }

    @Override
    public boolean isUsingCustomOvirtCa() {
        return false;
    }

    @Override
    public void setUsingCustomOvirtCa(boolean useCustomCa) {

    }

    @Override
    public boolean isSslStrict() {
        return false;
    }

    @Override
    public void setSslStrict(boolean sslStrict) {

    }

    @Override
    public boolean isUsbEnabled() {
        return false;
    }

    @Override
    public void setUsbEnabled(boolean usbEnabled) {

    }

    @Override
    public String getOvirtCaFile() {
        return null;
    }

    @Override
    public void setOvirtCaFile(String ovirtCaFile) {

    }

    @Override
    public String getOvirtCaData() {
        return null;
    }

    @Override
    public void setOvirtCaData(String ovirtCaData) {

    }

    @Override
    public String getLayoutMap() {
        return null;
    }

    @Override
    public void setLayoutMap(String layoutMap) {

    }

    @Override
    public void saveAndWriteRecent(boolean saveEmpty, Context c) {

    }

    @Override
    public void save(Context context) {

    }

    @Override
    public void load(Context context) {

    }

    @Override
    public String getAddress() {
        return null;
    }

    @Override
    public void setAddress(String address) {

    }

    @Override
    public int getPort() {
        return 0;
    }

    @Override
    public void setPort(int port) {

    }

    @Override
    public int getTlsPort() {
        return 0;
    }

    @Override
    public void setTlsPort(int port) {

    }

    @Override
    public ImageView.ScaleType getScaleMode() {
        return null;
    }

    @Override
    public void setScaleMode(ImageView.ScaleType value) {

    }

    @Override
    public int getRdpResType() {
        return 0;
    }

    @Override
    public void setRdpResType(int rdpResType) {

    }

    @Override
    public boolean getFollowMouse() {
        return false;
    }

    @Override
    public void setFollowMouse(boolean followMouse) {

    }

    @Override
    public boolean getFollowPan() {
        return false;
    }

    @Override
    public void setFollowPan(boolean followPan) {

    }

    @Override
    public long getLastMetaKeyId() {
        return 0;
    }

    @Override
    public void setLastMetaKeyId(long lastMetaKeyId) {

    }

    @Override
    public boolean getUseDpadAsArrows() {
        return false;
    }

    @Override
    public void setUseDpadAsArrows(boolean useDpadAsArrows) {

    }

    @Override
    public String getColorModel() {
        return null;
    }

    @Override
    public void setColorModel(String colorModel) {

    }

    @Override
    public boolean getRotateDpad() {
        return false;
    }

    @Override
    public void setRotateDpad(boolean rotateDpad) {

    }

    @Override
    public String getIdHash() {
        return null;
    }

    @Override
    public void setIdHash(String idHash) {

    }

    @Override
    public int getIdHashAlgorithm() {
        return 0;
    }

    @Override
    public void setIdHashAlgorithm(int idHashAlgorithm) {

    }

    @Override
    public int getPrefEncoding() {
        return 0;
    }

    @Override
    public void setPrefEncoding(int prefEncoding) {

    }

    @Override
    public boolean getUseRepeater() {
        return false;
    }

    @Override
    public void setUseRepeater(boolean useRepeater) {

    }

    @Override
    public String getRepeaterId() {
        return null;
    }

    @Override
    public void setRepeaterId(String repeaterId) {

    }

    @Override
    public String saveCaToFile(Context context, String caCertData) {
        return null;
    }

    @Override
    public void populateFromContentValues(ContentValues values) {

    }

    @Override
    public void populateFromPersistentBundle(PersistableBundle bundle) {

    }

    @Override
    public boolean isReadyForConnection() {
        return false;
    }

    @Override
    public void setReadyForConnection(boolean readyToBeSaved) {

    }

    @Override
    public boolean isReadyToBeSaved() {
        return false;
    }

    @Override
    public void setReadyToBeSaved(boolean readyToBeSaved) {

    }

    @Override
    public void determineIfReadyForConnection(int secType) {

    }

    @Override
    public String getCaCert() {
        return null;
    }

    @Override
    public void setCaCert(String caCert) {

    }

    @Override
    public String getCaCertPath() {
        return null;
    }

    @Override
    public void setCaCertPath(String caCertPath) {

    }

    @Override
    public String getCertSubject() {
        return null;
    }

    @Override
    public void setCertSubject(String certSubject) {

    }

    @Override
    public String getRdpDomain() {
        return null;
    }

    @Override
    public void setRdpDomain(String rdpDomain) {

    }

    @Override
    public int getRdpWidth() {
        return 0;
    }

    @Override
    public void setRdpWidth(int rdpWidth) {

    }

    @Override
    public int getRdpHeight() {
        return 0;
    }

    @Override
    public void setRdpHeight(int rdpHeight) {

    }

    @Override
    public int getRdpColor() {
        return 0;
    }

    @Override
    public void setRdpColor(int rdpColor) {

    }

    @Override
    public boolean getRemoteFx() {
        return false;
    }

    @Override
    public void setRemoteFx(boolean remoteFx) {

    }

    @Override
    public boolean getDesktopBackground() {
        return false;
    }

    @Override
    public void setDesktopBackground(boolean desktopBackground) {

    }

    @Override
    public boolean getFontSmoothing() {
        return false;
    }

    @Override
    public void setFontSmoothing(boolean fontSmoothing) {

    }

    @Override
    public boolean getDesktopComposition() {
        return false;
    }

    @Override
    public void setDesktopComposition(boolean desktopComposition) {

    }

    @Override
    public boolean getWindowContents() {
        return false;
    }

    @Override
    public void setWindowContents(boolean windowContents) {

    }

    @Override
    public boolean getMenuAnimation() {
        return false;
    }

    @Override
    public void setMenuAnimation(boolean menuAnimation) {

    }

    @Override
    public boolean getVisualStyles() {
        return false;
    }

    @Override
    public void setVisualStyles(boolean visualStyles) {

    }

    @Override
    public boolean getRedirectSdCard() {
        return false;
    }

    @Override
    public void setRedirectSdCard(boolean redirectSdCard) {

    }

    @Override
    public boolean getConsoleMode() {
        return false;
    }

    @Override
    public void setConsoleMode(boolean consoleMode) {

    }

    @Override
    public boolean getEnableSound() {
        return false;
    }

    @Override
    public void setEnableSound(boolean enableSound) {

    }

    @Override
    public boolean getEnableRecording() {
        return false;
    }

    @Override
    public void setEnableRecording(boolean enableRecording) {

    }

    @Override
    public int getRemoteSoundType() {
        return 0;
    }

    @Override
    public void setRemoteSoundType(int remoteSoundType) {

    }

    @Override
    public boolean getViewOnly() {
        return false;
    }

    @Override
    public void setViewOnly(boolean viewOnly) {

    }

    @Override
    public long getForceFull() {
        return 0;
    }

    @Override
    public void setForceFull(long forceFull) {

    }

    @Override
    public int getUseLocalCursor() {
        return 0;
    }

    @Override
    public void setUseLocalCursor(int useLocalCursor) {

    }

    @Override
    public String getSshServer() {
        return null;
    }

    @Override
    public void setSshServer(String sshServer) {

    }

    @Override
    public int getSshPort() {
        return 0;
    }

    @Override
    public void setSshPort(int sshPort) {

    }

    @Override
    public String getSshUser() {
        return null;
    }

    @Override
    public void setSshUser(String sshUser) {

    }

    @Override
    public String getSshPassword() {
        return null;
    }

    @Override
    public void setSshPassword(String sshPassword) {

    }

    @Override
    public boolean getKeepSshPassword() {
        return false;
    }

    @Override
    public void setKeepSshPassword(boolean keepSshPassword) {

    }

    @Override
    public String getSshPubKey() {
        return null;
    }

    @Override
    public void setSshPubKey(String sshPubKey) {

    }

    @Override
    public String getSshPrivKey() {
        return null;
    }

    @Override
    public void setSshPrivKey(String sshPrivKey) {

    }

    @Override
    public String getSshPassPhrase() {
        return null;
    }

    @Override
    public void setSshPassPhrase(String sshPassPhrase) {

    }

    @Override
    public boolean getUseSshPubKey() {
        return false;
    }

    @Override
    public void setUseSshPubKey(boolean useSshPubKey) {

    }

    @Override
    public int getSshRemoteCommandOS() {
        return 0;
    }

    @Override
    public void setSshRemoteCommandOS(int sshRemoteCommandOS) {

    }

    @Override
    public int getSshRemoteCommandType() {
        return 0;
    }

    @Override
    public void setSshRemoteCommandType(int sshRemoteCommandType) {

    }

    @Override
    public int getAutoXType() {
        return 0;
    }

    @Override
    public void setAutoXType(int autoXType) {

    }

    @Override
    public String getAutoXCommand() {
        return null;
    }

    @Override
    public void setAutoXCommand(String autoXCommand) {

    }

    @Override
    public boolean getAutoXEnabled() {
        return false;
    }

    @Override
    public void setAutoXEnabled(boolean autoXEnabled) {

    }

    @Override
    public int getAutoXResType() {
        return 0;
    }

    @Override
    public void setAutoXResType(int autoXResType) {

    }

    @Override
    public int getAutoXWidth() {
        return 0;
    }

    @Override
    public void setAutoXWidth(int autoXWidth) {

    }

    @Override
    public int getAutoXHeight() {
        return 0;
    }

    @Override
    public void setAutoXHeight(int autoXHeight) {

    }

    @Override
    public String getAutoXSessionProg() {
        return null;
    }

    @Override
    public void setAutoXSessionProg(String autoXSessionProg) {

    }

    @Override
    public int getAutoXSessionType() {
        return 0;
    }

    @Override
    public void setAutoXSessionType(int autoXSessionType) {

    }

    @Override
    public boolean getAutoXUnixpw() {
        return false;
    }

    @Override
    public void setAutoXUnixpw(boolean autoXUnixpw) {

    }

    @Override
    public boolean getAutoXUnixAuth() {
        return false;
    }

    @Override
    public void setAutoXUnixAuth(boolean autoXUnixAuth) {

    }

    @Override
    public String getAutoXRandFileNm() {
        return null;
    }

    @Override
    public void setAutoXRandFileNm(String autoXRandFileNm) {

    }

    @Override
    public String getSshRemoteCommand() {
        return null;
    }

    @Override
    public void setSshRemoteCommand(String sshRemoteCommand) {

    }

    @Override
    public int getSshRemoteCommandTimeout() {
        return 0;
    }

    @Override
    public void setSshRemoteCommandTimeout(int sshRemoteCommandTimeout) {

    }

    @Override
    public boolean getUseSshRemoteCommand() {
        return false;
    }

    @Override
    public void setUseSshRemoteCommand(boolean useSshRemoteCommand) {

    }

    @Override
    public String getSshHostKey() {
        return null;
    }

    @Override
    public void setSshHostKey(String sshHostKey) {

    }

    @Override
    public long getMetaListId() {
        return 0;
    }

    @Override
    public void setMetaListId(long metaListId) {

    }

    @Override
    public String getScreenshotFilename() {
        return null;
    }

    @Override
    public void setScreenshotFilename(String screenShotFilename) {

    }

    @Override
    public String getX509KeySignature() {
        return null;
    }

    @Override
    public void setX509KeySignature(String x509KeySignature) {

    }

    @Override
    public boolean getEnableGfx() {
        return false;
    }

    @Override
    public void setEnableGfx(boolean enableGfx) {

    }

    @Override
    public boolean getEnableGfxH264() {
        return false;
    }

    @Override
    public void setEnableGfxH264(boolean enableGfxH264) {

    }

    @Override
    public boolean getUseLastPositionToolbar() {
        return false;
    }

    @Override
    public void setUseLastPositionToolbar(boolean useLastPositionToolbar) {

    }

    @Override
    public int getUseLastPositionToolbarX() {
        return 0;
    }

    @Override
    public void setUseLastPositionToolbarX(int useLastPositionToolbarX) {

    }

    @Override
    public float getLastZoomFactor() {
        return 0;
    }

    @Override
    public boolean getEnableGesture() {
        return true;
    }

    @Override
    public void setLastZoomFactor(float lastScaleFactor) {

    }

    @Override
    public int getUseLastPositionToolbarY() {
        return 0;
    }

    @Override
    public void setUseLastPositionToolbarY(int useLastPositionToolbarY) {

    }

    @Override
    public boolean getUseLastPositionToolbarMoved() {
        return false;
    }

    @Override
    public void setUseLastPositionToolbarMoved(boolean useLastPositionToolbarMoved) {

    }

    @Override
    public boolean getPreferSendingUnicode() {
        return false;
    }

    @Override
    public void setPreferSendingUnicode(boolean preferSendingUnicode) {

    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public void setPriority(int priority) {

    }

    @Override
    public int getZoomLevel() {
        return 100;
    }
}
