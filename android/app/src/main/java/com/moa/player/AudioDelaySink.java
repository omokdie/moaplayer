package com.moa.player;

import androidx.media3.common.C;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

import java.nio.ByteBuffer;

/**
 * Moves the clock that the picture and the subtitles follow. Media3 times video frames by the audio
 * position the system reports; with some Bluetooth headsets the sound reaches the ears later than
 * reported, so the picture runs ahead of the voice. A positive offset holds the picture back by that
 * much (sound heard late); a negative one moves it ahead (sound heard early).
 *
 * <p>The shifted clock never goes back before the first sample after a start or seek: the picture
 * simply waits on its first frame until the sound has caught up.
 */
final class AudioDelaySink extends ForwardingAudioSink {
    /** Same for every player in the process: there is only one audio output. */
    private static volatile long offsetUs;

    /** First sample time after a start, seek or flush (C.TIME_UNSET until audio arrives). */
    private long floorUs = C.TIME_UNSET;

    AudioDelaySink(AudioSink sink) {
        super(sink);
    }

    static void setOffsetMs(int ms) {
        offsetUs = ms * 1000L;
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        long positionUs = super.getCurrentPositionUs(sourceEnded);
        long off = offsetUs;
        if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET || off == 0) return positionUs;
        // the delay is real time; positions are media time (2x speed: twice as much media per second)
        float speed = getPlaybackParameters().speed;
        long shifted = positionUs - (long) (off * (speed > 0 ? speed : 1f));
        if (floorUs != C.TIME_UNSET && shifted < floorUs) shifted = floorUs;
        return shifted;
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws AudioSink.InitializationException, AudioSink.WriteException {
        if (floorUs == C.TIME_UNSET) floorUs = Math.max(0, presentationTimeUs);
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }

    @Override
    public void flush() {
        floorUs = C.TIME_UNSET;
        super.flush();
    }

    @Override
    public void reset() {
        floorUs = C.TIME_UNSET;
        super.reset();
    }
}
