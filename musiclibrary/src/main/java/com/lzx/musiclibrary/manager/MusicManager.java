package com.lzx.musiclibrary.manager;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;

import com.danikula.videocache.ProxyCacheUtils;
import com.lzx.musiclibrary.aidl.listener.OnPlayerEventListener;
import com.lzx.musiclibrary.aidl.listener.OnTimerTaskListener;
import com.lzx.musiclibrary.aidl.model.SongInfo;
import com.lzx.musiclibrary.aidl.source.IOnPlayerEventListener;
import com.lzx.musiclibrary.aidl.source.IOnTimerTaskListener;
import com.lzx.musiclibrary.aidl.source.IPlayControl;
import com.lzx.musiclibrary.cache.CacheConfig;
import com.lzx.musiclibrary.cache.CacheUtils;
import com.lzx.musiclibrary.constans.State;
import com.lzx.musiclibrary.notification.NotificationCreater;
import com.lzx.musiclibrary.playback.PlayStateObservable;
import com.lzx.musiclibrary.utils.LogUtil;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Observer;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * lzx
 * 2018/1/22
 */

public class MusicManager implements IPlayControl {

    public static final int MSG_MUSIC_CHANGE = 0;
    public static final int MSG_PLAYER_START = 1;
    public static final int MSG_PLAYER_PAUSE = 2;
    public static final int MSG_PLAY_COMPLETION = 3;
    public static final int MSG_PLAYER_ERROR = 4;
    public static final int MSG_BUFFERING = 5;
    public static final int MSG_TIMER_FINISH = 6;
    public static final int MSG_TIMER_TICK = 7;
    public static final int MSG_PLAYER_STOP = 8;

    private Context mContext;
    private boolean isOpenCacheWhenPlaying = false;
    private IPlayControl control;
    private CacheConfig mCacheConfig;
    private ClientHandler mClientHandler;
    private PlayStateObservable mStateObservable;
    private CopyOnWriteArrayList<OnPlayerEventListener> mPlayerEventListeners = new CopyOnWriteArrayList<>();
    private CopyOnWriteArrayList<OnTimerTaskListener> mOnTimerTaskListeners = new CopyOnWriteArrayList<>();

    private static final byte[] sLock = new byte[0];

    private static volatile MusicManager sInstance;

    public static MusicManager get() {
        if (sInstance == null) {
            synchronized (sLock) {
                if (sInstance == null) {
                    sInstance = new MusicManager();
                }
            }
        }
        return sInstance;
    }

    private MusicManager() {
        mClientHandler = new ClientHandler(this);
        mStateObservable = new PlayStateObservable();
    }

    void attachPlayControl(Context context, IPlayControl control) {
        this.mContext = context;
        this.control = control;
        try {
            control.registerPlayerEventListener(mOnPlayerEventListener);
            control.registerTimerTaskListener(mOnTimerTaskListener);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    void attachMusicLibraryBuilder(MusicLibrary.Builder builder) {
        this.mCacheConfig = builder.getCacheConfig();
        if (mCacheConfig != null) {
            isOpenCacheWhenPlaying = mCacheConfig.isOpenCacheWhenPlaying();
        }
    }

    void stopService() {
        try {
            if (control != null && control.asBinder().isBinderAlive()) {
                control.unregisterPlayerEventListener(mOnPlayerEventListener);
                control.unregisterTimerTaskListener(mOnTimerTaskListener);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    /**
     * ???????????????????????????
     */
    public void addStateObservable(Observer o) {
        if (mStateObservable != null) {
            mStateObservable.addObserver(o);
        }
    }

    /**
     * ???????????????????????????
     */
    public void deleteStateObservable(Observer o) {
        if (mStateObservable != null) {
            mStateObservable.deleteObserver(o);
        }
    }

    /**
     * ???????????????????????????
     */
    public void clearStateObservable() {
        if (mStateObservable != null) {
            mStateObservable.deleteObservers();
        }
    }

    private IOnPlayerEventListener mOnPlayerEventListener = new IOnPlayerEventListener.Stub() {
        @Override
        public void onMusicSwitch(SongInfo music) {
            mClientHandler.obtainMessage(MSG_MUSIC_CHANGE, music).sendToTarget();
        }

        @Override
        public void onPlayerStart() {
            mClientHandler.obtainMessage(MSG_PLAYER_START).sendToTarget();
        }

        @Override
        public void onPlayerPause() {
            mClientHandler.obtainMessage(MSG_PLAYER_PAUSE).sendToTarget();
        }

        @Override
        public void onPlayCompletion(SongInfo songInfo) {
            mClientHandler.obtainMessage(MSG_PLAY_COMPLETION, songInfo).sendToTarget();
        }

        @Override
        public void onPlayerStop() {
            mClientHandler.obtainMessage(MSG_PLAYER_STOP).sendToTarget();
        }

        @Override
        public void onError(String errorMsg) {
            mClientHandler.obtainMessage(MSG_PLAYER_ERROR, errorMsg).sendToTarget();
        }

        @Override
        public void onAsyncLoading(boolean isFinishLoading) {
            mClientHandler.obtainMessage(MSG_BUFFERING, isFinishLoading).sendToTarget();
        }
    };

    private IOnTimerTaskListener mOnTimerTaskListener = new IOnTimerTaskListener.Stub() {
        @Override
        public void onTimerFinish() {
            mClientHandler.obtainMessage(MSG_TIMER_FINISH).sendToTarget();
        }

        @Override
        public void onTimerTick(long millisUntilFinished, long totalTime) {
            Bundle bundle = new Bundle();
            bundle.putLong("millisUntilFinished", millisUntilFinished);
            bundle.putLong("totalTime", totalTime);
            Message message = Message.obtain();
            message.setData(bundle);
            message.what = MSG_TIMER_TICK;
            mClientHandler.sendMessage(message);
        }
    };

    private static class ClientHandler extends Handler {
        private final WeakReference<MusicManager> mWeakReference;

        ClientHandler(MusicManager musicManager) {
            super(Looper.getMainLooper());
            mWeakReference = new WeakReference<>(musicManager);
        }

        @Override
        public void handleMessage(Message msg) {
            MusicManager manager = mWeakReference.get();
            switch (msg.what) {
                case MSG_MUSIC_CHANGE:
                    SongInfo musicInfo = (SongInfo) msg.obj;
                    manager.notifyPlayerEventChange(MSG_MUSIC_CHANGE, musicInfo, "", false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_MUSIC_CHANGE);
                    break;
                case MSG_PLAYER_START:
                    manager.notifyPlayerEventChange(MSG_PLAYER_START, null, "", false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_PLAYER_START);
                    break;
                case MSG_PLAYER_PAUSE:
                    manager.notifyPlayerEventChange(MSG_PLAYER_PAUSE, null, "", false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_PLAYER_PAUSE);
                    break;
                case MSG_PLAY_COMPLETION:
                    SongInfo songInfo = (SongInfo) msg.obj;
                    manager.notifyPlayerEventChange(MSG_PLAY_COMPLETION, songInfo, "", false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_PLAY_COMPLETION);
                    break;
                case MSG_PLAYER_ERROR:
                    String errMsg = (String) msg.obj;
                    manager.notifyPlayerEventChange(MSG_PLAYER_ERROR, null, errMsg, false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_PLAYER_ERROR);
                    break;
                case MSG_BUFFERING:
                    boolean isFinishLoading = (boolean) msg.obj;
                    manager.notifyPlayerEventChange(MSG_BUFFERING, null, "", isFinishLoading);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_BUFFERING);
                    break;
                case MSG_TIMER_FINISH:
                    manager.notifyTimerTaskEventChange(MSG_TIMER_FINISH, -1, -1);
                    break;
                case MSG_TIMER_TICK:
                    Bundle bundle = msg.getData();
                    long millisUntilFinished = bundle.getLong("millisUntilFinished");
                    long totalTime = bundle.getLong("totalTime");
                    manager.notifyTimerTaskEventChange(MSG_TIMER_TICK, millisUntilFinished, totalTime);
                    break;
                case MSG_PLAYER_STOP:
                    manager.notifyPlayerEventChange(MSG_PLAYER_STOP, null, null, false);
                    manager.mStateObservable.stateChangeNotifyObservers(MSG_PLAYER_STOP);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    /**
     * ????????????????????????
     */
    public void addPlayerEventListener(OnPlayerEventListener listener) {
        if (listener != null) {
            if (!mPlayerEventListeners.contains(listener)) {
                mPlayerEventListeners.add(listener);
            }
        }
    }

    /**
     * ????????????????????????
     */
    public void removePlayerEventListener(OnPlayerEventListener listener) {
        if (listener != null) {
            mPlayerEventListeners.remove(listener);
        }
    }

    /**
     * ????????????????????????
     */
    public void clearPlayerEventListener() {
        mPlayerEventListeners.clear();
    }

    /**
     * ????????????????????????
     */
    public void addTimerTaskEventListener(OnTimerTaskListener listener) {
        if (listener != null) {
            if (!mOnTimerTaskListeners.contains(listener)) {
                mOnTimerTaskListeners.add(listener);
            }
        }
    }

    /**
     * ????????????????????????
     */
    public void removeTimerTaskEventListener(OnTimerTaskListener listener) {
        if (listener != null) {
            mOnTimerTaskListeners.remove(listener);
        }
    }

    /**
     * ????????????????????????
     */
    public void clearTimerTaskEventListener() {
        mOnTimerTaskListeners.clear();
    }

    private void notifyPlayerEventChange(int msg, SongInfo info, String errorMsg, boolean isFinishBuffer) {
        for (OnPlayerEventListener listener : mPlayerEventListeners) {
            switch (msg) {
                case MSG_MUSIC_CHANGE:
                    listener.onMusicSwitch(info);
                    break;
                case MSG_PLAYER_START:
                    listener.onPlayerStart();
                    break;
                case MSG_PLAYER_PAUSE:
                    listener.onPlayerPause();
                    break;
                case MSG_PLAY_COMPLETION:
                    listener.onPlayCompletion(info);
                    break;
                case MSG_PLAYER_ERROR:
                    listener.onError(errorMsg);
                    break;
                case MSG_BUFFERING:
                    listener.onAsyncLoading(isFinishBuffer);
                    break;
                case MSG_PLAYER_STOP:
                    listener.onPlayerStop();
                    break;
            }
        }
    }

    private void notifyTimerTaskEventChange(int msg, long millisUntilFinished, long totalTime) {
        for (OnTimerTaskListener listener : mOnTimerTaskListeners) {
            if (msg == MSG_TIMER_FINISH) {
                listener.onTimerFinish();
            } else if (msg == MSG_TIMER_TICK) {
                listener.onTimerTick(millisUntilFinished, totalTime);
            }
        }
    }

    @Override
    public void playMusic(List<SongInfo> list, int index, boolean isJustPlay) {
        if (control != null) {
            try {
                control.playMusic(list, index, isJustPlay);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void playMusic(List<SongInfo> list, int index) {
        playMusic(list, index, false);
    }

    @Override
    public void playMusicByInfo(SongInfo info, boolean isJustPlay) {
        if (control != null) {
            try {
                control.playMusicByInfo(info, isJustPlay);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void playMusicByInfo(SongInfo info) {
        playMusicByInfo(info, false);
    }

    @Override
    public void playMusicByIndex(int index, boolean isJustPlay) {
        if (control != null) {
            try {
                control.playMusicByIndex(index, isJustPlay);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public void playMusicByIndex(int index) {
        playMusicByIndex(index, false);
    }

    @Override
    public void pausePlayInMillis(long time) {
        if (control != null) {
            try {
                control.pausePlayInMillis(time);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int getCurrPlayingIndex() {
        if (control != null) {
            try {
                return control.getCurrPlayingIndex();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public void pauseMusic() {
        if (control != null) {
            try {
                control.pauseMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void resumeMusic() {
        if (control != null) {
            try {
                control.resumeMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void stopMusic() {
        if (control != null) {
            try {
                control.stopMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPlayList(List<SongInfo> list) {
        if (control != null) {
            try {
                control.setPlayList(list);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPlayListWithIndex(List<SongInfo> list, int index) {
        if (control != null) {
            try {
                control.setPlayListWithIndex(list, index);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public List<SongInfo> getPlayList() {
        if (control != null) {
            try {
                return control.getPlayList();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void deleteSongInfoOnPlayList(SongInfo info, boolean isNeedToPlayNext) {
        if (control != null) {
            try {
                control.deleteSongInfoOnPlayList(info, isNeedToPlayNext);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int getStatus() {
        if (control != null) {
            try {
                return control.getStatus();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public int getDuration() {
        if (control != null) {
            try {
                return control.getDuration();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public void playNext() {
        if (control != null) {
            try {
                control.playNext();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void playPre() {
        if (control != null) {
            try {
                control.playPre();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean hasPre() {
        if (control != null) {
            try {
                return control.hasPre();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public boolean hasNext() {
        if (control != null) {
            try {
                return control.hasNext();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    @Override
    public SongInfo getPreMusic() {
        if (control != null) {
            try {
                return control.getPreMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public SongInfo getNextMusic() {
        if (control != null) {
            try {
                return control.getNextMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public SongInfo getCurrPlayingMusic() {
        if (control != null) {
            try {
                return control.getCurrPlayingMusic();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void setCurrMusic(int index) {
        if (control != null) {
            try {
                control.setCurrMusic(index);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPlayMode(int mode) {
        if (control != null) {
            try {
                control.setPlayMode(mode);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int getPlayMode() {
        if (control != null) {
            try {
                return control.getPlayMode();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public long getProgress() {
        if (control != null) {
            try {
                return control.getProgress();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public void seekTo(int position) {
        if (control != null) {
            try {
                control.seekTo(position);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void reset() {
        if (control != null) {
            try {
                control.reset();
                setPlayList(new ArrayList<SongInfo>());
                //clearPlayerEventListener();
                //clearStateObservable();
                //clearTimerTaskEventListener();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void openCacheWhenPlaying(boolean isOpen) {
        if (control != null) {
            try {
                isOpenCacheWhenPlaying = isOpen;
                control.openCacheWhenPlaying(isOpen);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void stopNotification() {
        if (control != null) {
            try {
                control.stopNotification();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void setPlaybackParameters(float speed, float pitch) {
        if (control != null) {
            try {
                if (speed > 0 && pitch > 0) {
                    control.setPlaybackParameters(speed, pitch);
                }
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public long getBufferedPosition() {
        if (control != null) {
            try {
                return control.getBufferedPosition();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public void setVolume(float audioVolume) {
        if (control != null) {
            try {
                if (audioVolume < 0) {
                    audioVolume = 0;
                }
                if (audioVolume > 1) {
                    audioVolume = 1;
                }
                control.setVolume(audioVolume);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void updateNotificationCreater(NotificationCreater creater) {
        if (control != null) {
            try {
                control.updateNotificationCreater(creater);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void updateNotificationFavorite(boolean isFavorite) {
        if (control != null) {
            try {
                control.updateNotificationFavorite(isFavorite);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void updateNotificationLyrics(boolean isChecked) {
        if (control != null) {
            try {
                control.updateNotificationLyrics(isChecked);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void updateNotificationContentIntent(Bundle bundle, String targetClass) {
        if (control != null) {
            try {
                control.updateNotificationContentIntent(bundle, targetClass);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param currMusic
     * @return ???????????????????????????????????????????????????
     */
    public static boolean isCurrMusicIsPlayingMusic(SongInfo currMusic) {
        SongInfo playingMusic = MusicManager.get().getCurrPlayingMusic();
        return playingMusic != null && currMusic.getSongId().equals(playingMusic.getSongId());
    }

    /**
     * ???????????????
     *
     * @return ????????????
     */
    public static boolean isPaused() {
        return MusicManager.get().getStatus() == State.STATE_PAUSED;
    }

    /**
     * ??????????????????
     *
     * @return ??????????????????
     */
    public static boolean isPlaying() {
        return MusicManager.get().getStatus() == State.STATE_PLAYING;
    }

    public static boolean isIdea() {
        return MusicManager.get().getStatus() == State.STATE_IDLE;
    }

    /**
     * ??????????????????????????????
     *
     * @param currMusic
     * @return ??????????????????????????????
     */
    public static boolean isCurrMusicIsPlaying(SongInfo currMusic) {
        return isCurrMusicIsPlayingMusic(currMusic) && isPlaying();
    }

    /**
     * ???????????????????????????
     *
     * @param currMusic
     * @return ???????????????????????????
     */
    public static boolean isCurrMusicIsPaused(SongInfo currMusic) {
        return isCurrMusicIsPlayingMusic(currMusic) && isPaused();
    }

    /**
     * ???????????????
     *
     * @param songUrl
     * @return ???????????????
     */
    public boolean isFullyCached(String songUrl) {
        if (TextUtils.isEmpty(songUrl)) {
            throw new NullPointerException("song Url can't be null!");
        }
        File file = getCacheFile(songUrl);
        return file != null && file.exists();
    }

    /**
     * ??????????????????File??????
     *
     * @param songUrl
     * @return ??????????????????File??????
     */
    public File getCacheFile(String songUrl) {
        if (mCacheConfig != null && isOpenCacheWhenPlaying) {
            String cacheDir = !TextUtils.isEmpty(mCacheConfig.getCachePath())
                    ? mCacheConfig.getCachePath()
                    : CacheUtils.getDefaultSongCacheDir().getAbsolutePath();
            String fileName = ProxyCacheUtils.computeMD5(songUrl);
            return new File(cacheDir, fileName);
        } else {
            if (isOpenCacheWhenPlaying) {
                String cacheDir = CacheUtils.getDefaultSongCacheDir().getAbsolutePath();
                String fileName = ProxyCacheUtils.computeMD5(songUrl);
                return new File(cacheDir, fileName);
            } else {
                return null;
            }
        }
    }

    /**
     * ????????????????????????
     *
     * @param songUrl
     * @return ????????????????????????
     */
    public long getCachedSize(String songUrl) {
        if (isFullyCached(songUrl)) {
            File file = getCacheFile(songUrl);
            return file != null ? file.length() : 0;
        } else {
            return 0;
        }
    }

    @Override
    public int getAudioSessionId() {
        if (control != null) {
            try {
                return control.getAudioSessionId();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public float getPlaybackSpeed() {
        if (control != null) {
            try {
                return control.getPlaybackSpeed();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }

    @Override
    public float getPlaybackPitch() {
        if (control != null) {
            try {
                return control.getPlaybackPitch();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return 0;
    }


    public void setAuditionList(List<SongInfo> auditionList) {
        setPlayList(auditionList);
    }

    @Deprecated
    @Override
    public void registerPlayerEventListener(IOnPlayerEventListener listener) {
        //Do nothing
    }

    @Deprecated
    @Override
    public void unregisterPlayerEventListener(IOnPlayerEventListener listener) {
        //Do nothing
    }

    @Deprecated
    @Override
    public void registerTimerTaskListener(IOnTimerTaskListener listener) {
        //Do nothing
    }

    @Deprecated
    @Override
    public void unregisterTimerTaskListener(IOnTimerTaskListener listener) {
        //Do nothing
    }

    @Deprecated
    @Override
    public IBinder asBinder() {
        //Do nothing
        return null;
    }

}
