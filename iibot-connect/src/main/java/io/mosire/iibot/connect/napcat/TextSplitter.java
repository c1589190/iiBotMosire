package io.mosire.iibot.connect.napcat;

import java.util.ArrayList;
import java.util.List;

/** 按 Unicode code point 与自然边界安全切分文本。 */
final class TextSplitter {

    private TextSplitter() {
    }

    static List<String> split(List<String> segments, int maxCodePoints) {
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints 必须大于 0");
        }
        List<String> result = new ArrayList<>();
        for (String segment : segments) {
            splitOne(segment, maxCodePoints, result);
        }
        return List.copyOf(result);
    }

    private static void splitOne(String text, int maxCodePoints, List<String> out) {
        String remaining = text;
        while (!remaining.isEmpty()) {
            int codePoints = remaining.codePointCount(0, remaining.length());
            if (codePoints <= maxCodePoints) {
                out.add(remaining);
                return;
            }
            int hardCut = remaining.offsetByCodePoints(0, maxCodePoints);
            int cut = preferredBoundary(remaining, hardCut);
            if (cut <= 0) {
                cut = hardCut;
            }
            out.add(remaining.substring(0, cut));
            remaining = remaining.substring(cut);
        }
    }

    private static int preferredBoundary(String text, int hardCut) {
        int newline = text.lastIndexOf('\n', hardCut - 1);
        if (newline >= 0 && newline + 1 >= hardCut / 2) {
            return newline + 1;
        }
        for (int index = hardCut - 1; index > 0; index--) {
            char ch = text.charAt(index);
            if (Character.isWhitespace(ch)) {
                return index + 1;
            }
        }
        return hardCut;
    }
}
