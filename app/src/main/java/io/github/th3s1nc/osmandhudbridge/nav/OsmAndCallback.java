package io.github.th3s1nc.osmandhudbridge.nav;

import net.osmand.aidlapi.IOsmAndAidlCallback;
import net.osmand.aidlapi.navigation.ADirectionInfo;
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams;
import net.osmand.aidlapi.logcat.OnLogcatMessageParams;
import net.osmand.aidlapi.gpx.AGpxBitmap;
import net.osmand.aidlapi.search.SearchResult;
import android.view.KeyEvent;
import java.util.List;

public class OsmAndCallback extends IOsmAndAidlCallback.Stub {
    public interface Listener {
        void onDirection(int meters, int turnType);
    }

    public interface VoiceListener {
        void onVoice(List<String> commands, List<String> played);
    }

    private final Listener listener;
    private final VoiceListener voiceListener;

    public OsmAndCallback(Listener listener, VoiceListener voiceListener) {
        this.listener = listener;
        this.voiceListener = voiceListener;
    }

    @Override public void onSearchComplete(List<SearchResult> resultSet) {}
    @Override public void onUpdate() {}
    @Override public void onAppInitialized() {}
    @Override public void onGpxBitmapCreated(AGpxBitmap bitmap) {}
    @Override public void updateNavigationInfo(ADirectionInfo directionInfo) {
        if (directionInfo == null || listener == null) return;
        listener.onDirection(directionInfo.getDistanceTo(), directionInfo.getTurnType());
    }
    @Override public void onContextMenuButtonClicked(int buttonId, String pointId, String layerId) {}
    @Override public void onVoiceRouterNotify(OnVoiceNavigationParams params) {
        if (params == null || voiceListener == null) return;
        voiceListener.onVoice(params.getCommands(), params.getPlayed());
    }
    @Override public void onKeyEvent(KeyEvent params) {}
    @Override public void onLogcatMessage(OnLogcatMessageParams params) {}
}
