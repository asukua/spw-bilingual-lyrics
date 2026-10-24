package io.github.saltlyrics.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class LyricFixtures {
    private LyricFixtures() {}

    /** Minimal FLAC metadata with one Vorbis LYRICS comment; no recorded audio. */
    public static byte[] flacWithLyrics(String lyrics) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        result.write(new byte[]{0, 0, 0, 34});
        byte[] streamInfo = new byte[34];
        ByteBuffer.wrap(streamInfo).putShort((short) 4096).putShort((short) 4096);
        long properties = (44100L << 44) | (1L << 41) | (15L << 36) | 44100L;
        ByteBuffer.wrap(streamInfo, 10, 8).putLong(properties);
        result.write(streamInfo);
        byte[] vendor = "synthetic-fixture".getBytes(StandardCharsets.UTF_8);
        byte[] comment = ("LYRICS=" + lyrics).getBytes(StandardCharsets.UTF_8);
        ByteBuffer vorbis = ByteBuffer.allocate(4 + vendor.length + 4 + 4 + comment.length).order(ByteOrder.LITTLE_ENDIAN);
        vorbis.putInt(vendor.length).put(vendor).putInt(1).putInt(comment.length).put(comment);
        int size = vorbis.capacity();
        result.write(new byte[]{(byte) 0x84, (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        result.write(vorbis.array());
        return result.toByteArray();
    }
}
