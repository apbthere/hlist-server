package com.abcconsulting.slme.server.stores;

import java.util.Optional;

/** Recognises raster images by their first bytes, whatever the server claimed (Aldi's favicon.ico is a PNG). */
final class ImageType {

    private ImageType() {
    }

    static Optional<String> detect(byte[] bytes) {
        if (startsWith(bytes, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (startsWith(bytes, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, 'G', 'I', 'F', '8')) {
            return Optional.of("image/gif");
        }
        if (startsWith(bytes, 0x00, 0x00, 0x01, 0x00)) {
            return Optional.of("image/x-icon");
        }
        if (bytes.length >= 12 && startsWith(bytes, 'R', 'I', 'F', 'F')
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
