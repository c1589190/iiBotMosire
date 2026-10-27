package io.mosire.iibot.connect.napcat;

import io.mosire.iibot.connect.core.OutboundSegment;

import java.util.ArrayList;
import java.util.List;

/** 保留 at/reply 位置与顺序的富消息切分器。 */
final class RichMessageSplitter {

    private RichMessageSplitter() {
    }

    static List<List<OutboundSegment>> split(List<OutboundSegment> segments, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints 必须大于 0");
        }
        List<List<OutboundSegment>> chunks = new ArrayList<>();
        List<OutboundSegment> current = new ArrayList<>();
        int textLength = 0;
        for (OutboundSegment segment : segments) {
            if (segment instanceof OutboundSegment.Text text) {
                for (String piece : TextSplitter.split(List.of(text.text()), maxCodePoints)) {
                    int pieceLength = piece.codePointCount(0, piece.length());
                    if (textLength > 0 && textLength + pieceLength > maxCodePoints) {
                        chunks.add(List.copyOf(current));
                        current.clear();
                        textLength = 0;
                    }
                    current.add(new OutboundSegment.Text(piece));
                    textLength += pieceLength;
                    if (textLength >= maxCodePoints) {
                        chunks.add(List.copyOf(current));
                        current.clear();
                        textLength = 0;
                    }
                }
            } else {
                if (textLength >= maxCodePoints) {
                    chunks.add(List.copyOf(current));
                    current.clear();
                    textLength = 0;
                }
                current.add(segment);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("富消息没有可发送内容");
        }
        return List.copyOf(chunks);
    }
}
