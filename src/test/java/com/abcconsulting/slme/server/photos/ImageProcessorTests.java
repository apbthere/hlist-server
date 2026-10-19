package com.abcconsulting.slme.server.photos;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTests {

	private static BufferedImage read(byte[] jpeg) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(jpeg));
	}

	@Test
	void shrinksLargePhotosAndMakesAThumbnail() throws IOException {
		ImageProcessor.Processed processed = ImageProcessor.process(TestImages.jpeg(4000, 3000));
		assertEquals(1600, processed.width());
		assertEquals(1200, processed.height());
		BufferedImage image = read(processed.image());
		assertEquals(1600, image.getWidth());
		BufferedImage thumbnail = read(processed.thumbnail());
		assertEquals(256, thumbnail.getWidth());
		assertEquals(192, thumbnail.getHeight());
	}

	@Test
	void keepsSmallPhotosAtTheirSize() throws IOException {
		ImageProcessor.Processed processed = ImageProcessor.process(TestImages.jpeg(640, 480));
		assertEquals(640, read(processed.image()).getWidth());
	}

	@Test
	void removesLocationAndOtherMetadata() {
		byte[] upload = TestImages.jpegWithGps(800, 600);
		assertTrue(TestImages.text(upload).contains(TestImages.GPS_MARKER), "the test photo carries a location");
		ImageProcessor.Processed processed = ImageProcessor.process(upload);
		assertFalse(TestImages.text(processed.image()).contains("Exif"), "no EXIF segment in the stored photo");
		assertFalse(TestImages.text(processed.image()).contains(TestImages.GPS_MARKER));
		assertFalse(TestImages.text(processed.thumbnail()).contains(TestImages.GPS_MARKER));
	}

	@Test
	void turnsTransparentPngsIntoJpegsOnWhite() throws IOException {
		ImageProcessor.Processed processed = ImageProcessor.process(TestImages.transparentPng(400, 400));
		BufferedImage image = read(processed.image());
		int corner = image.getRGB(399, 399) & 0xFFFFFF;
		assertTrue(((corner >> 16) & 0xFF) > 240 && ((corner >> 8) & 0xFF) > 240 && (corner & 0xFF) > 240,
				"transparent areas become white");
		assertEquals((byte) 0xFF, processed.image()[0]);
		assertEquals((byte) 0xD8, processed.image()[1]);
	}

	@Test
	void refusesThingsThatArentPictures() {
		assertThrows(IllegalArgumentException.class,
				() -> ImageProcessor.process("<html>not a picture</html>".getBytes(StandardCharsets.UTF_8)));
		assertThrows(IllegalArgumentException.class, () -> ImageProcessor.process(new byte[] {1, 2, 3}));
	}

	@Test
	void refusesHugeImagesBeforeDecodingThem() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> ImageProcessor.process(TestImages.pngClaiming(20_000, 20_000)));
		assertTrue(error.getMessage().contains("too large"), error.getMessage());
	}
}
