package com.abcconsulting.slme.server.photos;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** Builds test pictures in memory. */
final class TestImages {

	static final String GPS_MARKER = "GPSLatitude=27.9506N";

	private TestImages() {
	}

	static byte[] jpeg(int width, int height) {
		return encode(picture(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
	}

	static byte[] transparentPng(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(new Color(200, 30, 30, 255));
		graphics.fillOval(0, 0, width / 2, height / 2);
		graphics.dispose();
		return encode(image, "png");
	}

	/** A JPEG with an EXIF (APP1) segment carrying a fake GPS position, like a phone camera photo. */
	static byte[] jpegWithGps(int width, int height) {
		byte[] plain = jpeg(width, height);
		byte[] payload = ("Exif\0\0" + GPS_MARKER).getBytes(StandardCharsets.ISO_8859_1);
		ByteBuffer withExif = ByteBuffer.allocate(plain.length + 4 + payload.length);
		withExif.put(plain, 0, 2);                       // SOI
		withExif.put((byte) 0xFF).put((byte) 0xE1);      // APP1
		withExif.putShort((short) (payload.length + 2));
		withExif.put(payload);
		withExif.put(plain, 2, plain.length - 2);
		return withExif.array();
	}

	/** A PNG whose header claims a huge size; only the header is valid, so it must be refused before decoding. */
	static byte[] pngClaiming(int width, int height) {
		ByteBuffer png = ByteBuffer.allocate(8 + 25);
		png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
		ByteBuffer header = ByteBuffer.allocate(17);
		header.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(width).putInt(height)
				.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
		CRC32 crc = new CRC32();
		crc.update(header.array());
		png.putInt(13).put(header.array()).putInt((int) crc.getValue());
		return png.array();
	}

	static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}

	private static BufferedImage picture(int width, int height, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(new Color(36, 89, 65));
		graphics.fillRect(0, 0, width, height);
		graphics.setColor(Color.ORANGE);
		graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
		graphics.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(image, format, bytes);
			return bytes.toByteArray();
		} catch (IOException exception) {
			throw new UncheckedIOException(exception);
		}
	}
}
