package io.github.saltlyrics.plugin;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import org.pf4j.Extension;

@Extension
public final class LyricsExtension implements PlaybackExtensionPoint {
    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        return BilingualLyricsPlugin.load(mediaItem.getPath());
    }
}
