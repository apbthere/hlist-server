package com.abcconsulting.slme.server.photos;

import com.abcconsulting.slme.server.live.DataChangedEvent;
import com.abcconsulting.slme.server.model.Item;
import com.abcconsulting.slme.server.repository.ItemRepository;
import com.abcconsulting.slme.server.repository.ShoppingListItemDao;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Item photos: upload, serving to their owner, attaching to items and cleaning up unused ones. */
@Service
public class PhotoService {

    static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    /** Uploaded photos that no item uses after this long (an editor that was cancelled) are deleted. */
    static final Duration UNUSED_PHOTO_GRACE = Duration.ofDays(1);

    public record PhotoInfo(long photoId, int width, int height) {
    }

    /** A photo downloaded from the web, with the product's name and brand when it came from a product page. */
    public record ImportedPhoto(long photoId, int width, int height, String title, String brand) {
    }

    private final PhotoRepository photos;
    private final ItemRepository items;
    private final ShoppingListItemDao listItems;
    private final ApplicationEventPublisher events;
    private final PhotoImporter importer;

    public PhotoService(PhotoRepository photos, ItemRepository items, ShoppingListItemDao listItems,
            ApplicationEventPublisher events, PhotoImporter importer) {
        this.photos = photos;
        this.items = items;
        this.listItems = listItems;
        this.events = events;
        this.importer = importer;
    }

    public PhotoInfo upload(String username, InputStream body) throws IOException {
        byte[] upload = body.readNBytes(MAX_UPLOAD_BYTES + 1);
        if (upload.length > MAX_UPLOAD_BYTES) {
            throw new PhotoTooLargeException();
        }
        if (upload.length == 0) {
            throw new IllegalArgumentException("No picture was sent.");
        }
        return store(username, upload);
    }

    /** Downloads the picture at a web address (an image, or a product page's image) and stores it. */
    public ImportedPhoto importFromWeb(String username, String address) {
        PhotoImporter.Fetched fetched = importer.fetchPicture(address);
        PhotoInfo photo = store(username, fetched.picture());
        return new ImportedPhoto(photo.photoId(), photo.width(), photo.height(), fetched.title(), fetched.brand());
    }

    private PhotoInfo store(String username, byte[] picture) {
        ImageProcessor.Processed processed = ImageProcessor.process(picture);
        long photoId = photos.insert(username, processed);
        return new PhotoInfo(photoId, processed.width(), processed.height());
    }

    public Optional<byte[]> image(long photoId, String username, boolean thumbnail) {
        return photos.find(photoId, username, thumbnail);
    }

    /** Shows the photo on the item (replacing any previous one); null removes it. */
    @Transactional
    public void setItemPhoto(long itemId, Long photoId, String username) {
        Item item = items.findById(itemId)
                .filter(candidate -> username.equals(candidate.username()))
                .orElseThrow(() -> new NoSuchElementException("Item not found"));
        if (photoId != null && !photos.belongsTo(photoId, username)) {
            throw new IllegalArgumentException("Photo not found");
        }
        if (Objects.equals(item.photoId(), photoId)) {
            return;
        }
        photos.setItemPhoto(itemId, username, photoId);
        if (item.photoId() != null) {
            photos.deleteIfUnused(item.photoId());
        }
        events.publishEvent(DataChangedEvent.items(username));
        listItems.findListIdsContainingItem(itemId)
                .forEach(listId -> events.publishEvent(DataChangedEvent.listItems(username, listId)));
    }

    @Scheduled(cron = "0 45 4 * * *")
    public void deleteUnusedPhotos() {
        photos.deleteUnusedOlderThan(OffsetDateTime.now().minus(UNUSED_PHOTO_GRACE));
    }
}
