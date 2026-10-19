package com.abcconsulting.slme.server.photos;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Item photos. Upload the picture itself as the request body (POST /api/photos), then show it on an item with
 * PUT /api/items/{itemId}/photo. Photos are private to their owner and never change, so clients may keep them.
 */
@RestController
@RequestMapping("/api")
public class PhotoController {

    private static final int MAX_SEARCH_LENGTH = 200;

    private final PhotoService photoService;
    private final ProductPhotoFinder productPhotoFinder;

    public PhotoController(PhotoService photoService, ProductPhotoFinder productPhotoFinder) {
        this.photoService = photoService;
        this.productPhotoFinder = productPhotoFinder;
    }

    @PostMapping("/photos")
    public ResponseEntity<PhotoService.PhotoInfo> upload(Authentication authentication, HttpServletRequest request)
            throws IOException {
        if (request.getContentLengthLong() > PhotoService.MAX_UPLOAD_BYTES) {
            throw new PhotoTooLargeException();
        }
        PhotoService.PhotoInfo photo = photoService.upload(authentication.getName(), request.getInputStream());
        return ResponseEntity.status(HttpStatus.CREATED).body(photo);
    }

    /** A picture from a web address: an image's address or a product page (its og:image, and the product's name). */
    @PostMapping("/photos/import")
    public ResponseEntity<PhotoService.ImportedPhoto> importFromWeb(Authentication authentication,
            @RequestBody ImportRequest request) {
        PhotoService.ImportedPhoto photo = photoService.importFromWeb(authentication.getName(), request.url());
        return ResponseEntity.status(HttpStatus.CREATED).body(photo);
    }

    /** Photos of products matching an item's name and brand, to choose from (attach one with /photos/import). */
    @GetMapping("/photos/suggestions")
    public List<ProductPhotoFinder.Suggestion> suggestions(@RequestParam String name,
            @RequestParam(required = false) String brand) {
        if (name.isBlank() || name.length() > MAX_SEARCH_LENGTH || (brand != null && brand.length() > MAX_SEARCH_LENGTH)) {
            throw new IllegalArgumentException("Enter an item name of up to " + MAX_SEARCH_LENGTH + " characters.");
        }
        return productPhotoFinder.find(name, brand);
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<byte[]> photo(Authentication authentication, @PathVariable long photoId) {
        return serve(authentication, photoId, false);
    }

    @GetMapping("/photos/{photoId}/thumbnail")
    public ResponseEntity<byte[]> thumbnail(Authentication authentication, @PathVariable long photoId) {
        return serve(authentication, photoId, true);
    }

    @PutMapping("/items/{itemId}/photo")
    public ResponseEntity<Void> setItemPhoto(Authentication authentication, @PathVariable long itemId,
            @RequestBody PhotoRequest request) {
        if (request.photoId() == null) {
            throw new IllegalArgumentException("photoId is required");
        }
        photoService.setItemPhoto(itemId, request.photoId(), authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/items/{itemId}/photo")
    public ResponseEntity<Void> removeItemPhoto(Authentication authentication, @PathVariable long itemId) {
        photoService.setItemPhoto(itemId, null, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<byte[]> serve(Authentication authentication, long photoId, boolean thumbnail) {
        return photoService.image(photoId, authentication.getName(), thumbnail)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record PhotoRequest(Long photoId) {
    }

    public record ImportRequest(String url) {
    }
}
