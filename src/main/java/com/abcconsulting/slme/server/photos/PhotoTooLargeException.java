package com.abcconsulting.slme.server.photos;

/** An upload above {@link PhotoService#MAX_UPLOAD_BYTES}; answered with 413. */
public class PhotoTooLargeException extends RuntimeException {

    public PhotoTooLargeException() {
        super("That picture is too big to upload (over " + PhotoService.MAX_UPLOAD_BYTES / (1024 * 1024) + " MB).");
    }
}
