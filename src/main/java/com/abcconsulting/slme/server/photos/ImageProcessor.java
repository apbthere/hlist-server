package com.abcconsulting.slme.server.photos;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Turns an uploaded picture into a stored photo: a JPEG at most {@link #MAX_EDGE} px on its long side and a
 * {@link #THUMBNAIL_EDGE} px thumbnail. Re-encoding drops all metadata, including the GPS location phone photos
 * carry. Only JPEG, PNG and GIF are accepted, and the size is checked before decoding so a small file can't
 * expand into a huge image.
 */
final class ImageProcessor {

    static final int MAX_EDGE = 1600;
    static final int THUMBNAIL_EDGE = 256;
    static final int MAX_SOURCE_EDGE = 12_000;
    static final long MAX_SOURCE_PIXELS = 50_000_000L;
    private static final Set<String> FORMATS = Set.of("jpeg", "png", "gif");
    private static final float JPEG_QUALITY = 0.85f;

    record Processed(byte[] image, byte[] thumbnail, int width, int height) {
    }

    private ImageProcessor() {
    }

    /** Whether the bytes are a picture in a format HList reads (JPEG, PNG or GIF). */
    static boolean readable(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            return readers != null && readers.hasNext()
                    && FORMATS.contains(readers.next().getFormatName().toLowerCase(Locale.ROOT));
        } catch (IOException exception) {
            return false;
        }
    }

    static Processed process(byte[] upload) {
        BufferedImage source = decode(upload);
        BufferedImage image = scale(source, MAX_EDGE);
        return new Processed(encodeJpeg(image), encodeJpeg(scale(image, THUMBNAIL_EDGE)), image.getWidth(), image.getHeight());
    }

    private static BufferedImage decode(byte[] upload) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw new IllegalArgumentException("That file isn't a picture HList can use (JPEG, PNG or GIF).");
            }
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("That file isn't a picture HList can use (JPEG, PNG or GIF).");
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new IllegalArgumentException("That picture is too large (" + width + "×" + height + ").");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new IllegalArgumentException("That picture couldn't be read.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("That picture couldn't be read.", exception);
        }
    }

    /** Scales down so the long edge is at most maxEdge, on a white background (for transparent PNGs). */
    private static BufferedImage scale(BufferedImage source, int maxEdge) {
        double factor = Math.min(1.0, (double) maxEdge / Math.max(source.getWidth(), source.getHeight()));
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage current = toRgb(source);
        // Halve in steps first: a single large bilinear step looks jagged.
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return current.getWidth() == targetWidth && current.getHeight() == targetHeight
                ? current : resize(current, targetWidth, targetHeight);
    }

    private static BufferedImage toRgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException exception) {
            throw new IllegalStateException("Couldn't encode photo", exception);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
