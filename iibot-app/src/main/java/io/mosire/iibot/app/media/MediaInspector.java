package io.mosire.iibot.app.media;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 大图/GIF 的有界查看器：只解码首帧，按源图降采样后再切块，避免把原图直接塞给模型。
 *
 * <p>所有输出都是 JPEG，单张最大 {@code maxWidth}，块数和方法参数均有硬上限。</p>
 */
public final class MediaInspector {

    public static final int MAX_TILES = 9;
    public static final int MIN_EDGE = 160;
    public static final int MAX_EDGE = 1600;

    private MediaInspector() {
    }

    /** One rendered tile. */
    public record Tile(byte[] bytes, int width, int height, int row, int column) {
        public Tile {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Rendered result with source dimensions. */
    public record Result(int sourceWidth, int sourceHeight, List<Tile> tiles) {
        public Result {
            tiles = List.copyOf(tiles);
        }
    }

    /**
     * Decode the first frame and return one scaled image or a row/column grid.
     *
     * @param bytes image bytes
     * @param mediaType MIME type, used only for image/* validation
     * @param grid whether to split into tiles
     * @param rows grid rows (1..3)
     * @param columns grid columns (1..3)
     * @param maxWidth output edge cap (160..1600)
     * @return bounded JPEG tiles
     */
    public static Result inspect(byte[] bytes, String mediaType, boolean grid,
                                 int rows, int columns, int maxWidth) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("媒体字节为空");
        }
        if (mediaType == null || !mediaType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw new IOException("不是图片媒体");
        }
        int edge = Math.max(MIN_EDGE, Math.min(MAX_EDGE, maxWidth));
        int rowCount = grid ? Math.max(1, Math.min(3, rows)) : 1;
        int columnCount = grid ? Math.max(1, Math.min(3, columns)) : 1;
        if (rowCount * columnCount > MAX_TILES) {
            throw new IOException("分块数超过上限");
        }

        BufferedImage scaled = readAndScale(bytes, edge);
        List<Tile> tiles = new ArrayList<>();
        if (!grid) {
            tiles.add(tile(scaled, 0, 0, scaled.getWidth(), scaled.getHeight(), 0, 0));
            return new Result(scaled.getWidth(), scaled.getHeight(), tiles);
        }
        int tileWidth = Math.max(1, (scaled.getWidth() + columnCount - 1) / columnCount);
        int tileHeight = Math.max(1, (scaled.getHeight() + rowCount - 1) / rowCount);
        for (int row = 0; row < rowCount; row++) {
            for (int column = 0; column < columnCount; column++) {
                int x = column * tileWidth;
                int y = row * tileHeight;
                int width = Math.min(tileWidth, scaled.getWidth() - x);
                int height = Math.min(tileHeight, scaled.getHeight() - y);
                if (width <= 0 || height <= 0) {
                    continue;
                }
                tiles.add(tile(scaled, x, y, width, height, row, column));
            }
        }
        if (tiles.isEmpty()) {
            throw new IOException("分块结果为空");
        }
        return new Result(scaled.getWidth(), scaled.getHeight(), tiles);
    }

    private static BufferedImage readAndScale(byte[] bytes, int maxWidth) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("无法创建图片输入流");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("没有可用的图片解码器");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new IOException("图片尺寸非法");
                }
                int subsample = Math.max(1, Math.max(width, height) / maxWidth);
                ImageReadParam parameter = reader.getDefaultReadParam();
                parameter.setSourceSubsampling(subsample, subsample, 0, 0);
                BufferedImage source = reader.read(0, parameter);
                if (source == null) {
                    throw new IOException("图片首帧为空");
                }
                return scale(source, maxWidth);
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage scale(BufferedImage source, int maxWidth) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) maxWidth / Math.max(1, Math.max(width, height)));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));
        BufferedImage output = new BufferedImage(targetWidth, targetHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static Tile tile(BufferedImage source, int x, int y, int width, int height,
                             int row, int column) throws IOException {
        BufferedImage part = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = part.createGraphics();
        try {
            graphics.drawImage(source.getSubimage(x, y, width, height), 0, 0, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(part, "jpg", output)) {
            throw new IOException("JPEG 编码不可用");
        }
        return new Tile(output.toByteArray(), width, height, row, column);
    }
}
