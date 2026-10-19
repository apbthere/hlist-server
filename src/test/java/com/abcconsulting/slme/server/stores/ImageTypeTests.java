package com.abcconsulting.slme.server.stores;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageTypeTests {

	static byte[] bytes(int... values) {
		byte[] bytes = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			bytes[i] = (byte) values[i];
		}
		return bytes;
	}

	@Test
	void recognisesRasterImagesByContent() {
		assertEquals(Optional.of("image/png"), ImageType.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)));
		assertEquals(Optional.of("image/jpeg"), ImageType.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
		assertEquals(Optional.of("image/x-icon"), ImageType.detect(bytes(0, 0, 1, 0, 1, 0)));
		assertEquals(Optional.of("image/gif"), ImageType.detect("GIF89a".getBytes(StandardCharsets.US_ASCII)));
		assertEquals(Optional.of("image/webp"), ImageType.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	void rejectsEverythingElse() {
		assertEquals(Optional.empty(), ImageType.detect("<!doctype html><html>".getBytes(StandardCharsets.UTF_8)));
		assertEquals(Optional.empty(), ImageType.detect("<svg xmlns=\"http://www.w3.org/2000/svg\">".getBytes(StandardCharsets.UTF_8)));
		assertEquals(Optional.empty(), ImageType.detect(new byte[0]));
	}
}
